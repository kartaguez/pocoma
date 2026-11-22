package com.kartaguez.pocoma.engine.write.pot.port.execution;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;

import java.util.*;

/**
 * WRITE request/slot/result/publication boundary; every operation uses the caller's transaction.
 */
public interface PotCommandStore {
    enum Outcome {
        INVITATION_CREATED,
        CHANGED,
        UNCHANGED,
        REJECTED
    }

    record CreatedShareholder(int inputIndex, UUID shareholderId) {
        public CreatedShareholder {
            if (inputIndex < 0) throw new IllegalArgumentException("Negative input index");
            Objects.requireNonNull(shareholderId);
        }
    }

    record CreatedResources(UUID potId, UUID expenseId, List<CreatedShareholder> shareholders) {
        public CreatedResources {
            if (shareholders != null) {
                if (shareholders.isEmpty()) {
                    throw new IllegalArgumentException("Empty created shareholders");
                }
                var seen = new HashSet<UUID>();
                for (int i = 0; i < shareholders.size(); i++) {
                    var item = Objects.requireNonNull(shareholders.get(i));
                    if (item.inputIndex() != i || !seen.add(item.shareholderId())) {
                        throw new IllegalArgumentException("Invalid created shareholder ordering");
                    }
                }
                shareholders = List.copyOf(shareholders);
            }
            int populated =
                    (potId != null ? 1 : 0)
                            + (expenseId != null ? 1 : 0)
                            + (shareholders != null ? 1 : 0);
            if (populated != 1) {
                throw new IllegalArgumentException("Exactly one created resource family required");
            }
        }

        public static CreatedResources pot(UUID potId) {
            return new CreatedResources(Objects.requireNonNull(potId), null, null);
        }

        public static CreatedResources expense(UUID expenseId) {
            return new CreatedResources(null, Objects.requireNonNull(expenseId), null);
        }

        public static CreatedResources shareholders(List<CreatedShareholder> shareholders) {
            return new CreatedResources(null, null, Objects.requireNonNull(shareholders));
        }
    }

    record Result(
            Outcome outcome,
            Long resultingPotVersion,
            String rejection,
            PotId potId,
            CreatedResources createdResources) {
        public Result(Outcome outcome, Long resultingPotVersion, String rejection) {
            this(outcome, resultingPotVersion, rejection, null, null);
        }

        public Result {
            Objects.requireNonNull(outcome);
            if (outcome == Outcome.REJECTED
                    ? rejection == null || resultingPotVersion != null
                    : outcome == Outcome.INVITATION_CREATED
                            ? rejection != null || resultingPotVersion != null
                            : rejection != null
                                    || resultingPotVersion == null
                                    || resultingPotVersion < 1)
                throw new IllegalArgumentException("Invalid terminal result");
            if (createdResources != null) {
                if (outcome != Outcome.CHANGED) {
                    throw new IllegalArgumentException("CreatedResources only allowed on CHANGED");
                }
                if (createdResources.potId() != null
                        && potId != null
                        && !createdResources.potId().equals(potId.value())) {
                    throw new IllegalArgumentException("Created PotId mismatch");
                }
            }
        }

        public PotId effectivePotId(PotCommand command) {
            return potId != null ? potId : command.potId();
        }

        public static Result rejected(String reason) {
            return new Result(Outcome.REJECTED, null, reason, null, null);
        }

        public static Result rejected(String reason, PotId potId) {
            return new Result(Outcome.REJECTED, null, reason, potId, null);
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
