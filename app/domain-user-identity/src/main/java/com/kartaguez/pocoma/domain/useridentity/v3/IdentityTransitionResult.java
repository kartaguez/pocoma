package com.kartaguez.pocoma.domain.useridentity.v3;

import com.kartaguez.pocoma.domain.useridentity.User;

import java.util.Objects;
import java.util.Optional;

/** Strict transitions have either one new EUI state (and possibly a new User), or no effects. */
public sealed interface IdentityTransitionResult {
    record Changed(Optional<User> createdUser, ExternalUserIdentity identity)
            implements IdentityTransitionResult {
        public Changed {
            Objects.requireNonNull(createdUser, "createdUser");
            Objects.requireNonNull(identity, "identity");
            if (createdUser.isPresent() && !createdUser.get().id().equals(identity.userId()))
                throw new IllegalArgumentException("Created User must own the new EUI state");
        }
    }

    record Rejected(Rejection reason) implements IdentityTransitionResult {
        public Rejected {
            Objects.requireNonNull(reason, "reason");
        }
    }

    enum Rejection {
        INVALID_BINDING,
        TARGET_ALREADY_BOUND,
        TARGET_NOT_BOUND,
        TARGET_BOUND_TO_ANOTHER_USER
    }
}
