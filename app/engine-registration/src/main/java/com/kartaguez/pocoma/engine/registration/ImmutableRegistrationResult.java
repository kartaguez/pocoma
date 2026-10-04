package com.kartaguez.pocoma.engine.registration;

import com.kartaguez.pocoma.contracts.registration.RegistrationOutcome;

import java.util.Objects;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

/** Historical owner and immutable terminal decision. */
public record ImmutableRegistrationResult(ExternalIdentity owner, RegistrationOutcome outcome) {
    public ImmutableRegistrationResult {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(outcome);
    }
}
