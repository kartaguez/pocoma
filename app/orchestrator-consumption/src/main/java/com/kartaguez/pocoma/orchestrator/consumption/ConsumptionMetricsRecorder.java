package com.kartaguez.pocoma.orchestrator.consumption;

import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol.Attempt;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol.Status;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.Outcome;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.Phase;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions.Failure;
import com.kartaguez.pocoma.port.transaction.AttemptTransactions.Resolution;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Package-private observation and timing wrapper for ConsumptionEngine phases. */
final class ConsumptionMetricsRecorder {
    private static final Set<Phase> ZERO_AMOUNT_FAILURE_PHASES =
            Set.of(
                    Phase.CESSION,
                    Phase.ACQUIRE,
                    Phase.CLAIM_RENEW,
                    Phase.LEASE_ACQUIRE,
                    Phase.LEASE_RENEW);

    private final ConsumptionId consumptionId;
    private final ConsumptionObservation observation;
    private final BooleanSupplier unresolvedRecovery;

    ConsumptionMetricsRecorder(
            ConsumptionId consumptionId,
            ConsumptionObservation observation,
            BooleanSupplier unresolvedRecovery) {
        this.consumptionId = consumptionId;
        this.observation = observation;
        this.unresolvedRecovery = unresolvedRecovery;
    }

    <T> T measured(Phase phase, Supplier<T> operation) {
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
                    consumptionId,
                    phase,
                    outcome,
                    amount,
                    Duration.ofNanos(System.nanoTime() - begin));
        }
    }

    void event(Phase phase, Outcome outcome, long amount) {
        ConsumptionObservation.safely(
                observation, consumptionId, phase, outcome, amount, Duration.ZERO);
    }

    private Outcome resolveSuccessOutcome(Phase phase, Object result) {
        if (unresolvedRecovery.getAsBoolean() && isObservationPhase(phase)) {
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
        if (result instanceof ConsumptionEngine.Acquisition acquired) {
            return recordExaminedAcquisition(acquired);
        }
        if (result instanceof ConsumptionNavigation.Page page) return page.keys().size();
        if (phase == Phase.SETTLE && result instanceof Integer count) return count;
        if (result instanceof List<?> list) return list.size();
        if (result instanceof Optional<?> optional) return optional.isEmpty() ? 0 : 1;
        return 1;
    }

    private long recordExaminedAcquisition(ConsumptionEngine.Acquisition acquired) {
        event(Phase.ACQUISITION_EXAMINED, Outcome.CONFIRMED, acquired.examinedCount());
        return acquired.claims().size();
    }
}
