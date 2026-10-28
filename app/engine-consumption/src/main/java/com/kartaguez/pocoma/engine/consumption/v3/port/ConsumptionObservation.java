package com.kartaguez.pocoma.engine.consumption.v3.port;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;

import java.time.Duration;

/** Bounded operational facts; implementations must be nonblocking and must never perform SQL. */
@FunctionalInterface
public interface ConsumptionObservation {
    enum Phase {
        LEASE_ACQUIRE,
        LEASE_RENEW,
        LEASE_SUSPEND,
        CLAIM_RENEW,
        CLAIM_LOST,
        RENEWAL_S1,
        RENEWAL_S2,
        RENEWAL_LEASE,
        RENEWAL_LAG_S1,
        RENEWAL_LAG_S2,
        RENEWAL_LAG_LEASE,
        NAVIGATION,
        ACQUIRE,
        ACQUISITION_EXAMINED,
        PREPARE,
        FINALIZE,
        CESSION,
        QUARANTINE,
        OBSERVE,
        SETTLE,
        PENDING,
        CAPACITY_RESERVED,
        CAPACITY_RETURNED,
        DISCOVERY_EXAMINED,
        DISCOVERY_ADVANCED,
        DISCOVERY_ADMITTED,
        DISCOVERY,
        DISCOVERY_CYCLE,
        CONSUMPTION_CYCLE,
        DISCOVERY_ROTATION,
        CONSUMPTION_ROTATION,
        DISCOVERY_TOUR,
        CONSUMPTION_TOUR,
        OPPORTUNITY,
        OPPORTUNITY_WAIT,
        SATURATION_WORKER,
        SATURATION_CONSUMPTION,
        SATURATION_EXECUTOR,
        SATURATION_SOFTCAP,
        DISPATCH_REJECTED,
        DISPATCH_STOPPED,
        DISPATCH_SATURATED,
        ATTEMPT_STARTED,
        ATTEMPT,
        DRAIN
    }

    enum Outcome {
        CONFIRMED,
        COMMITTED,
        ROLLED_BACK,
        UNKNOWN,
        EMPTY,
        BUSY,
        CAPACITY,
        REJECTED,
        INCONSISTENT,
        RECOVERY_PENDING,
        CLAIM_LOST
    }

    void record(ConsumptionId id, Phase phase, Outcome outcome, long amount, Duration elapsed);

    static ConsumptionObservation noop() {
        return (id, phase, outcome, amount, elapsed) -> {};
    }

    static void safely(
            ConsumptionObservation observation,
            ConsumptionId id,
            Phase phase,
            Outcome outcome,
            long amount,
            Duration elapsed) {
        try {
            observation.record(id, phase, outcome, amount, elapsed);
        } catch (RuntimeException | Error ignored) {
            /* Metrics cannot change transactional outcomes. */
        }
    }
}
