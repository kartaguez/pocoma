package com.kartaguez.pocoma.engine.materialize.registrationresult;
import com.kartaguez.pocoma.engine.read.registrationresult.*;

import com.kartaguez.pocoma.contracts.registration.RegistrationRequest;
import com.kartaguez.pocoma.contracts.registration.RegistrationOutcome;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;

class RegistrationResultServiceTest {
    private static final ExternalIdentity E1 = new ExternalIdentity("issuer", "one");
    private static final ExternalIdentity E2 = new ExternalIdentity("issuer", "two");

    @Test void materializesHistoricalOwnerAndHidesOtherIdentity() {
        UUID id = UUID.randomUUID();
        var request = new RegistrationRequest(id, E1, "{}", Instant.now());
        var outcome = new RegistrationOutcome.Rejected(id);
        Map<UUID, ImmutableRegistrationResult> rows = new HashMap<>();
        RegistrationResultStore results = store(rows);
        var materializer = new MaterializeRegistrationResultService(
                key -> new RegistrationResultSource(request, outcome), results);
        materializer.materialize(id);
        materializer.materialize(id);
        assertEquals(1, rows.size());
        assertEquals(Optional.of(outcome), new GetRegistrationResultService(results).get(id, E1));
        assertTrue(new GetRegistrationResultService(results).get(id, E2).isEmpty());
    }

    @Test void rejectsMismatchedRequestAndOutcomeIds() {
        UUID id = UUID.randomUUID();
        var request = new RegistrationRequest(id, E1, "{}", Instant.now());
        assertThrows(IllegalStateException.class, () ->
                new MaterializeRegistrationResultService(
                        key -> new RegistrationResultSource(request, new RegistrationOutcome.Rejected(UUID.randomUUID())),
                        store(new HashMap<>())).materialize(id));
    }

    private static RegistrationResultStore store(Map<UUID, ImmutableRegistrationResult> rows) {
        return new RegistrationResultStore() {
            public void ensureResult(ImmutableRegistrationResult result) {
                var prior = rows.putIfAbsent(result.outcome().requestId(), result);
                if (prior != null && !prior.equals(result)) throw new IllegalStateException("divergent");
            }
            public Optional<ImmutableRegistrationResult> find(UUID key) { return Optional.ofNullable(rows.get(key)); }
        };
    }

}
