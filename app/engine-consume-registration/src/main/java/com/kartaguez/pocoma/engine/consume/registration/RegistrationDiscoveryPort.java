package com.kartaguez.pocoma.engine.consume.registration;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Metadata-only discovery; execution reloads the authoritative request. */
public interface RegistrationDiscoveryPort {
    Optional<Candidate> next(int segmentIndex, int segmentCount, Instant now, Optional<Cursor> after);

    record Candidate(UUID requestId, Cursor cursor) {}
    record Cursor(Instant createdAt, UUID requestId) {}
}
