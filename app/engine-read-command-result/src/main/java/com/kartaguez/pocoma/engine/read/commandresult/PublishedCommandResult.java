package com.kartaguez.pocoma.engine.read.commandresult;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.UUID;
import com.kartaguez.pocoma.contracts.command.CommandId;

/** Immutable public decision, independent of execution state. */
public sealed interface PublishedCommandResult permits PublishedCommandResult.Applied,
        PublishedCommandResult.Rejected, PublishedCommandResult.Failed {
    String PUBLIC_FAILURE_CODE = "COMMAND_PROCESSING_FAILED";

    CommandId commandId();
    Instant resolvedAt();

    record Applied(CommandId commandId, UUID potId, long resultingVersion, Instant resolvedAt)
            implements PublishedCommandResult {
        public Applied {
            requireNonNull(commandId, "commandId must not be null");
            requireNonNull(potId, "potId must not be null");
            if (resultingVersion < 1) {
                throw new IllegalArgumentException("resultingVersion must be greater than or equal to 1");
            }
            requireNonNull(resolvedAt, "resolvedAt must not be null");
        }
    }

    record Rejected(CommandId commandId, String rejectionCode, Instant resolvedAt)
            implements PublishedCommandResult {
        public Rejected {
            requireNonNull(commandId, "commandId must not be null");
            rejectionCode = requireText(rejectionCode, "rejectionCode");
            requireNonNull(resolvedAt, "resolvedAt must not be null");
        }
    }

    record Failed(CommandId commandId, String publicFailureCode, Instant resolvedAt)
            implements PublishedCommandResult {
        public Failed {
            requireNonNull(commandId, "commandId must not be null");
            publicFailureCode = requireText(publicFailureCode, "publicFailureCode");
            if (!PUBLIC_FAILURE_CODE.equals(publicFailureCode)) {
                throw new IllegalArgumentException("publicFailureCode must be " + PUBLIC_FAILURE_CODE);
            }
            requireNonNull(resolvedAt, "resolvedAt must not be null");
        }
    }

    private static String requireText(String value, String field) {
        requireNonNull(value, field + " must not be null");
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
