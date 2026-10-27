package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier;
import com.kartaguez.pocoma.engine.consumption.v3.port.AttemptFailureClassifier.FailureKind;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.PartitionOwnershipStore;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.*;

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

    private static final class ClaimLost extends RuntimeException {}

    private final ConsumptionDefinition definition;
    private final AttemptTransactions transactions;
    private final ConsumptionSlotStore slots;
    private final ConsumptionParticipant participant;

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
        this.definition = Objects.requireNonNull(definition);
        this.transactions = Objects.requireNonNull(transactions);
        this.slots = Objects.requireNonNull(slots);
        this.participant = Objects.requireNonNull(participant);
        this.ownership = Objects.requireNonNull(ownership);
        this.settings = Objects.requireNonNull(settings);
        this.capacity = Objects.requireNonNull(capacity);
        this.classifier = Objects.requireNonNull(classifier);
        if (!capacity.supports(definition.id()))
            throw new IllegalArgumentException("unconfigured capacity");
    }

    private <T> T technical(Supplier<T> operation) {
        return transactions.write(
                () -> {
                    ownership.bound(settings.lockTimeout(), settings.statementTimeout());
                    return operation.get();
                });
    }

    public Optional<PartitionLease> acquirePartition(int partition) {
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
        bounded(leases);
        leases.forEach(this::checkLease);
        return technical(() -> ownership.renew(definition, leases, settings.leaseDuration()));
    }

    public List<GrantedClaim> renewClaims(List<ClaimedSlot> claims) {
        bounded(claims);
        claims.forEach(
                c -> {
                    if (!c.consumptionId().equals(definition.id()))
                        throw new IllegalArgumentException("foreign claim");
                });
        return technical(() -> slots.renew(claims, settings.claimDuration()));
    }

    public List<AcquiredClaim> acquire(PartitionLease lease, List<UUID> examinedKeys) {
        checkLease(lease);
        bounded(examinedKeys);
        var tickets = capacity.reserve(definition.id(), examinedKeys.size());
        if (tickets.isEmpty()) return List.of();
        var candidates = new ArrayList<ClaimCandidate>();
        var handles = new ArrayList<AcquiredClaim>();
        for (int i = 0; i < tickets.size(); i++) {
            var claim = new ClaimedSlot(definition.id(), examinedKeys.get(i), UUID.randomUUID());
            candidates.add(new ClaimCandidate(claim.consumableId(), claim.claimToken()));
            handles.add(new AcquiredClaim(this, claim, tickets.get(i)));
        }
        pending.addAll(handles);
        try {
            var granted =
                    technical(
                            () ->
                                    ownership
                                            .protectAcquisition(definition, lease)
                                            .map(
                                                    protectedLease ->
                                                            slots.acquire(
                                                                    protectedLease,
                                                                    candidates,
                                                                    settings.claimDuration()))
                                            .orElseGet(List::of));
            var won = new HashSet<ClaimedSlot>();
            granted.forEach(g -> won.add(g.claim()));
            var result = new ArrayList<AcquiredClaim>();
            for (var handle : handles) {
                pending.remove(handle);
                if (won.contains(handle.claim)) result.add(handle);
                else handle.ticket.release();
            }
            return List.copyOf(result);
        } catch (Failure failure) {
            if (failure.resolution() == Resolution.ROLLED_BACK) {
                for (var handle : handles) {
                    pending.remove(handle);
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
        start(acquired);
        acquired.localDone = false;
        acquired.businessStarted = true;
        pending.add(acquired);
        try {
            var attempt = executeAttempt(acquired.claim);
            boolean resolved = attempt.resolution() != Resolution.UNKNOWN;
            var kind = FailureKind.NON_COUNTABLE;
            Cession cession = null;
            Resolution cessionResolution = null;
            if (attempt.status() == Status.ROLLED_BACK
                    && attempt.resolution() == Resolution.ROLLED_BACK) {
                kind = Objects.requireNonNull(classifier.classify(attempt.cause()));
                if (kind != FailureKind.NON_COUNTABLE) {
                    try {
                        cession =
                                technical(() -> slots.cede(acquired.claim, settings.retry()))
                                        .orElse(null);
                        cessionResolution = Resolution.COMMITTED;
                    } catch (Failure failure) {
                        cessionResolution = failure.resolution();
                        resolved = cessionResolution != Resolution.UNKNOWN;
                        if (attempt.cause() != null) attempt.cause().addSuppressed(failure);
                        // Never repeat an uncertain cession, even if a later read looks unchanged.
                    }
                }
            }
            if (resolved) {
                acquired.localDone = true;
                pending.remove(acquired);
                acquired.ticket.release();
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
        }
    }

    /** Bounded technical inspection only; never cede, re-execute or repair business data. */
    public int settlePending() {
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
                                    slots.inspectResolved(
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
            if (safe && handle.localDone && pending.remove(handle) && handle.ticket.release())
                released++;
        }
        return released;
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
            prepared = transactions.read(context -> participant.prepare(claim.consumableId()));
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
