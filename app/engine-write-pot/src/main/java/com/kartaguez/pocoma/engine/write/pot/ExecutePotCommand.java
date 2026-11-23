package com.kartaguez.pocoma.engine.write.pot;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;

import com.kartaguez.pocoma.domain.pot.*;
import com.kartaguez.pocoma.domain.pot.expense.*;
import com.kartaguez.pocoma.domain.pot.invitation.*;
import com.kartaguez.pocoma.domain.pot.revision.*;
import com.kartaguez.pocoma.domain.pot.shareholder.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.*;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.PotCommandStore.*;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.*;
import com.kartaguez.pocoma.domain.pot.PotHistory.*;

import java.time.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

/** Command participant: business preparation, revalidation, effects and publication. */
public final class ExecutePotCommand implements ConsumptionParticipant {
    private record ProposalContext(
            PotCommand command, PotActorAuthority.Witness actor, long workingVersion) {}

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
    private final Supplier<UUID> ids;
    private final InvitationStore invitations;

    public ExecutePotCommand(
            PotCommandStore store,
            PotCommandObservation observation,
            PotActorAuthority authority,
            PotHistoryPort history,
            PotRevisionPort revisions,
            Clock clock) {
        this(store, observation, authority, history, revisions, clock, null, UUID::randomUUID);
    }

    public ExecutePotCommand(
            PotCommandStore store,
            PotCommandObservation observation,
            PotActorAuthority authority,
            PotHistoryPort history,
            PotRevisionPort revisions,
            Clock clock,
            Supplier<UUID> ids) {
        this(store, observation, authority, history, revisions, clock, null, ids);
    }

    public ExecutePotCommand(
            PotCommandStore store,
            PotCommandObservation observation,
            PotActorAuthority authority,
            PotHistoryPort history,
            PotRevisionPort revisions,
            Clock clock,
            InvitationStore invitations) {
        this(store, observation, authority, history, revisions, clock, Objects.requireNonNull(invitations), UUID::randomUUID);
    }

    public ExecutePotCommand(
            PotCommandStore store,
            PotCommandObservation observation,
            PotActorAuthority authority,
            PotHistoryPort history,
            PotRevisionPort revisions,
            Clock clock,
            InvitationStore invitations,
            Supplier<UUID> ids) {
        this.store = Objects.requireNonNull(store);
        this.observation = Objects.requireNonNull(observation);
        this.authority = Objects.requireNonNull(authority);
        this.history = Objects.requireNonNull(history);
        this.revisions = Objects.requireNonNull(revisions);
        this.clock = Objects.requireNonNull(clock);
        this.invitations = invitations;
        this.ids = Objects.requireNonNull(ids);
    }

    @Override
    public Optional<ConsumptionParticipant.Prepared> prepare(
            UUID requestId, PreparationReads reads) {
        return reads.source(context -> prepareInSnapshot(requestId));
    }

    private Optional<ConsumptionParticipant.Prepared> prepareInSnapshot(UUID requestId) {
        var stored = store.load(requestId);
        if (stored.terminal().isPresent()) return Optional.empty();
        return Optional.of(new PreparedCommand(prepare(stored)));
    }

    private final class PreparedCommand implements ConsumptionParticipant.Prepared {
        private Proposal current;

        PreparedCommand(Proposal initial) {
            this.current = initial;
        }

        @Override
        public void finalizeIn(SlotCompletion completion) {
            finalizeProposal(current, completion, actual -> current = actual);
        }

        @Override
        public void requireCompletionReady() {
            store.requireCompletionReady(
                    current.command(),
                    current.result(),
                    current.components());
        }
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
        protectRequestAndPot(prepared);
        prepared = enforceInvitationExpiry(prepared);
        actual.accept(prepared);
        prepared.apply().run();
        store.finishTerminal(prepared.command(), prepared.result());
        completion.complete();
        store.publish(prepared.command(), prepared.result(), prepared.components());
    }

    private void protectRequestAndPot(Proposal prepared) {
        var command = prepared.command();
        var durable = store.load(command.requestId());
        if (durable.terminal().isPresent()) {
            throw new InconsistentPotFactsException("Request changed after preparation");
        }
        if (!durable.command().equals(command)) {
            throw new InconsistentPotFactsException("Request changed after preparation");
        }
        if (!authority.protect(command, prepared.actor())) throw new Collision();
        PotId targetPot = prepared.result().effectivePotId(command);
        if (prepared.actor().actor().isPresent()
                && targetPot != null
                && !store.protectPot(targetPot, prepared.workingVersion())) {
            throw new Collision();
        }
    }

    private Proposal enforceInvitationExpiry(Proposal prepared) {
        if (prepared.result().outcome() == Outcome.REJECTED) return prepared;
        Instant expiry = invitationExpiry(prepared.command());
        if (expiry != null && !requireInvitations().unexpired(expiry)) {
            return rejected(
                    prepared.command(),
                    prepared.actor(),
                    prepared.workingVersion(),
                    "INVALID_INVITATION");
        }
        return prepared;
    }

    private Instant invitationExpiry(PotCommand command) {
        if (command.intent() instanceof CreateInvitation i) return i.expiresAt();
        if (command.intent() instanceof PotCommand.AttachShareholderViaInvitation i) {
            return requireInvitations().find(i.tokenHash()).orElseThrow().expiresAt();
        }
        return null;
    }

    private Proposal prepare(Stored stored) {
        var c = stored.command();
        var actor = authority.observe(c);
        // Even invalid authority must be protected until its rejection commits.
        if (actor.actor().isEmpty()) return rejected(c, actor, 0, "INVALID_BINDING");
        if (c.intent() instanceof CreatePot intent && c.potId() == null) {
            PotId assigned = new PotId(ids.get());
            var existing = history.current(assigned, actor.actor().get(), selection(c.intent()));
            if (existing.isPresent()) {
                throw new Collision();
            }
            return prepareCreatePot(new ProposalContext(c, actor, 0L), intent, assigned, false);
        }
        var selected = history.current(c.potId(), actor.actor().get(), selection(c.intent()));
        long version = selected.map(p -> p.facts().workingVersion()).orElse(0L);
        var ctx = new ProposalContext(c, actor, version);
        if (c.intent() instanceof CreatePot intent) {
            return prepareCreatePot(ctx, intent, c.potId(), selected.isPresent());
        }
        if (selected.isEmpty()) return rejected(c, actor, 0, "NOT_FOUND");
        if (c.intent() instanceof CreateInvitation i) {
            return prepareCreateInvitation(ctx, selected.get().facts(), i);
        }
        if (c.intent() instanceof PotCommand.AttachShareholderViaInvitation i) {
            return prepareAttachViaInvitation(ctx, selected.get(), i);
        }
        return decide(ctx, selected.get());
    }

    private Proposal prepareCreatePot(
            ProposalContext ctx, CreatePot intent, PotId targetPotId, boolean alreadyPresent) {
        return decision(
                ctx,
                targetPotId,
                PotTransitions.create(
                        targetPotId, intent.label(), ctx.actor().actor().get(), alreadyPresent),
                p -> revisions.createPot(p, clock.instant()),
                p ->
                        List.of(
                                component("POT_HEADER", targetPotId, p.header()),
                                component("POT_DETAILS", targetPotId, p.details())),
                p -> CreatedResources.pot(targetPotId.value()));
    }

    private Proposal prepareCreateInvitation(
            ProposalContext ctx, PotFacts facts, CreateInvitation intent) {
        if (facts.header().value().deleted()) {
            return rejected(ctx.command(), ctx.actor(), ctx.workingVersion(), "DELETED");
        }
        if (!facts.authorization().creator()) {
            return rejected(ctx.command(), ctx.actor(), ctx.workingVersion(), "FORBIDDEN");
        }
        var invitation = requireInvitations().prepare(ctx.command(), intent);
        return new Proposal(
                ctx.command(),
                ctx.actor(),
                ctx.workingVersion(),
                new Result(Outcome.INVITATION_CREATED, null, null, ctx.command().potId(), null),
                () -> requireInvitations().insert(invitation),
                List.of());
    }

    private Proposal prepareAttachViaInvitation(
            ProposalContext ctx, SelectedPot p, PotCommand.AttachShareholderViaInvitation intent) {
        var c = ctx.command();
        var invitation = requireInvitations().find(intent.tokenHash());
        if (invitation.isEmpty() || !invitation.get().potId().equals(c.potId().value())) {
            return rejected(c, ctx.actor(), ctx.workingVersion(), "INVALID_INVITATION");
        }
        var target = p.shareholders().get(intent.shareholderId());
        Versioned<ShareholderDetails> details =
                target.details() instanceof ComponentRead.Present<ShareholderDetails> d
                        ? d.revision()
                        : null;
        return decision(
                ctx,
                new com.kartaguez.pocoma.domain.pot.AttachShareholderViaInvitation(
                                intent.shareholderId())
                        .decide(p.facts(), target.reference(), details),
                r -> revisions.reviseShareholderDetails(c.potId(), r),
                values -> componentList("SHAREHOLDER_DETAILS", values));
    }

    private static final Set<Class<?>> EXPENSE_INTENTS =
            Set.of(
                    CreateExpense.class,
                    UpdateExpenseDetails.class,
                    UpdateExpenseFinancials.class,
                    DeleteExpense.class);

    private Proposal decide(ProposalContext ctx, SelectedPot p) {
        var c = ctx.command();
        var refs =
                p.shareholders().entrySet().stream()
                        .collect(
                                Collectors.toUnmodifiableMap(
                                        Map.Entry::getKey, e -> e.getValue().reference()));
        if (EXPENSE_INTENTS.contains(c.intent().getClass())) {
            return decideExpense(ctx, p, refs);
        }
        return decidePotOrShareholder(ctx, p, refs);
    }

    private Proposal decidePotOrShareholder(
            ProposalContext ctx, SelectedPot p, Map<ShareholderId, ShareholderReference> refs) {
        var c = ctx.command();
        return switch (c.intent()) {
            case UpdatePotDetails i ->
                    decision(
                            ctx,
                            PotTransitions.updateDetails(
                                    p.facts(), c.observedVersion(), present(p.details()), i.label()),
                            r -> revisions.revisePotDetails(c.potId(), r),
                            v -> List.of(component("POT_DETAILS", c.potId(), v)));
            case DeletePot i ->
                    decision(
                            ctx,
                            PotTransitions.delete(p.facts(), c.observedVersion()),
                            r -> revisions.revisePotHeader(c.potId(), r),
                            v -> List.of(component("POT_HEADER", c.potId(), v)));
            case AddShareholders i -> decideAddShareholders(ctx, p, i, refs);
            case UpdateShareholderDetails i -> decideUpdateShareholderDetails(ctx, p, i, refs);
            case UpdateShareholderWeights i -> decideUpdateShareholderWeights(ctx, p, i, refs);
            case DeleteShareholder i -> decideDeleteShareholder(ctx, p, i, refs);
            default -> throw new IllegalStateException("Unexpected intent dispatch");
        };
    }

    private Proposal decideExpense(
            ProposalContext ctx, SelectedPot p, Map<ShareholderId, ShareholderReference> refs) {
        return switch (ctx.command().intent()) {
            case CreateExpense i -> decideCreateExpense(ctx, p, i, refs);
            case UpdateExpenseDetails i -> decideUpdateExpenseDetails(ctx, p, i);
            case UpdateExpenseFinancials i -> decideUpdateExpenseFinancials(ctx, p, i, refs);
            case DeleteExpense i -> decideDeleteExpense(ctx, p, i);
            default -> throw new IllegalStateException("Unexpected expense intent");
        };
    }

    private Proposal decideAddShareholders(
            ProposalContext ctx,
            SelectedPot p,
            AddShareholders i,
            Map<ShareholderId, ShareholderReference> refs) {
        var c = ctx.command();
        var actor = ctx.actor().actor().orElseThrow();
        List<ShareholderTransitions.Creation> targets;
        Map<ShareholderId, ShareholderReference> existence;
        if (i.serverAssigned()) {
            var allocated = new ArrayList<ShareholderTransitions.Creation>(i.creations().size());
            var syntheticRefs = new HashMap<ShareholderId, ShareholderReference>();
            var used = new HashSet<ShareholderId>();
            for (var item : i.creations()) {
                ShareholderId generated;
                do {
                    generated = new ShareholderId(ids.get());
                } while (!used.add(generated));
                allocated.add(
                        new ShareholderTransitions.Creation(
                                generated,
                                item.name(),
                                item.attachment() == Attachment.SELF
                                        ? Optional.of(actor)
                                        : Optional.empty(),
                                item.defaultWeight()));
                syntheticRefs.put(
                        generated,
                        new ShareholderReference(
                                p.facts().potId(),
                                p.facts().workingVersion(),
                                generated,
                                0L,
                                Optional.empty()));
            }
            targets = List.copyOf(allocated);
            existence = Map.copyOf(syntheticRefs);
        } else {
            targets = creationTargets(c, i, actor);
            existence = refs;
        }
        return decision(
                ctx,
                c.potId(),
                ShareholderTransitions.create(p.facts(), c.observedVersion(), targets, existence),
                r -> revisions.createShareholders(c.potId(), r),
                ExecutePotCommand::shareholderCreatedComponents,
                states -> {
                    var created = new ArrayList<CreatedShareholder>(states.size());
                    for (int idx = 0; idx < states.size(); idx++) {
                        created.add(
                                new CreatedShareholder(
                                        idx, states.get(idx).header().value().shareholderId().value()));
                    }
                    return CreatedResources.shareholders(created);
                });
    }

    private static List<Component> shareholderCreatedComponents(List<ShareholderState> states) {
        return states.stream()
                .flatMap(
                        s ->
                                java.util.stream.Stream.of(
                                        component(
                                                "SHAREHOLDER_HEADER",
                                                s.header().value().shareholderId(),
                                                s.header()),
                                        component(
                                                "SHAREHOLDER_DETAILS",
                                                s.details().value().shareholderId(),
                                                s.details()),
                                        component(
                                                "SHAREHOLDER_WEIGHT",
                                                s.weight().value().shareholderId(),
                                                s.weight())))
                .toList();
    }

    private Proposal decideUpdateShareholderDetails(
            ProposalContext ctx,
            SelectedPot p,
            UpdateShareholderDetails i,
            Map<ShareholderId, ShareholderReference> refs) {
        var c = ctx.command();
        return decision(
                ctx,
                ShareholderTransitions.updateDetails(
                        p.facts(),
                        c.observedVersion(),
                        detailTargets(c, i, ctx.actor().actor().orElseThrow(), p),
                        refs,
                        extractShareholderComponents(p, SelectedShareholder::details)),
                r -> revisions.reviseShareholderDetails(c.potId(), r),
                values -> componentList("SHAREHOLDER_DETAILS", values));
    }

    private Proposal decideUpdateShareholderWeights(
            ProposalContext ctx,
            SelectedPot p,
            UpdateShareholderWeights i,
            Map<ShareholderId, ShareholderReference> refs) {
        var c = ctx.command();
        return decision(
                ctx,
                ShareholderTransitions.updateWeights(
                        p.facts(),
                        c.observedVersion(),
                        i.targets(),
                        refs,
                        extractShareholderComponents(p, SelectedShareholder::weight)),
                r -> revisions.reviseShareholderWeights(c.potId(), r),
                values -> componentList("SHAREHOLDER_WEIGHT", values));
    }

    private Proposal decideDeleteShareholder(
            ProposalContext ctx,
            SelectedPot p,
            DeleteShareholder i,
            Map<ShareholderId, ShareholderReference> refs) {
        var c = ctx.command();
        if (refs.get(i.id()).header().isEmpty()) {
            return rejected(c, ctx.actor(), ctx.workingVersion(), "NOT_FOUND");
        }
        return decision(
                ctx,
                ShareholderTransitions.delete(
                        p.facts(),
                        c.observedVersion(),
                        refs.get(i.id()),
                        history.activeUsage(c.potId(), ctx.workingVersion(), i.id())),
                r -> revisions.reviseShareholderHeader(c.potId(), r),
                v -> List.of(component("SHAREHOLDER_HEADER", i.id(), v)));
    }

    private Proposal decideCreateExpense(
            ProposalContext ctx,
            SelectedPot p,
            CreateExpense i,
            Map<ShareholderId, ShareholderReference> refs) {
        var c = ctx.command();
        ExpenseId targetId = i.serverAssigned() ? new ExpenseId(ids.get()) : i.legacyId();
        boolean exists =
                !i.serverAssigned()
                        && p.expenses().get(targetId).header() instanceof ComponentRead.Present<?>;
        return decision(
                ctx,
                c.potId(),
                ExpenseTransitions.create(
                        p.facts(),
                        c.observedVersion(),
                        i.details(c.potId(), targetId),
                        i.financials(c.potId(), targetId),
                        exists,
                        refs),
                r -> revisions.createExpense(c.potId(), r),
                s ->
                        List.of(
                                component("EXPENSE_HEADER", targetId, s.header()),
                                component("EXPENSE_DETAILS", targetId, s.details()),
                                component("EXPENSE_FINANCIALS", targetId, s.financials())),
                s -> CreatedResources.expense(targetId.value()));
    }

    private Proposal decideUpdateExpenseDetails(
            ProposalContext ctx, SelectedPot p, UpdateExpenseDetails i) {
        if (expenseMissing(p, i.id())) {
            return rejected(ctx.command(), ctx.actor(), ctx.workingVersion(), "NOT_FOUND");
        }
        var c = ctx.command();
        var expense = p.expenses().get(i.id());
        return decision(
                ctx,
                ExpenseTransitions.updateDetails(
                        p.facts(),
                        c.observedVersion(),
                        present(expense.header()),
                        present(expense.details()),
                        i.label(),
                        i.date()),
                r -> revisions.reviseExpenseDetails(c.potId(), r),
                v -> List.of(component("EXPENSE_DETAILS", i.id(), v)));
    }

    private Proposal decideUpdateExpenseFinancials(
            ProposalContext ctx,
            SelectedPot p,
            UpdateExpenseFinancials i,
            Map<ShareholderId, ShareholderReference> refs) {
        var expenseId = i.financials().expenseId();
        if (expenseMissing(p, expenseId)) {
            return rejected(ctx.command(), ctx.actor(), ctx.workingVersion(), "NOT_FOUND");
        }
        var c = ctx.command();
        var expense = p.expenses().get(expenseId);
        return decision(
                ctx,
                ExpenseTransitions.updateFinancials(
                        p.facts(),
                        c.observedVersion(),
                        present(expense.header()),
                        present(expense.financials()),
                        i.financials(),
                        refs),
                r -> revisions.reviseExpenseFinancials(c.potId(), r),
                v -> List.of(component("EXPENSE_FINANCIALS", expenseId, v)));
    }

    private Proposal decideDeleteExpense(ProposalContext ctx, SelectedPot p, DeleteExpense i) {
        if (expenseMissing(p, i.id())) {
            return rejected(ctx.command(), ctx.actor(), ctx.workingVersion(), "NOT_FOUND");
        }
        var c = ctx.command();
        return decision(
                ctx,
                ExpenseTransitions.delete(
                        p.facts(), c.observedVersion(), expenseState(p.expenses().get(i.id()))),
                r -> revisions.reviseExpenseHeader(c.potId(), r),
                v -> List.of(component("EXPENSE_HEADER", i.id(), v)));
    }

    private static List<Component> componentList(
            String kind, Map<? extends EntityId, ? extends Versioned<?>> values) {
        return values.entrySet().stream()
                .map(e -> component(kind, e.getKey(), e.getValue()))
                .toList();
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
            ProposalContext ctx,
            TransitionResult<T> result,
            Consumer<TransitionResult.Changed<T>> apply,
            Function<T, List<Component>> components) {
        return decision(ctx, ctx.command().potId(), result, apply, components, unused -> null);
    }

    private <T> Proposal decision(
            ProposalContext ctx,
            PotId targetPotId,
            TransitionResult<T> result,
            Consumer<TransitionResult.Changed<T>> apply,
            Function<T, List<Component>> components,
            Function<T, CreatedResources> createdResources) {
        var c = ctx.command();
        var actor = ctx.actor();
        long version = ctx.workingVersion();
        if (result instanceof TransitionResult.Rejected<T> rejected) {
            return rejected(c, actor, version, rejected.reason().name());
        }
        if (result instanceof TransitionResult.Unchanged<T> unchanged) {
            return new Proposal(
                    c,
                    actor,
                    version,
                    new Result(
                            Outcome.UNCHANGED, unchanged.currentPotVersion(), null, targetPotId, null),
                    () -> {},
                    List.of());
        }
        var changed = (TransitionResult.Changed<T>) result;
        var created = createdResources.apply(changed.changes());
        return new Proposal(
                c,
                actor,
                version,
                new Result(
                        Outcome.CHANGED, changed.candidatePotVersion(), null, targetPotId, created),
                () -> {
                    if (!(c.intent() instanceof CreatePot))
                        revisions.recordVersion(
                                targetPotId,
                                changed.expectedPotVersion(),
                                changed.candidatePotVersion(),
                                clock.instant());
                    apply.accept(changed);
                },
                List.copyOf(components.apply(changed.changes())));
    }

    private static Proposal rejected(
            PotCommand c, PotActorAuthority.Witness actor, long version, String reason) {
        return new Proposal(
                c, actor, version, Result.rejected(reason, c.potId()), () -> {}, List.of());
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

    private static <T> Map<ShareholderId, Versioned<T>> extractShareholderComponents(
            SelectedPot p, Function<SelectedShareholder, ComponentRead<T>> selector) {
        var result = new HashMap<ShareholderId, Versioned<T>>();
        p.shareholders()
                .forEach(
                        (id, s) -> {
                            if (selector.apply(s) instanceof ComponentRead.Present<T> v) {
                                result.put(id, v.revision());
                            }
                        });
        return Map.copyOf(result);
    }

    private static Selection selection(Intent intent) {
        return new Selection(
                selectedShareholders(intent),
                selectedExpenses(intent),
                intent instanceof UpdatePotDetails || intent instanceof CreatePot,
                intent instanceof UpdateShareholderDetails
                        || intent instanceof PotCommand.AttachShareholderViaInvitation,
                intent instanceof UpdateShareholderWeights,
                intent instanceof UpdateExpenseDetails || intent instanceof DeleteExpense,
                intent instanceof UpdateExpenseFinancials || intent instanceof DeleteExpense);
    }

    private static Set<ShareholderId> selectedShareholders(Intent intent) {
        return switch (intent) {
            case PotCommand.AttachShareholderViaInvitation i -> Set.of(i.shareholderId());
            case AddShareholders i ->
                    i.serverAssigned()
                            ? Set.of()
                            : i.creations().stream()
                                    .map(PotCommand.ShareholderCreation::legacyId)
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
            case CreateExpense i -> referenceIds(i.payerId(), i.participants().keySet());
            case UpdateExpenseFinancials i ->
                    referenceIds(i.financials().payerId(), i.financials().participants().keySet());
            default -> Set.of();
        };
    }

    private static Set<ExpenseId> selectedExpenses(Intent intent) {
        return switch (intent) {
            case CreateExpense i -> i.serverAssigned() ? Set.of() : Set.of(i.legacyId());
            case UpdateExpenseDetails i -> Set.of(i.id());
            case UpdateExpenseFinancials i -> Set.of(i.financials().expenseId());
            case DeleteExpense i -> Set.of(i.id());
            default -> Set.of();
        };
    }

    private InvitationStore requireInvitations() {
        if (invitations == null)
            throw new IllegalStateException("Invitation support not configured");
        return invitations;
    }

    private static Set<ShareholderId> referenceIds(
            ShareholderId payerId, Set<ShareholderId> participants) {
        var ids = new HashSet<>(participants);
        ids.add(payerId);
        return Set.copyOf(ids);
    }
}
