package com.kartaguez.pocoma.orchestrator.consumption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol.Attempt;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol.Status;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.Outcome;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.Phase;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.contract.consumption.spi.ClaimedSlot;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore.Cession;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore.ClaimCandidate;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore.ClaimInspection;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore.GrantedClaim;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore.Inspection;
import com.kartaguez.pocoma.contract.consumption.spi.ConsumptionSlotStore.SlotObservation;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoveryAdmissionStore;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.contract.consumption.spi.NonBurnableReferences;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionOwnershipStore;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.contract.consumption.spi.RecoverySourcePolicy;
import com.kartaguez.pocoma.contract.consumption.spi.SlotKeyNavigation;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions.Failure;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions.Resolution;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

/** Bounded lifecycle orchestration. One acquisition, one attempt; no polling or internal retry. */
public final class ConsumptionEngine {
    ConsumptionDefinition definition() { return definition; }




    /** The caller may advance only over this committed prefix of its supplied keys. */
    public record Acquisition(List<AcquiredClaim> claims, int examinedCount) {
        public Acquisition {
            claims = List.copyOf(claims);
        }
    }

    private static final class ClaimLost extends RuntimeException {}

    private final ConsumptionDefinition definition;
    private final AttemptTransactions transactions;
    private final ConsumptionSlotStore slots;
    private final ConsumptionParticipant participant;
    private final AttemptTransactions sourceTransactions;
    private final RecoverySourcePolicy recoveryPolicy;
    private final AttemptTransactions controlTransactions;
    private final ConsumptionSlotStore controlSlots;
    private final SlotKeyNavigation navigation;
    private final ConsumptionObservation observation;

    private final PartitionOwnershipStore ownership;
    private final LifecycleSettings settings;
    final WorkerCapacity capacity;
    private final AttemptFailureClassifier classifier;
    // At most M_W entries across the Engines: each entry owns one reserved ticket.
    private final Set<AcquiredClaim> pending = Collections.synchronizedSet(new HashSet<>());
    // Inspection/observation ports expose no witness for a locally returned UNKNOWN unit.
    // This is a local exclusion, never a durable result or a claim/ticket authority.
    private volatile boolean unresolvedRecovery;

    /** Local capacity handle. No mirrored durable state or cached deadline. */
    public static final class AcquiredClaim {
        private final ConsumptionEngine owner;
        private final ClaimedSlot claim;
        private final WorkerCapacity.Ticket ticket;
        private final AtomicBoolean used = new AtomicBoolean();
        private volatile boolean localDone;
        private boolean businessStarted;
        // A preparation or recovery read can outlive local transport failure without a slot row
        // lock.
        // The current ports expose no physical resolution witness for that unit. Never settle
        // its reservation from slot availability; retain it until this Worker is terminated.
        private volatile boolean unresolvedPreparation;

        AcquiredClaim(ConsumptionEngine owner, ClaimedSlot claim, WorkerCapacity.Ticket ticket) {
            this.owner = Objects.requireNonNull(owner);
            this.claim = Objects.requireNonNull(claim);
            this.ticket = Objects.requireNonNull(ticket);
            if (!ticket.belongsTo(owner.capacity, claim.consumptionId())
                    || !owner.definition.id().equals(claim.consumptionId()))
                throw new IllegalArgumentException("foreign reservation");
        }

        public ClaimedSlot claim() {
            return claim;
        }
    }

    public ConsumptionEngine(
            ConsumptionDefinition definition,
            AttemptTransactions transactions,
            ConsumptionSlotStore slots,
            ConsumptionParticipant participant,
            PartitionOwnershipStore ownership,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            AttemptFailureClassifier classifier) {
        this(
                definition,
                transactions,
                slots,
                participant,
                transactions,
                slots,
                ownership,
                settings,
                capacity,
                classifier,
                null,
                ConsumptionObservation.noop());
    }

    public ConsumptionEngine(
            ConsumptionDefinition definition,
            AttemptTransactions effectsTransactions,
            ConsumptionSlotStore effectsSlots,
            ConsumptionParticipant participant,
            AttemptTransactions controlTransactions,
            ConsumptionSlotStore controlSlots,
            PartitionOwnershipStore ownership,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            AttemptFailureClassifier classifier,
            SlotKeyNavigation navigation,
            ConsumptionObservation observation) {
        this(
                definition,
                effectsTransactions,
                effectsSlots,
                participant,
                effectsTransactions,
                Optional.empty(),
                r -> true,
                null,
                controlTransactions,
                controlSlots,
                ownership,
                settings,
                capacity,
                classifier,
                navigation,
                observation);
    }

    public ConsumptionEngine(
            ConsumptionDefinition definition,
            AttemptTransactions effectsTransactions,
            ConsumptionSlotStore effectsSlots,
            ConsumptionParticipant participant,
            AttemptTransactions sourceTransactions,
            Optional<NonBurnableReferences> recoverySource,
            Predicate<NonBurnableReferences.Reference> relevant,
            DiscoveryAdmissionStore admissions,
            AttemptTransactions controlTransactions,
            ConsumptionSlotStore controlSlots,
            PartitionOwnershipStore ownership,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            AttemptFailureClassifier classifier,
            SlotKeyNavigation navigation,
            ConsumptionObservation observation) {
        this(
                definition,
                effectsTransactions,
                effectsSlots,
                participant,
                sourceTransactions,
                RecoverySourcePolicy.forAuthority(
                        Objects.requireNonNull(definition),
                        Objects.requireNonNull(recoverySource),
                        Objects.requireNonNull(relevant),
                        admissions),
                controlTransactions,
                controlSlots,
                ownership,
                settings,
                capacity,
                classifier,
                navigation,
                observation);
    }

    public ConsumptionEngine(
            ConsumptionDefinition definition,
            AttemptTransactions effectsTransactions,
            ConsumptionSlotStore effectsSlots,
            ConsumptionParticipant participant,
            AttemptTransactions sourceTransactions,
            RecoverySourcePolicy recoveryPolicy,
            AttemptTransactions controlTransactions,
            ConsumptionSlotStore controlSlots,
            PartitionOwnershipStore ownership,
            LifecycleSettings settings,
            WorkerCapacity capacity,
            AttemptFailureClassifier classifier,
            SlotKeyNavigation navigation,
            ConsumptionObservation observation) {
        this.definition = Objects.requireNonNull(definition);
        this.transactions = Objects.requireNonNull(effectsTransactions);
        this.sourceTransactions = Objects.requireNonNull(sourceTransactions);
        this.recoveryPolicy = Objects.requireNonNull(recoveryPolicy);
        this.recoveryPolicy.validate(definition, effectsTransactions, sourceTransactions);
        this.slots = Objects.requireNonNull(effectsSlots);
        this.controlTransactions = Objects.requireNonNull(controlTransactions);
        this.controlSlots = Objects.requireNonNull(controlSlots);
        this.navigation = navigation;
        this.observation = Objects.requireNonNull(observation);
        this.participant = Objects.requireNonNull(participant);
        this.ownership = Objects.requireNonNull(ownership);
        this.settings = Objects.requireNonNull(settings);
        this.capacity = Objects.requireNonNull(capacity);
        this.classifier = Objects.requireNonNull(classifier);
        if (!capacity.supports(definition.id()))
            throw new IllegalArgumentException("unconfigured capacity");
    }

    private <T> T technical(Supplier<T> operation) {
        return controlTransactions.write(
                () -> {
                    ownership.bound(settings.lockTimeout(), settings.statementTimeout());
                    return operation.get();
                });
    }

    /** Read-only local information; never an execution or release permission. */
    public boolean retainsCapacity(AcquiredClaim handle) {
        if (handle == null || handle.owner != this)
            throw new IllegalArgumentException("foreign claim handle");
        return handle.ticket.retained();
    }

    /** Monotone local uncertainty only; never a permission to execute or release. */
    public boolean hasUnresolvedRecovery() {
        return unresolvedRecovery;
    }

    private void recoveryFailed(Throwable fault, ClaimedSlot claim) {
        if (fault instanceof Failure f && f.resolution() != Resolution.UNKNOWN) return;
        unresolvedRecovery = true;
        if (claim != null) {
            synchronized (pending) {
                pending.stream()
                        .filter(h -> h.claim.equals(claim))
                        .forEach(h -> h.unresolvedPreparation = true);
            }
        }
    }

    public ConsumptionNavigation.Page navigate(
            PartitionLease lease, ConsumptionNavigation.Walk walk, int limit) {
        checkLease(lease);
        Objects.requireNonNull(walk);
        if (navigation == null) throw new IllegalStateException("slot navigation not configured");
        if (limit < 1 || limit > settings.batchSize())
            throw new IllegalArgumentException("invalid page bound");
        return measured(
                Phase.NAVIGATION,
                () ->
                        controlTransactions.read(
                                context ->
                                        navigation.readPage(
                                                definition.id(),
                                                lease.partitionId(),
                                                walk,
                                                limit,
                                                context)));
    }

    private static final Set<Phase> ZERO_AMOUNT_FAILURE_PHASES =
            Set.of(
                    Phase.CESSION,
                    Phase.ACQUIRE,
                    Phase.CLAIM_RENEW,
                    Phase.LEASE_ACQUIRE,
                    Phase.LEASE_RENEW);

    private <T> T measured(Phase phase, Supplier<T> operation) {
        long begin = System.nanoTime();
        Outcome outcome = Outcome.CONFIRMED;
        long amount = 1;
        try {
            T result = operation.get();
            outcome = resolveSuccessOutcome(phase, result);
            amount = resolveSuccessAmount(phase, result);
            return result;
        } catch (Failure failure) {
            if (ZERO_AMOUNT_FAILURE_PHASES.contains(phase)) amount = 0;
            outcome = resolutionToOutcome(failure.resolution());
            throw failure;
        } catch (RuntimeException | Error failure) {
            outcome = Outcome.UNKNOWN;
            throw failure;
        } finally {
            ConsumptionObservation.safely(
                    observation,
                    definition.id(),
                    phase,
                    outcome,
                    amount,
                    Duration.ofNanos(System.nanoTime() - begin));
        }
    }

    private Outcome resolveSuccessOutcome(Phase phase, Object result) {
        if (unresolvedRecovery && isObservationPhase(phase)) {
            return Outcome.UNKNOWN;
        }
        if (result instanceof Attempt attempt) {
            return resolveAttemptOutcome(phase, attempt);
        }
        if (result instanceof Optional<?> optional && optional.isEmpty()) {
            return Outcome.EMPTY;
        }
        return Outcome.CONFIRMED;
    }

    private static boolean isObservationPhase(Phase phase) {
        return phase == Phase.SETTLE || phase == Phase.OBSERVE;
    }

    private static Outcome resolveAttemptOutcome(Phase phase, Attempt attempt) {
        if (phase == Phase.ATTEMPT) {
            var statusOutcome = attemptStatusOutcome(attempt.status());
            if (statusOutcome != null) return statusOutcome;
        }
        return resolutionToOutcome(attempt.resolution());
    }

    private static Outcome attemptStatusOutcome(Status status) {
        return switch (status) {
            case INCONSISTENT -> Outcome.INCONSISTENT;
            case RECOVERY_PENDING -> Outcome.RECOVERY_PENDING;
            case CLAIM_LOST -> Outcome.CLAIM_LOST;
            default -> null;
        };
    }

    private static Outcome resolutionToOutcome(Resolution resolution) {
        return switch (resolution) {
            case COMMITTED -> Outcome.COMMITTED;
            case ROLLED_BACK -> Outcome.ROLLED_BACK;
            case UNKNOWN -> Outcome.UNKNOWN;
        };
    }

    private long resolveSuccessAmount(Phase phase, Object result) {
        if (result instanceof Acquisition acquired) return recordExaminedAcquisition(acquired);
        if (result instanceof ConsumptionNavigation.Page page) return page.keys().size();
        if (phase == Phase.SETTLE && result instanceof Integer count) return count;
        return collectionOrOptionalAmount(result);
    }

    private static long collectionOrOptionalAmount(Object result) {
        if (result instanceof List<?> list) return list.size();
        if (result instanceof Optional<?> optional) return optional.isEmpty() ? 0 : 1;
        return 1;
    }

    private long recordExaminedAcquisition(Acquisition acquired) {
        ConsumptionObservation.safely(
                observation,
                definition.id(),
                Phase.ACQUISITION_EXAMINED,
                Outcome.CONFIRMED,
                acquired.examinedCount(),
                Duration.ZERO);
        return acquired.claims().size();
    }

    public Optional<PartitionLease> acquirePartition(int partition) {
        return measured(Phase.LEASE_ACQUIRE, () -> acquirePartitionOperation(partition));
    }

    private Optional<PartitionLease> acquirePartitionOperation(int partition) {
        checkPartition(partition);
        // If the ACK is UNKNOWN, no lease is returned or used. A later acquisition gets a new
        // token.
        return technical(
                () ->
                        ownership.acquire(
                                definition,
                                partition,
                                UUID.randomUUID(),
                                settings.leaseDuration()));
    }

    public List<PartitionLease> renewPartitions(List<PartitionLease> leases) {
        return measured(Phase.LEASE_RENEW, () -> renewPartitionsOperation(leases));
    }

    private List<PartitionLease> renewPartitionsOperation(List<PartitionLease> leases) {
        bounded(leases);
        leases.forEach(this::checkLease);
        return technical(() -> ownership.renew(definition, leases, settings.leaseDuration()));
    }

    public List<PartitionLease> renewPartitionBatch(
            List<ConsumptionDefinition> definitions, List<PartitionLease> leases) {
        return measured(
                Phase.LEASE_RENEW,
                () -> {
                    bounded(leases);
                    return technical(
                            () ->
                                    definitions.size() == 1
                                            ? ownership.renew(
                                                    definitions.getFirst(),
                                                    leases,
                                                    settings.leaseDuration())
                                            : ownership.renewBatch(
                                                    definitions, leases, settings.leaseDuration()));
                });
    }

    public List<GrantedClaim> renewClaims(List<ClaimedSlot> claims) {
        return measured(Phase.CLAIM_RENEW, () -> renewClaimsOperation(claims));
    }

    private List<GrantedClaim> renewClaimsOperation(List<ClaimedSlot> claims) {
        bounded(claims);
        claims.forEach(
                c -> {
                    if (!c.consumptionId().equals(definition.id()))
                        throw new IllegalArgumentException("foreign claim");
                });
        return technical(() -> controlSlots.renew(claims, settings.claimDuration()));
    }

    public Acquisition acquire(PartitionLease lease, List<UUID> examinedKeys) {
        return measured(Phase.ACQUIRE, () -> acquireOperation(lease, examinedKeys));
    }

    private Acquisition acquireOperation(PartitionLease lease, List<UUID> examinedKeys) {
        checkLease(lease);
        bounded(examinedKeys);
        var tickets = capacity.reserve(definition.id(), examinedKeys.size());
        if (tickets.isEmpty()) return new Acquisition(List.of(), 0);
        var candidates = new ArrayList<ClaimCandidate>();
        var handles = new ArrayList<AcquiredClaim>();
        for (int i = 0; i < tickets.size(); i++) {
            var claim = new ClaimedSlot(definition.id(), examinedKeys.get(i), UUID.randomUUID());
            candidates.add(new ClaimCandidate(claim.consumableId(), claim.claimToken()));
            handles.add(new AcquiredClaim(this, claim, tickets.get(i)));
        }
        handles.forEach(this::addPending);
        try {
            var granted = grantClaims(lease, candidates);
            var won = collectGrantedClaims(granted);
            var result = settleAcquiredHandles(handles, won);
            return new Acquisition(result, granted.isPresent() ? candidates.size() : 0);
        } catch (Failure failure) {
            if (failure.resolution() == Resolution.ROLLED_BACK) {
                releaseHandles(handles);
            }
            // COMMITTED with lost callback result is also inspected, never guessed or executed.
            throw failure;
        } finally {
            handles.forEach(h -> h.localDone = true);
        }
    }

    private Optional<List<GrantedClaim>> grantClaims(
            PartitionLease lease, List<ClaimCandidate> candidates) {
        return technical(
                () ->
                        ownership
                                .protectAcquisition(definition, lease)
                                .map(
                                        protectedLease ->
                                                controlSlots.acquire(
                                                        protectedLease,
                                                        candidates,
                                                        settings.claimDuration())));
    }

    private static Set<ClaimedSlot> collectGrantedClaims(Optional<List<GrantedClaim>> granted) {
        var won = new HashSet<ClaimedSlot>();
        granted.orElseGet(List::of).forEach(g -> won.add(g.claim()));
        return won;
    }

    private List<AcquiredClaim> settleAcquiredHandles(
            List<AcquiredClaim> handles, Set<ClaimedSlot> won) {
        var result = new ArrayList<AcquiredClaim>();
        for (var handle : handles) {
            removePending(handle);
            if (won.contains(handle.claim)) result.add(handle);
            else handle.ticket.release();
        }
        return result;
    }

    private void releaseHandles(List<AcquiredClaim> handles) {
        for (var handle : handles) {
            removePending(handle);
            handle.ticket.release();
        }
    }

    public void abandon(AcquiredClaim acquired) {
        start(acquired);
        acquired.localDone = true; // Acquisition was confirmed; no local business callback started.
        acquired.ticket.release(); // The durable claim is left to expire, with no budget mutation.
    }

    private void start(AcquiredClaim acquired) {
        if (acquired == null || acquired.owner != this) {
            throw new IllegalArgumentException("foreign or already used claim handle");
        }
        if (!acquired.used.compareAndSet(false, true)) {
            throw new IllegalArgumentException("foreign or already used claim handle");
        }
    }

    public Attempt execute(AcquiredClaim acquired) {
        return measured(Phase.ATTEMPT, () -> executeOperation(acquired));
    }

    private Attempt executeOperation(AcquiredClaim acquired) {
        start(acquired);
        acquired.localDone = false;
        acquired.businessStarted = true;
        addPending(acquired);
        boolean resolved = false;
        try {
            var attempt = executeAttempt(acquired);
            if (attempt.status() == Status.CLAIM_LOST) {
                observeEvent(Phase.CLAIM_LOST, Outcome.CONFIRMED);
            }
            if (attempt.status() != Status.ROLLED_BACK
                    || attempt.resolution() != Resolution.ROLLED_BACK) {
                resolved = attempt.resolution() != Resolution.UNKNOWN;
                return nonCountableAttempt(attempt, FailureKind.NON_COUNTABLE);
            }
            FailureKind kind;
            try {
                kind = Objects.requireNonNull(classifier.classify(attempt.cause()));
            } catch (RuntimeException | Error failure) {
                resolved = true;
                throw failure;
            }
            if (kind == FailureKind.NON_COUNTABLE) {
                resolved = true;
                return nonCountableAttempt(attempt, kind);
            }
            var result = cedeCountableFailure(acquired, attempt, kind);
            resolved = result.resolved();
            return result.attempt();
        } finally {
            // A cancellation request is not this event. Only the synchronous callback's exit is.
            acquired.localDone = true;
            // Classification can fail after a confirmed rollback. Release only when the last
            // physical operation is known resolved; raw port failures and UNKNOWN stay pending.
            if (resolved && !acquired.unresolvedPreparation) {
                removePending(acquired);
                acquired.ticket.release();
            }
        }
    }

    private record ExecutionOutcome(Attempt attempt, boolean resolved) {}

    private static Attempt nonCountableAttempt(Attempt attempt, FailureKind kind) {
        return new Attempt(
                attempt.status(),
                attempt.resolution(),
                attempt.observation(),
                attempt.cause(),
                kind,
                null,
                null);
    }

    private ExecutionOutcome cedeCountableFailure(
            AcquiredClaim acquired, Attempt attempt, FailureKind kind) {
        // The business rollback says nothing about this new physical operation.
        Cession cession = null;
        Resolution cessionResolution;
        boolean resolved;
        try {
            cession =
                    measured(
                                    Phase.CESSION,
                                    () ->
                                            technical(
                                                    () ->
                                                            controlSlots.cede(
                                                                    acquired.claim,
                                                                    settings.retry())))
                            .orElse(null);
            cessionResolution = Resolution.COMMITTED;
            resolved = true;
            if (cession != null && "QUARANTINED".equals(cession.state())) {
                observeEvent(Phase.QUARANTINE, Outcome.COMMITTED);
            }
        } catch (Failure failure) {
            cessionResolution = failure.resolution();
            resolved = cessionResolution != Resolution.UNKNOWN;
            if (attempt.cause() != null) attempt.cause().addSuppressed(failure);
            // Never repeat an uncertain cession, even if a later read looks unchanged.
        }
        return new ExecutionOutcome(
                new Attempt(
                        attempt.status(),
                        attempt.resolution(),
                        attempt.observation(),
                        attempt.cause(),
                        kind,
                        cession,
                        cessionResolution),
                resolved);
    }

    private void observeEvent(Phase phase, Outcome outcome) {
        ConsumptionObservation.safely(
                observation, definition.id(), phase, outcome, 1, Duration.ZERO);
    }

    /** Bounded technical inspection only; never cede, re-execute or repair business data. */
    public int settlePending() {
        return measured(Phase.SETTLE, () -> settlePendingOperation());
    }

    private int settlePendingOperation() {
        if (unresolvedRecovery) return 0;
        var snapshot = snapshotSettleableHandles();
        if (snapshot.isEmpty()) return 0;
        var inspections = inspectSettleableClaims(snapshot);
        if (inspections == null) return 0;
        int released = 0;
        for (var handle : snapshot) {
            if (unresolvedRecovery) break;
            if (tryReleaseHandle(handle, inspections)) released++;
        }
        return released;
    }

    private boolean tryReleaseHandle(AcquiredClaim handle, List<ClaimInspection> inspections) {
        if (!canReleaseHandle(handle, inspections) || !handle.localDone) return false;
        return removePending(handle) && handle.ticket.release();
    }

    private List<AcquiredClaim> snapshotSettleableHandles() {
        synchronized (pending) {
            return pending.stream()
                    .filter(h -> h.localDone && !h.unresolvedPreparation)
                    .limit(settings.batchSize())
                    .toList();
        }
    }

    private List<ClaimInspection> inspectSettleableClaims(List<AcquiredClaim> snapshot) {
        try {
            return technical(
                    () ->
                            controlSlots.inspectResolved(
                                    snapshot.stream().map(h -> h.claim).toList()));
        } catch (RuntimeException | Error unavailable) {
            recoveryFailed(unavailable, null);
            return null;
        }
    }

    private boolean canReleaseHandle(AcquiredClaim handle, List<ClaimInspection> inspections) {
        var inspection =
                inspections.stream()
                        .filter(i -> i.claim().equals(handle.claim))
                        .map(ClaimInspection::inspection)
                        .findFirst()
                        .orElse(Inspection.LOCKED);
        if (inspection == Inspection.PRESENT_UNLOCKED) return true;
        if (inspection != Inspection.ABSENT) return false;
        return !handle.businessStarted || observe(handle.claim) == RecoveryObservation.COMPLETED;
    }

    private void addPending(AcquiredClaim handle) {
        if (pending.add(handle))
            ConsumptionObservation.safely(
                    observation,
                    definition.id(),
                    Phase.PENDING,
                    Outcome.CONFIRMED,
                    1,
                    Duration.ZERO);
    }

    private boolean removePending(AcquiredClaim handle) {
        boolean removed = pending.remove(handle);
        if (removed)
            ConsumptionObservation.safely(
                    observation,
                    definition.id(),
                    Phase.PENDING,
                    Outcome.CONFIRMED,
                    -1,
                    Duration.ZERO);
        return removed;
    }

    private void checkPartition(int partition) {
        if (partition < 0 || partition >= definition.partitionCount())
            throw new IllegalArgumentException("invalid partition");
    }

    private void checkLease(PartitionLease lease) {
        if (!definition.id().equals(lease.consumptionId()))
            throw new IllegalArgumentException("foreign lease");
        checkPartition(lease.partitionId());
    }

    private void bounded(List<?> keys) {
        Objects.requireNonNull(keys);
        if (keys.size() > settings.batchSize() || keys.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("invalid bounded keys");
        }
        if (new HashSet<>(keys).size() != keys.size()) {
            throw new IllegalArgumentException("invalid bounded keys");
        }
    }

    private Attempt executeAttempt(AcquiredClaim acquired) {
        var claim = acquired.claim;
        Objects.requireNonNull(claim);
        if (!definition.id().equals(claim.consumptionId())) {
            throw new IllegalArgumentException("Claim belongs to another Consumption");
        }
        var reads = new Reads();
        Optional<ConsumptionParticipant.Prepared> prepared;
        try {
            prepared =
                    measured(Phase.PREPARE, () -> participant.prepare(claim.consumableId(), reads));
            reads.requireValid();
            Objects.requireNonNull(prepared);
        } catch (RuntimeException | Error failure) {
            return handlePrepareFailure(claim, reads, failure);
        } finally {
            reads.expire();
            if (reads.physicallyUnresolved()) acquired.unresolvedPreparation = true;
        }
        if (prepared.isEmpty()) return observed(claim, Resolution.UNKNOWN, null);
        return finalizeAttempt(claim, prepared.get(), reads);
    }

    private Attempt handlePrepareFailure(
            ClaimedSlot claim, Reads reads, Throwable failure) {
        reads.invalidate(failure);
        if (reads.physicallyUnresolved() && reads.failedResolution == null) {
            if (failure instanceof Error error) throw error;
            throw (RuntimeException) failure;
        }
        if (reads.physicallyUnresolved()) {
            return new Attempt(Status.RECOVERY_PENDING, Resolution.UNKNOWN, null, reads.cause());
        }
        if (reads.failedResolution == Resolution.COMMITTED) {
            return observed(claim, Resolution.UNKNOWN, reads.cause());
        }
        // No business transaction began; resolved reads keep their own physical outcome.
        return new Attempt(Status.ROLLED_BACK, Resolution.ROLLED_BACK, null, reads.cause());
    }

    private Attempt finalizeAttempt(
            ClaimedSlot claim,
            ConsumptionParticipant.Prepared prepared,
            AttemptTransactions.Guard guard) {
        return measured(Phase.FINALIZE, () -> finalizeAttemptOperation(claim, prepared, guard));
    }

    private Attempt finalizeAttemptOperation(
            ClaimedSlot claim,
            ConsumptionParticipant.Prepared prepared,
            AttemptTransactions.Guard guard) {
        try {
            guard.requireValid();
        } catch (RuntimeException | Error failure) {
            return new Attempt(Status.ROLLED_BACK, Resolution.ROLLED_BACK, null, failure);
        }
        try {
            transactions.write(
                    () -> {
                        if (slots.protectFinal(claim) != ConsumptionSlotStore.Protection.VALID)
                            throw new ClaimLost();
                        var completion = new Completion(claim, prepared);
                        try {
                            prepared.finalizeIn(completion);
                            completion.requireCompleted();
                            transactions.requirePublicationClosed();
                            return null;
                        } finally {
                            completion.active = false;
                        }
                    },
                    guard);
            return new Attempt(Status.COMMITTED, Resolution.COMMITTED, null, null);
        } catch (Failure failure) {
            if (failure.resolution() == Resolution.COMMITTED)
                return new Attempt(
                        Status.COMMITTED, Resolution.COMMITTED, null, failure.getCause());
            if (failure.resolution() == Resolution.UNKNOWN)
                return observed(claim, Resolution.UNKNOWN, failure.getCause());
            return new Attempt(
                    failure.getCause() instanceof ClaimLost
                            ? Status.CLAIM_LOST
                            : Status.ROLLED_BACK,
                    Resolution.ROLLED_BACK,
                    null,
                    failure.getCause());
        }
    }

    /** A fresh, physically read-only coherent snapshot; never repair or infer rollback. */
    public RecoveryObservation observe(ClaimedSlot claim) {
        return measured(Phase.OBSERVE, () -> observeOperation(claim));
    }

    private RecoveryObservation observeOperation(ClaimedSlot claim) {
        if (!definition.id().equals(claim.consumptionId())) {
            throw new IllegalArgumentException("Claim belongs to another Consumption");
        }
        if (unresolvedRecovery) return RecoveryObservation.UNAVAILABLE;
        var reads = new Reads();
        var phaseState = new ObservePhaseState();
        try {
            var sourceCheck = recoveryPolicy.checkSource(definition, claim.consumableId(), reads);
            reads.requireValid();
            if (!sourceCheck.consistent()) return RecoveryObservation.INCONSISTENT;
            phaseState.readingSource = false;
            var recovery =
                    Objects.requireNonNull(
                            participant.prepareRecovery(claim.consumableId(), reads));
            reads.expire();
            reads.requireValid();
            phaseState.finalReadStarted = true;
            return transactions.read(
                    context ->
                            evaluateRecoverySnapshot(
                                    claim, sourceCheck.reference(), recovery, context),
                    reads);
        } catch (RuntimeException | Error failure) {
            return handleObserveFailure(claim, reads, phaseState, failure);
        } finally {
            reads.expire();
        }
    }

    private static final class ObservePhaseState {
        boolean readingSource = true;
        boolean finalReadStarted;
    }

    private RecoveryObservation evaluateRecoverySnapshot(
            ClaimedSlot claim,
            NonBurnableReferences.Reference source,
            ConsumptionParticipant.Recovery recovery,
            RecoveryReadContext context) {
        var slot = slots.observe(claim.consumptionId(), claim.consumableId(), context);
        var business = recovery.observe(context);
        if (business == ConsumptionParticipant.EffectEvidence.UNAVAILABLE) {
            return RecoveryObservation.UNAVAILABLE;
        }
        if (!recoveryPolicy.checkpointCovers(definition, claim.consumableId(), source, context)) {
            return RecoveryObservation.INCONSISTENT;
        }
        return mapRecoveryEvidence(slot, business);
    }

    private RecoveryObservation mapRecoveryEvidence(
            SlotObservation slot, ConsumptionParticipant.EffectEvidence business) {
        return switch (business) {
            case SATISFIED ->
                    slot == SlotObservation.ABSENT
                            ? RecoveryObservation.COMPLETED
                            : recoveryPolicy.satisfiedWithPresentSlot();
            case MISSING ->
                    slot == SlotObservation.PRESENT
                            ? RecoveryObservation.NOT_COMPLETED
                            : RecoveryObservation.INCONSISTENT;
            case DIVERGENT -> RecoveryObservation.INCONSISTENT;
            case UNAVAILABLE -> RecoveryObservation.UNAVAILABLE;
        };
    }

    private RecoveryObservation handleObserveFailure(
            ClaimedSlot claim, Reads reads, ObservePhaseState state, Throwable failure) {
        if (reads.physicallyUnresolved()) {
            recoveryFailed(failure, claim);
            return RecoveryObservation.UNAVAILABLE;
        }
        if (state.readingSource
                && reads.cause() instanceof NonBurnableReferences.InvalidReference) {
            return RecoveryObservation.INCONSISTENT;
        }
        if (reads.failedResolution != null) {
            recoveryFailed(new Failure(reads.failedResolution, failure), claim);
        } else if (!state.finalReadStarted) {
            recoveryFailed(new Failure(Resolution.ROLLED_BACK, failure), claim);
        } else {
            recoveryFailed(failure, claim);
        }
        return RecoveryObservation.UNAVAILABLE;
    }

    /** Pure, monotone invocation guard. Spring remains the sole physical transaction owner. */
    private final class Reads implements PreparationReads, AttemptTransactions.Guard {
        private final Thread thread = Thread.currentThread();
        private final AtomicReference<Throwable> first = new AtomicReference<>();
        private boolean open = true;
        private boolean active;
        private volatile boolean uncertain;
        private volatile Resolution failedResolution;
        private int entered;

        @Override
        public <T> T source(Function<RecoveryReadContext, T> action) {
            return scope(sourceTransactions, action);
        }

        @Override
        public <T> T effects(Function<RecoveryReadContext, T> action) {
            return scope(transactions, action);
        }

        @Override
        public void invalidate(Throwable cause) {
            first.compareAndSet(null, Objects.requireNonNull(cause));
        }

        private Throwable cause() {
            return first.get();
        }

        @Override
        public void requireValid() {
            if (Thread.currentThread().isInterrupted())
                invalidate(new PreparationReads.Invalid("Preparation interrupted", null));
            var failure = first.get();
            if (failure instanceof Error error) throw error;
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure != null) throw new IllegalStateException("Preparation failed", failure);
        }

        private <T> T scope(AttemptTransactions owner, Function<RecoveryReadContext, T> action) {
            enterScope(action);
            try {
                T result = owner.read(action, this);
                // Physical success preceded this barrier; a late poison cannot undo that success.
                requireValid();
                return result;
            } catch (Failure failure) {
                failedResolution = failure.resolution();
                if (failure.resolution() == Resolution.UNKNOWN) uncertain = true;
                invalidate(failure.getCause());
                throw failure;
            } catch (RuntimeException | Error failure) {
                uncertain = true;
                invalidate(failure);
                throw failure;
            } finally {
                synchronized (this) {
                    active = false;
                }
            }
        }

        private synchronized void enterScope(Function<RecoveryReadContext, ?> action) {
            if (!isScopeCallable()) {
                var fault =
                        new PreparationReads.Invalid(
                                "Preparation scope expired, nested or foreign", null);
                invalidate(fault);
                throw fault;
            }
            try {
                requireValid();
                Objects.requireNonNull(action);
            } catch (RuntimeException | Error failure) {
                invalidate(failure);
                throw failure;
            }
            active = true;
            entered++;
        }

        private boolean isScopeCallable() {
            if (Thread.currentThread() != thread || !open) return false;
            return !active;
        }

        private synchronized void expire() {
            open = false;
        }

        private boolean physicallyUnresolved() {
            return uncertain;
        }
    }

    private Attempt observed(ClaimedSlot claim, Resolution resolution, Throwable cause) {
        var observation = observe(claim);
        var status =
                switch (observation) {
                    case COMPLETED -> Status.COMMITTED;
                    case INCONSISTENT -> Status.INCONSISTENT;
                    case NOT_COMPLETED, UNAVAILABLE -> Status.RECOVERY_PENDING;
                };
        // COMPLETED resolves business outcome, not the historical physical ACK.
        return new Attempt(status, resolution, observation, cause);
    }

    private final class Completion implements SlotCompletion {
        private final ClaimedSlot claim;
        private final ConsumptionParticipant.Prepared prepared;
        private final Thread thread = Thread.currentThread();
        private volatile boolean active = true;
        private boolean called;
        private volatile Throwable failure;

        Completion(ClaimedSlot claim, ConsumptionParticipant.Prepared prepared) {
            this.claim = claim;
            this.prepared = prepared;
        }

        @Override
        public synchronized void complete() {
            try {
                requireActiveSingleUse();
                called = true;
                prepared.requireCompletionReady();
                slots.deleteProtected(claim);
            } catch (RuntimeException | Error fault) {
                failure = fault;
                transactions.poison(fault);
                throw fault;
            }
        }

        private void requireActiveSingleUse() {
            if (!active || called) {
                throw new IllegalStateException("Completion is single-use in its final callback");
            }
            if (Thread.currentThread() != thread) {
                throw new IllegalStateException("Completion is single-use in its final callback");
            }
        }

        synchronized void requireCompleted() {
            if (!called || failure != null) {
                var fault = new IllegalStateException("Missing or failed completion", failure);
                transactions.poison(fault);
                throw fault;
            }
        }
    }
}
