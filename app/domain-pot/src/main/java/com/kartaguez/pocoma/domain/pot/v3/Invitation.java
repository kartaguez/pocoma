package com.kartaguez.pocoma.domain.pot.v3;

import java.time.Instant;
import java.util.*;

/** Immutable invitation history value, independent of the Pot's global version. */
public record Invitation(
        UUID invitationId,
        long version,
        UUID creationRequestId,
        String creatorIssuer,
        String creatorSubject,
        UUID potId,
        Instant expiresAt,
        String tokenHash,
        String protectedLink) {
    public Invitation {
        Objects.requireNonNull(invitationId);
        Objects.requireNonNull(creationRequestId);
        Objects.requireNonNull(creatorIssuer);
        Objects.requireNonNull(creatorSubject);
        Objects.requireNonNull(potId);
        Objects.requireNonNull(expiresAt);
        Objects.requireNonNull(tokenHash);
        Objects.requireNonNull(protectedLink);
        if (version < 1
                || creatorIssuer.isBlank()
                || creatorSubject.isBlank()
                || !tokenHash.matches("[0-9a-f]{64}")
                || protectedLink.isBlank())
            throw new IllegalArgumentException("Invalid invitation");
    }

    @Override
    public String toString() {
        return "Invitation[id=" + invitationId + ", version=" + version + "]";
    }
}
