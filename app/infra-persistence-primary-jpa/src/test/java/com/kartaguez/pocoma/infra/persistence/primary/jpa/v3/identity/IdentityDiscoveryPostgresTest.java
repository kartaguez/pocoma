package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.engine.consume.registration.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;

@Testcontainers
class IdentityDiscoveryPostgresTest extends IdentityEngineFixture {
    @Test
    void l3_burnable_discovery() {
        var e = key("burn");
        var req = request(new IdentityRequest.Registration(), e, null, e);
        admitRequest(req);
        assertEquals(0, count("consumption_slot"));
        discover(req);
        discover(req);
        assertEquals(1, count("consumption_slot"));
        execute(req);
        discover(req);
        assertEquals(0, count("consumption_slot"));
        assertEquals(0, count("discovery_checkpoint"));
    }

    @Test
    void l3_discovery_source_slot_race() throws Exception {
        var e = key("race");
        var req = request(new IdentityRequest.Registration(), e, null, e);
        admitRequest(req);
        var handle = claim(req);
        var finalizerAtState = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        hook =
                b -> {
                    if (b.equals("after_state")) {
                        finalizerAtState.countDown();
                        await(finish);
                    }
                };
        var finalizer = threads.submit(() -> engine.execute(handle));
        await(finalizerAtState);
        var concurrent =
                threads.submit(
                        () -> {
                            discover(req);
                            return true;
                        });
        try {
            assertTrue(
                    concurrent.get(5, TimeUnit.SECONDS),
                    "SKIP LOCKED or unlocked slot recheck cannot cycle");
        } finally {
            finish.countDown();
        }
        assertEquals(
                ConsumptionEngine.Status.COMMITTED, finalizer.get(15, TimeUnit.SECONDS).status());
        discover(req);
        assertEquals(0, count("consumption_slot"));
    }

    @Test
    void l3_identity_windows() {
        var e = key("window");
        executeAdmitted(request(new IdentityRequest.Registration(), e, null, e));
        var identities =
                new JdbcEventDiscoveryReferences(
                        source, ConsumableFamily.IDENTITY_EVENT, discoverySettings);
        var completions =
                new JdbcEventDiscoveryReferences(
                        source, ConsumableFamily.IDENTITY_COMPLETION_EVENT, discoverySettings);
        var first = tx.read(c -> identities.window(0, 1, c));
        assertEquals("POCOMA_USER", first.references().getFirst().subjectType());
        assertEquals(1, first.highInclusive());
        var second = tx.read(c -> identities.window(1, 1, c));
        assertEquals("EXTERNAL_USER_IDENTITY", second.references().getFirst().subjectType());
        assertEquals(2, second.highInclusive());
        assertEquals(
                "REGISTRATION",
                tx.read(c -> completions.window(0, 1, c)).references().getFirst().subjectType());
        var e2 = key("linked");
        executeAdmitted(request(new IdentityRequest.Link(e2), e, current(e).bindingToken(), e2));
        executeAdmitted(request(new IdentityRequest.Unlink(e2), e, current(e).bindingToken(), e2));
        assertEquals(
                List.of("REGISTRATION", "LINK", "UNLINK"),
                tx.read(c -> completions.window(0, 3, c)).references().stream()
                        .map(x -> x.subjectType())
                        .toList());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JdbcEventDiscoveryReferences(
                                source, ConsumableFamily.INVITATION_EVENT, discoverySettings));
    }
}
