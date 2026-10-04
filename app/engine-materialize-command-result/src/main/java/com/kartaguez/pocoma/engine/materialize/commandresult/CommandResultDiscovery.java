package com.kartaguez.pocoma.engine.materialize.commandresult;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import com.kartaguez.pocoma.engine.materialize.commandresult.CommandResultSource;
public interface CommandResultDiscovery {
    String CONSUMER_TYPE = "COMMAND_RESULT_MATERIALIZER_V2";
    Optional<Candidate> next(int segmentIndex, int segmentCount, Instant now, Optional<Cursor> after);
    CommandResultSource reload(UUID eventId);
    record Candidate(UUID eventId, UUID commandId, Cursor cursor) {}
    record Cursor(Instant recordedAt, UUID eventId) {}
}
