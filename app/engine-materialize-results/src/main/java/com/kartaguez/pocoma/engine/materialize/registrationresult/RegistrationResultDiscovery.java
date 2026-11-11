package com.kartaguez.pocoma.engine.materialize.registrationresult;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
public interface RegistrationResultDiscovery {
    String CONSUMER_TYPE = "REGISTRATION_RESULT_MATERIALIZER_V1";
    Optional<Candidate> next(int segmentIndex, int segmentCount, Instant now, Optional<Cursor> after);
    record Candidate(UUID requestId, Cursor cursor) {}
    record Cursor(Instant decidedAt, UUID requestId) {}
}
