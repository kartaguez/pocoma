package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.PartitionOwnershipStore;
import com.kartaguez.pocoma.engine.consumption.v3.port.SlotKeyNavigation;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** Local capacity handle. No mirrored durable state or cached deadline. */
    public static final class AcquiredClaim {
        private final ConsumptionEngine owner;
        private final ClaimedSlot claim;
        private final WorkerCapacity.Ticket ticket;
        private final AtomicBoolean used = new AtomicBoolean();
        private volatile boolean localDone;
        private boolean businessStarted;

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
            var attempt = executeAttempt(acquired.claim);
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
            if (resolved) {
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
        List<AcquiredClaim> snapshot;
        synchronized (pending) {
            snapshot =
                    pending.stream().filter(h -> h.localDone).limit(settings.batchSize()).toList();
        }
        if (snapshot.isEmpty()) return 0;
        List<ClaimInspection> inspections;
        try {
            inspections =
                    technical(
                            () ->
                                    controlSlots.inspectResolved(
                                            snapshot.stream().map(h -> h.claim).toList()));
        } catch (RuntimeException unavailable) {
            return 0;
        }
        int released = 0;
        for (var handle : snapshot) {
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

    private Attempt executeAttempt(ClaimedSlot claim) {
        Objects.requireNonNull(claim);
        if (!definition.id().equals(claim.consumptionId()))
            throw new IllegalArgumentException("Claim belongs to another Consumption");
        java.util.Optional<ConsumptionParticipant.Prepared> prepared;
        try {
            prepared =
                    measured(
                            Phase.PREPARE,
                            () ->
                                    transactions.read(
                                            context -> participant.prepare(claim.consumableId())));
        } catch (Failure failure) {
            // A committed preparation snapshot is not a committed business attempt.
            return failure.resolution() == Resolution.ROLLED_BACK
                    ? new Attempt(
                            Status.ROLLED_BACK, Resolution.ROLLED_BACK, null, failure.getCause())
                    : observed(claim, Resolution.UNKNOWN, failure.getCause());
        }
        return prepared.isEmpty()
                ? observed(claim, Resolution.UNKNOWN, null)
                : finalizeAttempt(claim, prepared.get());
    }

    private Attempt finalizeAttempt(ClaimedSlot claim, ConsumptionParticipant.Prepared prepared) {
        return measured(Phase.FINALIZE, () -> finalizeAttemptOperation(claim, prepared));
    }

    private Attempt finalizeAttemptOperation(
            ClaimedSlot claim, ConsumptionParticipant.Prepared prepared) {
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
                    });
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
        try {
            return transactions.read(
                    context -> {
                        var slot =
                                slots.observe(claim.consumptionId(), claim.consumableId(), context);
                        var business = participant.observe(claim.consumableId(), context);
                        return switch (business) {
                            case COMPLETED ->
                                    slot == ConsumptionSlotStore.SlotObservation.ABSENT
                                            ? business
                                            : RecoveryObservation.INCONSISTENT;
                            case NOT_COMPLETED ->
                                    slot == ConsumptionSlotStore.SlotObservation.PRESENT
                                            ? business
                                            : RecoveryObservation.INCONSISTENT;
                            case INCONSISTENT, UNAVAILABLE -> business;
                        };
                    });
        } catch (RuntimeException failure) {
            return RecoveryObservation.UNAVAILABLE;
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
