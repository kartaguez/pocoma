package com.kartaguez.pocoma.engine.write.pot.port.execution.v3;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

import java.time.Duration;
import java.util.*;

/**
 * WRITE request/slot/result/publication boundary; every operation uses the caller's transaction.
 */
public interface PotCommandStore {
    enum Outcome {
        CHANGED,
        UNCHANGED,
        REJECTED
    }

    record Result(Outcome outcome, Long resultingPotVersion, String rejection) {
        public Result {
            Objects.requireNonNull(outcome);
            if (outcome == Outcome.REJECTED
                    ? rejection == null || resultingPotVersion != null
                    : rejection != null || resultingPotVersion == null || resultingPotVersion < 1)
                throw new IllegalArgumentException("Invalid terminal result");
        }

        public static Result rejected(String reason) {
            return new Result(Outcome.REJECTED, null, reason);
        }
    }

    record Stored(PotCommand command, Optional<Result> terminal) {
        public Stored {
            Objects.requireNonNull(command);
            Objects.requireNonNull(terminal);
        }
    }

    record Component(String kind, UUID objectId, long localVersion) {
        public Component {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(objectId);
            if (localVersion < 1) throw new IllegalArgumentException();
        }
    }

    enum FailureRecorded {
        RETRY_SCHEDULED,
        QUARANTINED,
        CLAIM_LOST
    }

    Stored load(UUID requestId);

    long currentVersion(PotId potId);

    /**
     * Exclusive slot lock, then fresh DB time. Hold through physical commit, no partition fence.
     */
    boolean protectClaim(UUID requestId, UUID claimToken);

    /** Protect absence too (CreatePot/NOT_FOUND). Return false on changed working version. */
    boolean protectPot(PotId potId, long expectedVersion);

    /** After all domain writes: result/status and slot removal, no publication lock yet. */
    void finish(PotCommand command, UUID claimToken, Result result);

    /** Last operation: flush/deferred checks, then dense POT -> COMMAND_COMPLETION publication. */
    void publish(PotCommand command, Result result, List<Component> changedComponents);

    /** A short, separate transaction AFTER confirmed rollback; conditional once per valid claim. */
    FailureRecorded recordFailure(
            UUID requestId, UUID claimToken, int maximumFailures, Duration delay);
}
