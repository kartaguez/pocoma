package com.kartaguez.pocoma.engine.read.registrationresult;

import static java.util.Objects.requireNonNull;

import java.util.UUID;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;

/** Immutable public Registration decision, independent of execution state. */
public sealed interface PublishedRegistrationResult permits PublishedRegistrationResult.Registered,
        PublishedRegistrationResult.Rejected {
    UUID requestId();

    record Registered(UUID requestId, PocomaUserId userId, BindingId bindingId)
            implements PublishedRegistrationResult {
        public Registered {
            requireNonNull(requestId, "requestId must not be null");
            requireNonNull(userId, "userId must not be null");
            requireNonNull(bindingId, "bindingId must not be null");
        }
    }

    record Rejected(UUID requestId) implements PublishedRegistrationResult {
        public static final String CODE = "EXTERNAL_IDENTITY_ALREADY_USED";
        public Rejected { requireNonNull(requestId, "requestId must not be null"); }
    }
}
