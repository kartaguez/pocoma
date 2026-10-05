package com.kartaguez.pocoma.supra.http.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.engine.read.registrationresult.PublishedRegistrationResult;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.read.registrationresult.GetRegistrationResultService;
import com.kartaguez.pocoma.engine.read.registrationresult.ImmutableRegistrationResult;
import com.kartaguez.pocoma.engine.read.registrationresult.RegistrationResultStore;

class RegistrationResultControllerTest {
    private static final UUID ID = UUID.randomUUID();
    private static final ExternalIdentity OWNER = new ExternalIdentity("issuer", "owner");

    @Test void absentAndNonOwnerHaveTheSameOpaque404() {
        var store = new MemoryStore();
        var controller = new RegistrationResultController(new GetRegistrationResultService(store));
        var absent = controller.get(ID, principal("owner"));
        store.result = new ImmutableRegistrationResult(OWNER, new PublishedRegistrationResult.Rejected(ID));
        var nonOwner = controller.get(ID, principal("other"));
        assertEquals(404, absent.getStatusCode().value());
        assertEquals(absent.getStatusCode(), nonOwner.getStatusCode());
        assertNull(absent.getBody());
        assertEquals(absent.getBody(), nonOwner.getBody());
        assertEquals(200, controller.get(ID, principal("owner")).getStatusCode().value());
    }

    private static AuthenticatedExternalPrincipal principal(String subject) {
        Instant now = Instant.parse("2026-10-04T10:00:00Z");
        return new AuthenticatedExternalPrincipal("issuer", subject, now.minusSeconds(1), now,
                now.plusSeconds(60), Set.of());
    }

    private static final class MemoryStore implements RegistrationResultStore {
        private ImmutableRegistrationResult result;
        @Override public void ensureResult(ImmutableRegistrationResult value) { result = value; }
        @Override public Optional<ImmutableRegistrationResult> find(UUID requestId) {
            return Optional.ofNullable(result);
        }
    }
}
