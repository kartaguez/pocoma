package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.v3.RoutingV1;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.DiscoveryAdmissionStore;
import com.kartaguez.pocoma.engine.consumption.v3.port.NonBurnableReferences;
import com.kartaguez.pocoma.engine.consumption.v3.port.PartitionOwnershipStore;
import com.kartaguez.pocoma.engine.consumption.v3.port.SlotKeyNavigation;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.*;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Bounded lifecycle orchestration. One acquisition, one attempt; no polling or internal retry. */
public final class ConsumptionEngine {
    public enum Status {
        COMMITTED,
        CLAIM_LOST,
        ROLLED_BACK,
        RECOVERY_PENDING,
        INCONSISTENT
    }

    public record Attempt(
            Status status,
            Resolution resolution,
            RecoveryObservation observation,
            Throwable cause,
            FailureKind failureKind,
            Cession cession,
            Resolution cessionResolution) {
        public Attempt(
                Status status,
                Resolution resolution,
                RecoveryObservation observation,
                Throwable cause) {
            this(status, resolution, observation, cause, FailureKind.NON_COUNTABLE, null, null);
        }
    }

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
    private final Optional<NonBurnableReferences> recoverySource;
    private final Predicate<NonBurnableReferences.Reference> relevant;
    private final DiscoveryAdmissionStore admissions;
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
        this.sourceTransactions = Objects.requireNonNull(sourceTransactions);
        this.recoverySource = Objects.requireNonNull(recoverySource);
        this.relevant = Objects.requireNonNull(relevant);
        this.admissions = admissions;
        if (definition.authority() == ConsumptionDefinition.Authority.READ) {
            if (recoverySource.isEmpty() || recoverySource.get().family() != definition.family())
                throw new IllegalArgumentException("READ requires its exact source family");
            Objects.requireNonNull(admissions, "READ requires effects admissions");
        } else if (sourceTransactions != effectsTransactions || recoverySource.isPresent())
            throw new IllegalArgumentException(
                    "WRITE requires one source/effects owner and no event lookup");
        this.definition = Objects.requireNonNull(definition);
        this.transactions = Objects.requireNonNull(effectsTransactions);
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

    public SlotKeyNavigation.Page navigate(
            PartitionLease lease, SlotKeyNavigation.Walk walk, int limit) {
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

    private <T> T measured(Phase phase, Supplier<T> operation) {
        long begin = System.nanoTime();
        Outcome outcome = Outcome.CONFIRMED;
        long amount = 1;
        try {
            T result = operation.get();
            if (result instanceof Attempt attempt)
                outcome =
                        switch (attempt.resolution()) {
                            case COMMITTED -> Outcome.COMMITTED;
                            case ROLLED_BACK -> Outcome.ROLLED_BACK;
                            case UNKNOWN -> Outcome.UNKNOWN;
                        };
            if (phase == Phase.ATTEMPT && result instanceof Attempt attempt) {
                if (attempt.status() == Status.INCONSISTENT) outcome = Outcome.INCONSISTENT;
                else if (attempt.status() == Status.RECOVERY_PENDING)
                    outcome = Outcome.RECOVERY_PENDING;
                else if (attempt.status() == Status.CLAIM_LOST) outcome = Outcome.CLAIM_LOST;
            }
            if (result instanceof Acquisition acquired) {
                amount = acquired.claims().size();
                ConsumptionObservation.safely(
                        observation,
                        definition.id(),
                        Phase.ACQUISITION_EXAMINED,
                        Outcome.CONFIRMED,
                        acquired.examinedCount(),
                        Duration.ZERO);
            } else if (result instanceof SlotKeyNavigation.Page page) amount = page.keys().size();
            else if (phase == Phase.SETTLE && result instanceof Integer count) amount = count;
            else if (result instanceof List<?> list) amount = list.size();
            else if (result instanceof java.util.Optional<?> optional) {
                if (optional.isEmpty()) {
                    amount = 0;
                    outcome = Outcome.EMPTY;
                }
            }
            if ((phase == Phase.SETTLE || phase == Phase.OBSERVE) && unresolvedRecovery)
                outcome = Outcome.UNKNOWN;
            return result;
        } catch (Failure failure) {
            if (phase == Phase.CESSION
                    || phase == Phase.ACQUIRE
                    || phase == Phase.CLAIM_RENEW
                    || phase == Phase.LEASE_ACQUIRE
                    || phase == Phase.LEASE_RENEW) amount = 0;
            outcome =
                    switch (failure.resolution()) {
                        case COMMITTED -> Outcome.COMMITTED;
                        case ROLLED_BACK -> Outcome.ROLLED_BACK;
                        case UNKNOWN -> Outcome.UNKNOWN;
                    };
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
            var granted =
                    technical(
                            () ->
                                    ownership
                                            .protectAcquisition(definition, lease)
                                            .map(
                                                    protectedLease ->
                                                            controlSlots.acquire(
                                                                    protectedLease,
                                                                    candidates,
                                                                    settings.claimDuration())));
            var won = new HashSet<ClaimedSlot>();
            granted.orElseGet(List::of).forEach(g -> won.add(g.claim()));
            var result = new ArrayList<AcquiredClaim>();
            for (var handle : handles) {
                removePending(handle);
                if (won.contains(handle.claim)) result.add(handle);
                else handle.ticket.release();
            }
            return new Acquisition(result, granted.isPresent() ? candidates.size() : 0);
        } catch (Failure failure) {
            if (failure.resolution() == Resolution.ROLLED_BACK) {
                for (var handle : handles) {
                    removePending(handle);
                    handle.ticket.release();
                }
            }
            // COMMITTED with lost callback result is also inspected, never guessed or executed.
            throw failure;
        } finally {
            handles.forEach(h -> h.localDone = true);
        }
    }

    public void abandon(AcquiredClaim acquired) {
        start(acquired);
        acquired.localDone = true; // Acquisition was confirmed; no local business callback started.
        acquired.ticket.release(); // The durable claim is left to expire, with no budget mutation.
    }

    private void start(AcquiredClaim acquired) {
        if (acquired == null || acquired.owner != this || !acquired.used.compareAndSet(false, true))
            throw new IllegalArgumentException("foreign or already used claim handle");
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
            if (attempt.status() == Status.CLAIM_LOST)
                ConsumptionObservation.safely(
                        observation,
                        definition.id(),
                        Phase.CLAIM_LOST,
                        Outcome.CONFIRMED,
                        1,
                        Duration.ZERO);
            resolved = attempt.resolution() != Resolution.UNKNOWN;
            var kind = FailureKind.NON_COUNTABLE;
            Cession cession = null;
            Resolution cessionResolution = null;
            if (attempt.status() == Status.ROLLED_BACK
                    && attempt.resolution() == Resolution.ROLLED_BACK) {
                kind = Objects.requireNonNull(classifier.classify(attempt.cause()));
                if (kind != FailureKind.NON_COUNTABLE) {
                    // The business rollback says nothing about this new physical operation.
                    resolved = false;
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
                        if (cession != null && "QUARANTINED".equals(cession.state()))
                            ConsumptionObservation.safely(
                                    observation,
                                    definition.id(),
                                    Phase.QUARANTINE,
                                    Outcome.COMMITTED,
                                    1,
                                    Duration.ZERO);
                    } catch (Failure failure) {
                        cessionResolution = failure.resolution();
                        resolved = cessionResolution != Resolution.UNKNOWN;
                        if (attempt.cause() != null) attempt.cause().addSuppressed(failure);
                        // Never repeat an uncertain cession, even if a later read looks unchanged.
                    }
                }
            }
            return new Attempt(
                    attempt.status(),
                    attempt.resolution(),
                    attempt.observation(),
                    attempt.cause(),
                    kind,
                    cession,
                    cessionResolution);
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

    /** Bounded technical inspection only; never cede, re-execute or repair business data. */
    public int settlePending() {
        return measured(Phase.SETTLE, () -> settlePendingOperation());
    }

    private int settlePendingOperation() {
        if (unresolvedRecovery) return 0;
        List<AcquiredClaim> snapshot;
        synchronized (pending) {
            snapshot =
                    pending.stream()
                            .filter(h -> h.localDone && !h.unresolvedPreparation)
                            .limit(settings.batchSize())
                            .toList();
        }
        if (snapshot.isEmpty()) return 0;
        List<ClaimInspection> inspections;
        try {
            inspections =
                    technical(
                            () ->
                                    controlSlots.inspectResolved(
                                            snapshot.stream().map(h -> h.claim).toList()));
        } catch (RuntimeException | Error unavailable) {
            recoveryFailed(unavailable, null);
            return 0;
        }
        int released = 0;
        for (var handle : snapshot) {
            if (unresolvedRecovery) break;
            var inspection =
                    inspections.stream()
                            .filter(i -> i.claim().equals(handle.claim))
                            .map(ClaimInspection::inspection)
                            .findFirst()
                            .orElse(Inspection.LOCKED);
            boolean safe =
                    inspection == Inspection.PRESENT_UNLOCKED
                            || (inspection == Inspection.ABSENT
                                    && (!handle.businessStarted
                                            || observe(handle.claim)
                                                    == RecoveryObservation.COMPLETED));
            if (safe && handle.localDone && removePending(handle) && handle.ticket.release())
                released++;
        }
        return released;
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
        if (keys.size() > settings.batchSize()
                || keys.stream().anyMatch(Objects::isNull)
                || new HashSet<>(keys).size() != keys.size())
            throw new IllegalArgumentException("invalid bounded keys");
    }

    private Attempt executeAttempt(AcquiredClaim acquired) {
        var claim = acquired.claim;
        Objects.requireNonNull(claim);
        if (!definition.id().equals(claim.consumptionId()))
            throw new IllegalArgumentException("Claim belongs to another Consumption");
        var reads = new Reads();
        Optional<ConsumptionParticipant.Prepared> prepared;
        try {
            prepared =
                    measured(Phase.PREPARE, () -> participant.prepare(claim.consumableId(), reads));
            reads.requireValid();
            Objects.requireNonNull(prepared);
        } catch (RuntimeException | Error failure) {
            reads.invalidate(failure);
            if (reads.physicallyUnresolved() && reads.failedResolution == null) throw failure;
            if (reads.physicallyUnresolved())
                return new Attempt(
                        Status.RECOVERY_PENDING, Resolution.UNKNOWN, null, reads.cause());
            if (reads.failedResolution == Resolution.COMMITTED)
                return observed(claim, Resolution.UNKNOWN, reads.cause());
            // No business transaction began; resolved reads keep their own physical outcome.
            return new Attempt(Status.ROLLED_BACK, Resolution.ROLLED_BACK, null, reads.cause());
        } finally {
            reads.expire();
            if (reads.physicallyUnresolved()) acquired.unresolvedPreparation = true;
        }
        return prepared.isEmpty()
                ? observed(claim, Resolution.UNKNOWN, null)
                : finalizeAttempt(claim, prepared.get(), reads);
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
        if (!definition.id().equals(claim.consumptionId()))
            throw new IllegalArgumentException("Claim belongs to another Consumption");
        if (unresolvedRecovery) return RecoveryObservation.UNAVAILABLE;
        var reads = new Reads();
        NonBurnableReferences.Reference reference = null;
        boolean readingSource = definition.authority() == ConsumptionDefinition.Authority.READ;
        boolean finalReadStarted = false;
        try {
            if (readingSource) {
                var found =
                        reads.source(
                                ctx ->
                                        recoverySource
                                                .orElseThrow()
                                                .find(claim.consumableId(), ctx));
                reads.requireValid();
                if (found.isEmpty()
                        || !found.get().eventId().equals(claim.consumableId())
                        || !relevant.test(found.get())) return RecoveryObservation.INCONSISTENT;
                reference = found.get();
            }
            readingSource = false;
            var recovery =
                    Objects.requireNonNull(
                            participant.prepareRecovery(claim.consumableId(), reads));
            reads.expire();
            reads.requireValid();
            var source = reference;
            finalReadStarted = true;
            return transactions.read(
                    context -> {
                        var slot =
                                slots.observe(claim.consumptionId(), claim.consumableId(), context);
                        long checkpoint =
                                source == null
                                        ? 0
                                        : admissions.checkpoint(
                                                definition.id(),
                                                RoutingV1.partition(
                                                        RoutingV1.hash(
                                                                definition.family(),
                                                                claim.consumableId()),
                                                        definition.partitionCount()),
                                                context);
                        var business = recovery.observe(context);
                        if (business == ConsumptionParticipant.EffectEvidence.UNAVAILABLE)
                            return RecoveryObservation.UNAVAILABLE;
                        if (source != null && checkpoint < source.position())
                            return RecoveryObservation.INCONSISTENT;
                        return switch (business) {
                            case SATISFIED ->
                                    slot == SlotObservation.ABSENT
                                            ? RecoveryObservation.COMPLETED
                                            : source != null
                                                    ? RecoveryObservation.NOT_COMPLETED
                                                    : RecoveryObservation.INCONSISTENT;
                            case MISSING ->
                                    slot == SlotObservation.PRESENT
                                            ? RecoveryObservation.NOT_COMPLETED
                                            : RecoveryObservation.INCONSISTENT;
                            case DIVERGENT -> RecoveryObservation.INCONSISTENT;
                            case UNAVAILABLE -> RecoveryObservation.UNAVAILABLE;
                        };
                    },
                    reads);
        } catch (RuntimeException | Error failure) {
            if (reads.physicallyUnresolved()) recoveryFailed(failure, claim);
            else if (readingSource
                    && reads.cause() instanceof NonBurnableReferences.InvalidReference)
                return RecoveryObservation.INCONSISTENT;
            else if (reads.failedResolution != null)
                recoveryFailed(new Failure(reads.failedResolution, failure), claim);
            else if (!finalReadStarted)
                recoveryFailed(new Failure(Resolution.ROLLED_BACK, failure), claim);
            else recoveryFailed(failure, claim);
            return RecoveryObservation.UNAVAILABLE;
        } finally {
            reads.expire();
        }
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
            synchronized (this) {
                if (Thread.currentThread() != thread || !open || active) {
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
            T result;
            try {
                result = owner.read(action, this);
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
            // Physical success preceded this barrier; a late poison cannot undo that success.
            requireValid();
            return result;
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
                if (!active || Thread.currentThread() != thread || called)
                    throw new IllegalStateException(
                            "Completion is single-use in its final callback");
                called = true;
                prepared.requireCompletionReady();
                slots.deleteProtected(claim);
            } catch (RuntimeException | Error fault) {
                failure = fault;
                transactions.poison(fault);
                throw fault;
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
