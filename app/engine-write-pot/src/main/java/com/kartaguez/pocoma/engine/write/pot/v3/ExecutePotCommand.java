package com.kartaguez.pocoma.engine.write.pot.v3;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandStore.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.PotHistoryPort.*;

import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

/** One acquisition, one complete attempt. Scheduling and acquisition belong to Consumption. */
public final class ExecutePotCommand {
    public enum Status {
        COMPLETED,
        RETRY_SCHEDULED,
        QUARANTINED,
        CLAIM_LOST,
        RECOVERY_PENDING
    }

    public enum Cause {
        NONE,
        COLLISION,
        TECHNICAL,
        INCONSISTENT_FACTS,
        COMMIT_UNCERTAIN
    }

    public record Attempt(Status status, Optional<Result> result, Cause cause) {}

    public record RetryPolicy(int maximumFailures, Duration delay) {
        public RetryPolicy {
            if (maximumFailures < 1 || delay == null || delay.isNegative())
                throw new IllegalArgumentException("Invalid retry policy");
        }
    }

    private record Prepared(
            PotCommand command,
            Optional<Result> terminal,
            PotActorAuthority.Witness actor,
            long workingVersion,
            Result result,
            Runnable apply,
            List<Component> components) {}

    private static final class Collision extends RuntimeException {}

    private static final class ClaimLost extends RuntimeException {}

    private final PotCommandTransactions transactions;
    private final PotCommandStore store;
    private final PotActorAuthority authority;
    private final PotHistoryPort history;
    private final PotRevisionPort revisions;
    private final RetryPolicy retries;
    private final Clock clock;

    public ExecutePotCommand(
            PotCommandTransactions transactions,
            PotCommandStore store,
            PotActorAuthority authority,
            PotHistoryPort history,
            PotRevisionPort revisions,
            RetryPolicy retries,
            Clock clock) {
        this.transactions = Objects.requireNonNull(transactions);
        this.store = Objects.requireNonNull(store);
        this.authority = Objects.requireNonNull(authority);
        this.history = Objects.requireNonNull(history);
        this.revisions = Objects.requireNonNull(revisions);
        this.retries = Objects.requireNonNull(retries);
        this.clock = Objects.requireNonNull(clock);
    }

    public Attempt execute(UUID requestId, UUID claimToken) {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(claimToken);
        try {
            var prepared = transactions.snapshot(() -> prepare(store.load(requestId)));
            if (prepared.terminal().isPresent()) return completed(prepared.terminal().get());
            return transactions.write(() -> finalize(prepared, claimToken));
        } catch (PotCommandTransactions.Failure failure) {
            if (failure.resolution() == PotCommandTransactions.Resolution.UNKNOWN)
                return recover(requestId);
            var cause = failure.getCause();
            if (cause instanceof ClaimLost)
                return new Attempt(Status.CLAIM_LOST, Optional.empty(), Cause.NONE);
            Cause classified =
                    cause instanceof Collision
                            ? Cause.COLLISION
                            : cause instanceof InconsistentPotFactsException
                                    ? Cause.INCONSISTENT_FACTS
                                    : Cause.TECHNICAL;
            try {
                var recorded =
                        transactions.write(
                                () ->
                                        store.recordFailure(
                                                requestId,
                                                claimToken,
                                                retries.maximumFailures(),
                                                retries.delay()));
                return new Attempt(Status.valueOf(recorded.name()), Optional.empty(), classified);
            } catch (PotCommandTransactions.Failure recordingFailure) {
                // An uncertain cession is never repeated: the durable slot governs reacquisition.
                return new Attempt(
                        Status.RECOVERY_PENDING, Optional.empty(), Cause.COMMIT_UNCERTAIN);
            }
        }
    }

    private Attempt recover(UUID requestId) {
        try {
            var durable = transactions.snapshot(() -> store.load(requestId));
            if (durable.terminal().isPresent()) return completed(durable.terminal().get());
        } catch (PotCommandTransactions.Failure unresolved) {
            // The database may not yet be reachable or the original transaction still unresolved.
        }
        return new Attempt(Status.RECOVERY_PENDING, Optional.empty(), Cause.COMMIT_UNCERTAIN);
    }

    private Attempt finalize(Prepared prepared, UUID token) {
        var command = prepared.command();
        if (!store.protectClaim(command.requestId(), token)) throw new ClaimLost();
        var durable = store.load(command.requestId());
        if (durable.terminal().isPresent() || !durable.command().equals(command))
            throw new InconsistentPotFactsException("Request changed after preparation");
        if (!authority.protect(command, prepared.actor())) throw new Collision();
        if (!store.protectPot(command.potId(), prepared.workingVersion())) throw new Collision();
        prepared.apply().run();
        store.finish(command, token, prepared.result());
        store.publish(command, prepared.result(), prepared.components());
        return completed(prepared.result());
    }

    private Prepared prepare(Stored stored) {
        var c = stored.command();
        if (stored.terminal().isPresent())
            return new Prepared(c, stored.terminal(), null, 0, null, () -> {}, List.of());
        var actor = authority.observe(c);
        // Even invalid authority must be protected until its rejection commits.
        if (actor.actor().isEmpty())
            return rejected(c, actor, currentVersion(c), "INVALID_BINDING");
        var selected = history.current(c.potId(), actor.actor().get(), selection(c.intent()));
        long version = selected.map(p -> p.facts().workingVersion()).orElse(0L);
        if (c.intent() instanceof CreatePot intent) {
            return decision(
                    c,
                    actor,
                    version,
                    PotTransitions.create(
                            c.potId(), intent.label(), actor.actor().get(), selected.isPresent()),
                    p -> revisions.createPot(p, clock.instant()),
                    p ->
                            List.of(
                                    component("POT_HEADER", c.potId(), p.header()),
                                    component("POT_DETAILS", c.potId(), p.details())));
        }
        if (selected.isEmpty()) return rejected(c, actor, 0, "NOT_FOUND");
        return decide(c, actor, selected.get());
    }

    private long currentVersion(PotCommand c) {
        // Identity failure still needs a protected Pot observation for a consistent terminal unit.
        return store.currentVersion(c.potId());
    }

    private Prepared decide(PotCommand c, PotActorAuthority.Witness actor, SelectedPot p) {
        var facts = p.facts();
        long observed = c.observedVersion();
        long version = facts.workingVersion();
        var refs =
                p.shareholders().entrySet().stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        Map.Entry::getKey, e -> e.getValue().reference()));
        return switch (c.intent()) {
            case CreatePot ignored ->
                    throw new IllegalStateException("Creation handled separately");
            case UpdatePotDetails i ->
                    decision(
                            c,
                            actor,
                            version,
                            PotTransitions.updateDetails(
                                    facts, observed, present(p.details()), i.label()),
                            r -> revisions.revisePotDetails(c.potId(), r),
                            v -> List.of(component("POT_DETAILS", c.potId(), v)));
            case DeletePot i ->
                    decision(
                            c,
                            actor,
                            version,
                            PotTransitions.delete(facts, observed),
                            r -> revisions.revisePotHeader(c.potId(), r),
                            v -> List.of(component("POT_HEADER", c.potId(), v)));
            case AddShareholders i ->
                    decision(
                            c,
                            actor,
                            version,
                            ShareholderTransitions.create(facts, observed, i.targets(), refs),
                            r -> revisions.createShareholders(c.potId(), r),
                            states ->
                                    states.stream()
                                            .flatMap(
                                                    s ->
                                                            java.util.stream.Stream.of(
                                                                    component(
                                                                            "SHAREHOLDER_HEADER",
                                                                            s.header()
                                                                                    .value()
                                                                                    .shareholderId(),
                                                                            s.header()),
                                                                    component(
                                                                            "SHAREHOLDER_DETAILS",
                                                                            s.details()
                                                                                    .value()
                                                                                    .shareholderId(),
                                                                            s.details()),
                                                                    component(
                                                                            "SHAREHOLDER_WEIGHT",
                                                                            s.weight()
                                                                                    .value()
                                                                                    .shareholderId(),
                                                                            s.weight())))
                                            .toList());
            case UpdateShareholderDetails i ->
                    decision(
                            c,
                            actor,
                            version,
                            ShareholderTransitions.updateDetails(
                                    facts, observed, i.targets(), refs, shareholderDetails(p)),
                            r -> revisions.reviseShareholderDetails(c.potId(), r),
                            values ->
                                    values.entrySet().stream()
                                            .map(
                                                    e ->
                                                            component(
                                                                    "SHAREHOLDER_DETAILS",
                                                                    e.getKey(),
                                                                    e.getValue()))
                                            .toList());
            case UpdateShareholderWeights i ->
                    decision(
                            c,
                            actor,
                            version,
                            ShareholderTransitions.updateWeights(
                                    facts, observed, i.targets(), refs, shareholderWeights(p)),
                            r -> revisions.reviseShareholderWeights(c.potId(), r),
                            values ->
                                    values.entrySet().stream()
                                            .map(
                                                    e ->
                                                            component(
                                                                    "SHAREHOLDER_WEIGHT",
                                                                    e.getKey(),
                                                                    e.getValue()))
                                            .toList());
            case DeleteShareholder i ->
                    refs.get(i.id()).header().isEmpty()
                            ? rejected(c, actor, version, "NOT_FOUND")
                            : decision(
                                    c,
                                    actor,
                                    version,
                                    ShareholderTransitions.delete(
                                            facts,
                                            observed,
                                            refs.get(i.id()),
                                            history.activeUsage(c.potId(), version, i.id())),
                                    r -> revisions.reviseShareholderHeader(c.potId(), r),
                                    v -> List.of(component("SHAREHOLDER_HEADER", i.id(), v)));
            case CreateExpense i ->
                    decision(
                            c,
                            actor,
                            version,
                            ExpenseTransitions.create(
                                    facts,
                                    observed,
                                    i.details(),
                                    i.financials(),
                                    p.expenses().get(i.details().expenseId()).header()
                                            instanceof ComponentRead.Present<?>,
                                    refs),
                            r -> revisions.createExpense(c.potId(), r),
                            s ->
                                    List.of(
                                            component(
                                                    "EXPENSE_HEADER",
                                                    i.details().expenseId(),
                                                    s.header()),
                                            component(
                                                    "EXPENSE_DETAILS",
                                                    i.details().expenseId(),
                                                    s.details()),
                                            component(
                                                    "EXPENSE_FINANCIALS",
                                                    i.details().expenseId(),
                                                    s.financials())));
            case UpdateExpenseDetails i ->
                    expenseMissing(p, i.id())
                            ? rejected(c, actor, version, "NOT_FOUND")
                            : decision(
                                    c,
                                    actor,
                                    version,
                                    ExpenseTransitions.updateDetails(
                                            facts,
                                            observed,
                                            present(p.expenses().get(i.id()).header()),
                                            present(p.expenses().get(i.id()).details()),
                                            i.label(),
                                            i.date()),
                                    r -> revisions.reviseExpenseDetails(c.potId(), r),
                                    v -> List.of(component("EXPENSE_DETAILS", i.id(), v)));
            case UpdateExpenseFinancials i ->
                    expenseMissing(p, i.financials().expenseId())
                            ? rejected(c, actor, version, "NOT_FOUND")
                            : decision(
                                    c,
                                    actor,
                                    version,
                                    ExpenseTransitions.updateFinancials(
                                            facts,
                                            observed,
                                            present(
                                                    p.expenses()
                                                            .get(i.financials().expenseId())
                                                            .header()),
                                            present(
                                                    p.expenses()
                                                            .get(i.financials().expenseId())
                                                            .financials()),
                                            i.financials(),
                                            refs),
                                    r -> revisions.reviseExpenseFinancials(c.potId(), r),
                                    v ->
                                            List.of(
                                                    component(
                                                            "EXPENSE_FINANCIALS",
                                                            i.financials().expenseId(),
                                                            v)));
            case DeleteExpense i ->
                    expenseMissing(p, i.id())
                            ? rejected(c, actor, version, "NOT_FOUND")
                            : decision(
                                    c,
                                    actor,
                                    version,
                                    ExpenseTransitions.delete(
                                            facts,
                                            observed,
                                            expenseState(p.expenses().get(i.id()))),
                                    r -> revisions.reviseExpenseHeader(c.potId(), r),
                                    v -> List.of(component("EXPENSE_HEADER", i.id(), v)));
        };
    }

    private <T> Prepared decision(
            PotCommand c,
            PotActorAuthority.Witness actor,
            long version,
            TransitionResult<T> result,
            Consumer<TransitionResult.Changed<T>> apply,
            Function<T, List<Component>> components) {
        if (result instanceof TransitionResult.Rejected<T> rejected)
            return rejected(c, actor, version, rejected.reason().name());
        if (result instanceof TransitionResult.Unchanged<T> unchanged)
            return new Prepared(
                    c,
                    Optional.empty(),
                    actor,
                    version,
                    new Result(Outcome.UNCHANGED, unchanged.currentPotVersion(), null),
                    () -> {},
                    List.of());
        var changed = (TransitionResult.Changed<T>) result;
        return new Prepared(
                c,
                Optional.empty(),
                actor,
                version,
                new Result(Outcome.CHANGED, changed.candidatePotVersion(), null),
                () -> {
                    if (!(c.intent() instanceof CreatePot))
                        revisions.recordVersion(
                                c.potId(),
                                changed.expectedPotVersion(),
                                changed.candidatePotVersion(),
                                clock.instant());
                    apply.accept(changed);
                },
                List.copyOf(components.apply(changed.changes())));
    }

    private static Prepared rejected(
            PotCommand c, PotActorAuthority.Witness actor, long version, String reason) {
        return new Prepared(
                c, Optional.empty(), actor, version, Result.rejected(reason), () -> {}, List.of());
    }

    private static Attempt completed(Result result) {
        return new Attempt(Status.COMPLETED, Optional.of(result), Cause.NONE);
    }

    private static Component component(String kind, EntityId id, Versioned<?> revision) {
        return new Component(kind, id.value(), revision.localVersion());
    }

    private static <T> Versioned<T> present(ComponentRead<T> read) {
        if (read instanceof ComponentRead.Present<T> value) return value.revision();
        throw new InconsistentPotFactsException("Required selected component missing");
    }

    private static boolean expenseMissing(SelectedPot p, ExpenseId id) {
        return p.expenses().get(id).header() instanceof ComponentRead.Absent<?>;
    }

    private static ExpenseState expenseState(SelectedExpense e) {
        return new ExpenseState(present(e.header()), present(e.details()), present(e.financials()));
    }

    private static Map<ShareholderId, Versioned<ShareholderDetails>> shareholderDetails(
            SelectedPot p) {
        var result = new HashMap<ShareholderId, Versioned<ShareholderDetails>>();
        p.shareholders()
                .forEach(
                        (id, s) -> {
                            if (s.details() instanceof ComponentRead.Present<ShareholderDetails> v)
                                result.put(id, v.revision());
                        });
        return Map.copyOf(result);
    }

    private static Map<ShareholderId, Versioned<ShareholderWeight>> shareholderWeights(
            SelectedPot p) {
        var result = new HashMap<ShareholderId, Versioned<ShareholderWeight>>();
        p.shareholders()
                .forEach(
                        (id, s) -> {
                            if (s.weight() instanceof ComponentRead.Present<ShareholderWeight> v)
                                result.put(id, v.revision());
                        });
        return Map.copyOf(result);
    }

    private static Selection selection(Intent intent) {
        Set<ShareholderId> shareholders =
                switch (intent) {
                    case AddShareholders i ->
                            i.targets().stream()
                                    .map(ShareholderTransitions.Creation::id)
                                    .collect(Collectors.toSet());
                    case UpdateShareholderDetails i ->
                            i.targets().stream()
                                    .map(ShareholderTransitions.DetailsIntent::id)
                                    .collect(Collectors.toSet());
                    case UpdateShareholderWeights i ->
                            i.targets().stream()
                                    .map(ShareholderTransitions.WeightIntent::id)
                                    .collect(Collectors.toSet());
                    case DeleteShareholder i -> Set.of(i.id());
                    case CreateExpense i -> referenceIds(i.financials());
                    case UpdateExpenseFinancials i -> referenceIds(i.financials());
                    default -> Set.of();
                };
        Set<ExpenseId> expenses =
                switch (intent) {
                    case CreateExpense i -> Set.of(i.details().expenseId());
                    case UpdateExpenseDetails i -> Set.of(i.id());
                    case UpdateExpenseFinancials i -> Set.of(i.financials().expenseId());
                    case DeleteExpense i -> Set.of(i.id());
                    default -> Set.of();
                };
        return new Selection(
                shareholders,
                expenses,
                intent instanceof UpdatePotDetails || intent instanceof CreatePot,
                intent instanceof UpdateShareholderDetails,
                intent instanceof UpdateShareholderWeights,
                intent instanceof UpdateExpenseDetails || intent instanceof DeleteExpense,
                intent instanceof UpdateExpenseFinancials || intent instanceof DeleteExpense);
    }

    private static Set<ShareholderId> referenceIds(ExpenseFinancials f) {
        var ids = new HashSet<>(f.participants().keySet());
        ids.add(f.payerId());
        return Set.copyOf(ids);
    }
}
