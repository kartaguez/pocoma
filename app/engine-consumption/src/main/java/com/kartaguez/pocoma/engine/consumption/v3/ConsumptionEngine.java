package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.*;

import java.util.Objects;

/** Bounded R1/R9 orchestration. No acquisition, cession, retries or polling. */
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
            Throwable cause) {}

    private static final class ClaimLost extends RuntimeException {}

    private final ConsumptionDefinition definition;
    private final AttemptTransactions transactions;
    private final ConsumptionSlotStore slots;
    private final ConsumptionParticipant participant;

    public ConsumptionEngine(
            ConsumptionDefinition definition,
            AttemptTransactions transactions,
            ConsumptionSlotStore slots,
            ConsumptionParticipant participant) {
        this.definition = Objects.requireNonNull(definition);
        this.transactions = Objects.requireNonNull(transactions);
        this.slots = Objects.requireNonNull(slots);
        this.participant = Objects.requireNonNull(participant);
    }

    public Attempt execute(ClaimedSlot claim) {
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
