package com.kartaguez.pocoma.engine.read.registrationresult;


import java.util.Objects;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;

/** Historical owner and immutable terminal decision. */
public record ImmutableRegistrationResult(ExternalIdentity owner, PublishedRegistrationResult outcome) {
    public ImmutableRegistrationResult {
        Objects.requireNonNull(owner);
        Objects.requireNonNull(outcome);
    }
}
