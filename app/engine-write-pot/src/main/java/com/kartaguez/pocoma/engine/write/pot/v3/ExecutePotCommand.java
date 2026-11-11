package com.kartaguez.pocoma.engine.write.pot.v3;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommandStore.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.v3.PotHistoryPort.*;

import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

/** Command participant: business preparation, revalidation, effects and publication. */
public final class ExecutePotCommand implements ConsumptionParticipant {
    private record Proposal(
            PotCommand command,
            PotActorAuthority.Witness actor,
            long workingVersion,
            Result result,
            Runnable apply,
            List<Component> components) {}

    public static final class Collision extends RuntimeException {}

    private final PotCommandStore store;
    private final PotCommandObservation observation;
    private final PotActorAuthority authority;
    private final PotHistoryPort history;
    private final PotRevisionPort revisions;
    private final Clock clock;
    private InvitationStore invitations;

    public ExecutePotCommand(
            PotCommandStore store,
            PotCommandObservation observation,
            PotActorAuthority authority,
            PotHistoryPort history,
            PotRevisionPort revisions,
            Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.observation = Objects.requireNonNull(observation);
        this.authority = Objects.requireNonNull(authority);
        this.history = Objects.requireNonNull(history);
        this.revisions = Objects.requireNonNull(revisions);
        this.clock = Objects.requireNonNull(clock);
    }

    public ExecutePotCommand(
            PotCommandStore store,
            PotCommandObservation observation,
            PotActorAuthority authority,
            PotHistoryPort history,
            PotRevisionPort revisions,
            Clock clock,
            InvitationStore invitations) {
        this(store, observation, authority, history, revisions, clock);
        this.invitations = Objects.requireNonNull(invitations);
    }

    @Override
    public Optional<ConsumptionParticipant.Prepared> prepare(
            UUID requestId, PreparationReads reads) {
        return reads.source(context -> prepareInSnapshot(requestId));
    }

    private Optional<ConsumptionParticipant.Prepared> prepareInSnapshot(UUID requestId) {
        var stored = store.load(requestId);
        if (stored.terminal().isPresent()) return Optional.empty();
        var proposal = new Proposal[] {prepare(stored)};
        return Optional.of(
                new ConsumptionParticipant.Prepared() {
                    @Override
                    public void finalizeIn(SlotCompletion completion) {
                        finalizeProposal(proposal[0], completion, actual -> proposal[0] = actual);
                    }

                    @Override
                    public void requireCompletionReady() {
                        store.requireCompletionReady(
                                proposal[0].command(),
                                proposal[0].result(),
                                proposal[0].components());
                    }
                });
    }

    @Override
    public Recovery prepareRecovery(UUID requestId, PreparationReads reads) {
        return context ->
                switch (observation.observe(requestId, context)) {
                    case COMPLETED -> EffectEvidence.SATISFIED;
                    case NOT_COMPLETED -> EffectEvidence.MISSING;
                    case INCONSISTENT -> EffectEvidence.DIVERGENT;
                    case UNAVAILABLE -> EffectEvidence.UNAVAILABLE;
                };
    }

    private void finalizeProposal(
            Proposal prepared, SlotCompletion completion, Consumer<Proposal> actual) {
        var command = prepared.command();
        var durable = store.load(command.requestId());
        if (durable.terminal().isPresent() || !durable.command().equals(command))
            throw new InconsistentPotFactsException("Request changed after preparation");
        if (!authority.protect(command, prepared.actor())) throw new Collision();
        if (prepared.actor().actor().isPresent()
                && !store.protectPot(command.potId(), prepared.workingVersion()))
            throw new Collision();
        if (prepared.result().outcome() != Outcome.REJECTED) {
            Instant expiry =
                    command.intent() instanceof CreateInvitation i
                            ? i.expiresAt()
                            : command.intent()
                                            instanceof PotCommand.AttachShareholderViaInvitation i
                                    ? requireInvitations()
                                            .find(i.tokenHash())
                                            .orElseThrow()
                                            .expiresAt()
                                    : null;
            if (expiry != null && !requireInvitations().unexpired(expiry))
                prepared =
                        rejected(
                                command,
                                prepared.actor(),
                                prepared.workingVersion(),
                                "INVALID_INVITATION");
        }
        actual.accept(prepared);
        prepared.apply().run();
        store.finishTerminal(command, prepared.result());
        completion.complete();
        store.publish(command, prepared.result(), prepared.components());
    }

    private Proposal prepare(Stored stored) {
        var c = stored.command();
        var actor = authority.observe(c);
        // Even invalid authority must be protected until its rejection commits.
        if (actor.actor().isEmpty()) return rejected(c, actor, 0, "INVALID_BINDING");
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
        if (c.intent() instanceof CreateInvitation i) {
            var facts = selected.get().facts();
            if (facts.header().value().deleted()) return rejected(c, actor, version, "DELETED");
            if (!facts.authorization().creator()) return rejected(c, actor, version, "FORBIDDEN");
            var invitation = requireInvitations().prepare(c, i);
            return new Proposal(
                    c,
                    actor,
                    version,
                    new Result(Outcome.INVITATION_CREATED, null, null),
                    () -> requireInvitations().insert(invitation),
                    List.of());
        }
        if (c.intent() instanceof PotCommand.AttachShareholderViaInvitation i) {
            var invitation = requireInvitations().find(i.tokenHash());
            if (invitation.isEmpty() || !invitation.get().potId().equals(c.potId().value()))
                return rejected(c, actor, version, "INVALID_INVITATION");
            var p = selected.get();
            var target = p.shareholders().get(i.shareholderId());
            Versioned<ShareholderDetails> details =
                    target.details() instanceof ComponentRead.Present<ShareholderDetails> d
                            ? d.revision()
                            : null;
            return decision(
                    c,
                    actor,
                    version,
                    new com.kartaguez.pocoma.domain.pot.v3.AttachShareholderViaInvitation(
                                    i.shareholderId())
                            .decide(p.facts(), target.reference(), details),
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
        }
        return decide(c, actor, selected.get());
    }

    private Proposal decide(PotCommand c, PotActorAuthority.Witness actor, SelectedPot p) {
        var facts = p.facts();
        long observed = c.observedVersion();
        long version = facts.workingVersion();
        var refs =
                p.shareholders().entrySet().stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        Map.Entry::getKey, e -> e.getValue().reference()));
        return switch (c.intent()) {
            case CreateInvitation i ->
                    throw new IllegalStateException("Invitation dispatch required");
            case PotCommand.AttachShareholderViaInvitation i ->
                    throw new IllegalStateException("Invitation dispatch required");
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
                            ShareholderTransitions.create(
                                    facts,
                                    observed,
                                    creationTargets(c, i, actor.actor().orElseThrow()),
                                    refs),
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
                                    facts,
                                    observed,
                                    detailTargets(c, i, actor.actor().orElseThrow(), p),
                                    refs,
                                    shareholderDetails(p)),
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

    private static List<ShareholderTransitions.Creation> creationTargets(
            PotCommand c, AddShareholders i, UserId actor) {
        if (c.attachments().isEmpty()) return i.targets();
        return i.targets().stream()
                .map(
                        t ->
                                new ShareholderTransitions.Creation(
                                        t.id(),
                                        t.name(),
                                        c.attachments().get(t.id()) == Attachment.SELF
                                                ? Optional.of(actor)
                                                : Optional.empty(),
                                        t.defaultWeight()))
                .toList();
    }

    private static List<ShareholderTransitions.DetailsIntent> detailTargets(
            PotCommand c, UpdateShareholderDetails i, UserId actor, SelectedPot p) {
        if (c.attachments().isEmpty()) return i.targets();
        return i.targets().stream()
                .map(
                        t -> {
                            Optional<UserId> user =
                                    switch (c.attachments().get(t.id())) {
                                        case SELF -> Optional.of(actor);
                                        case DETACH -> Optional.empty();
                                        case KEEP -> {
                                            var details = p.shareholders().get(t.id()).details();
                                            yield details
                                                            instanceof
                                                            ComponentRead.Present<
                                                                                    ShareholderDetails>
                                                                            v
                                                    ? v.revision().value().userId()
                                                    : Optional.empty();
                                        }
                                        default ->
                                                throw new InconsistentPotFactsException(
                                                        "Invalid durable attachment");
                                    };
                            return new ShareholderTransitions.DetailsIntent(t.id(), t.name(), user);
                        })
                .toList();
    }

    private <T> Proposal decision(
            PotCommand c,
            PotActorAuthority.Witness actor,
            long version,
            TransitionResult<T> result,
            Consumer<TransitionResult.Changed<T>> apply,
            Function<T, List<Component>> components) {
        if (result instanceof TransitionResult.Rejected<T> rejected)
            return rejected(c, actor, version, rejected.reason().name());
        if (result instanceof TransitionResult.Unchanged<T> unchanged)
            return new Proposal(
                    c,
                    actor,
                    version,
                    new Result(Outcome.UNCHANGED, unchanged.currentPotVersion(), null),
                    () -> {},
                    List.of());
        var changed = (TransitionResult.Changed<T>) result;
        return new Proposal(
                c,
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

    private static Proposal rejected(
            PotCommand c, PotActorAuthority.Witness actor, long version, String reason) {
        return new Proposal(c, actor, version, Result.rejected(reason), () -> {}, List.of());
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
                    case PotCommand.AttachShareholderViaInvitation i -> Set.of(i.shareholderId());
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
                intent instanceof UpdateShareholderDetails
                        || intent instanceof PotCommand.AttachShareholderViaInvitation,
                intent instanceof UpdateShareholderWeights,
                intent instanceof UpdateExpenseDetails || intent instanceof DeleteExpense,
                intent instanceof UpdateExpenseFinancials || intent instanceof DeleteExpense);
    }

    private InvitationStore requireInvitations() {
        if (invitations == null)
            throw new IllegalStateException("Invitation support not configured");
        return invitations;
    }

    private static Set<ShareholderId> referenceIds(ExpenseFinancials f) {
        var ids = new HashSet<>(f.participants().keySet());
        ids.add(f.payerId());
        return Set.copyOf(ids);
    }
}
