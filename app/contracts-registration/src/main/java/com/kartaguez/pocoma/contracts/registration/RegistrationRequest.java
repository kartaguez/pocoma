package com.kartaguez.pocoma.contracts.registration;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

/** The immutable authenticated intention. The current Canon defines no profile fields. */
public record RegistrationRequest(UUID requestId, ExternalIdentity requesterExternalIdentity,
        String payload, Instant createdAt) {
    public RegistrationRequest {
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(requesterExternalIdentity);
        Objects.requireNonNull(payload);
        Objects.requireNonNull(createdAt);
        if (!payload.equals("{}")) throw new IllegalArgumentException("Registration payload must be empty");
    }
}
