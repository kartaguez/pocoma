package com.kartaguez.pocoma.engine.consume.registration;

import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;

/** Exactly one terminal business decision per request. */
public sealed interface RegistrationOutcome permits RegistrationOutcome.Registered, RegistrationOutcome.Rejected {
    UUID requestId();
    record Registered(UUID requestId, PocomaUserId userId, BindingId bindingId) implements RegistrationOutcome {
        public Registered {
            java.util.Objects.requireNonNull(requestId);
            java.util.Objects.requireNonNull(userId);
            java.util.Objects.requireNonNull(bindingId);
        }
    }
    record Rejected(UUID requestId) implements RegistrationOutcome {
        public static final String CODE = "EXTERNAL_IDENTITY_ALREADY_USED";
        public Rejected { java.util.Objects.requireNonNull(requestId); }
    }
}
