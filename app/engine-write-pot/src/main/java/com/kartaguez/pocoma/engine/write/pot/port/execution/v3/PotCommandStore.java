package com.kartaguez.pocoma.engine.write.pot.port.execution.v3;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

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

    Stored load(UUID requestId);

    long currentVersion(PotId potId);

    /** Protect absence too; false means the prepared working version changed. */
    boolean protectPot(PotId potId, long expectedVersion);

    void finishTerminal(PotCommand command, Result result);

    /** Actual result and exact effects of this transaction, before Engine completes the slot. */
    void requireCompletionReady(
            PotCommand command, Result result, List<Component> changedComponents);

    /** Last operation: events, flush/constraints, closed dense POT -> COMPLETION suffix. */
    void publish(PotCommand command, Result result, List<Component> changedComponents);
}
