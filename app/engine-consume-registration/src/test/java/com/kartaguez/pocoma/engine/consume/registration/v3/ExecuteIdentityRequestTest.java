package com.kartaguez.pocoma.engine.consume.registration.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.*;

class ExecuteIdentityRequestTest {
    final List<String> calls = new ArrayList<>();
    final ExternalIdentity key = new ExternalIdentity("controlled", "actor");
    final IdentityRequest request =
            new IdentityRequest(
                    UUID.randomUUID(),
                    key,
                    new IdentityRequest.Registration(),
                    null,
                    Instant.EPOCH,
                    new IdentityRequest.AdmissionContext(
                            "unit",
                            "1",
                            key,
                            key,
                            "fingerprint",
                            "correlation",
                            Instant.EPOCH,
                            Map.of(),
                            IdentityRequest.Origin.CONTROLLED_INTERNAL));
    IdentityRequestStore.Stored stored =
            new IdentityRequestStore.Stored(
                    request, IdentityRequestStore.Status.PENDING, Optional.empty());
    RuntimeException technical;
    final IdentityRequestStore store =
            (IdentityRequestStore)
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class[] {IdentityRequestStore.class},
                            (p, m, a) -> {
                                calls.add(m.getName());
                                return switch (m.getName()) {
                                    case "load" -> Optional.of(stored);
                                    case "finishTerminal" -> {
                                        stored =
                                                new IdentityRequestStore.Stored(
                                                        request,
                                                        IdentityRequestStore.Status.TERMINAL,
                                                        Optional.of(
                                                                (IdentityRequestStore.Result)
                                                                        a[1]));
                                        yield null;
                                    }
                                    default -> null;
                                };
                            });
    final IdentityAuthority authority =
            (IdentityAuthority)
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class[] {IdentityAuthority.class},
                            (p, m, a) -> {
                                calls.add(m.getName());
                                if (technical != null) throw technical;
                                return switch (m.getName()) {
                                    case "lockInCanonicalOrder" ->
                                            Map.of(key, new ExternalUserIdentityId(new UUID(0, 1)));
                                    case "current" -> Optional.empty();
                                    default -> null;
                                };
                            });

    ExecuteIdentityRequest participant() {
        return new ExecuteIdentityRequest(
                store,
                (id, c) -> RecoveryObservation.NOT_COMPLETED,
                authority,
                () -> new BindingToken("secret"),
                UUID::randomUUID);
    }

    @Test
    void l3_participant_order() {
        var prepared = participant().prepare(request.requestId()).orElseThrow();
        assertEquals(List.of("load"), calls);
        calls.clear();
        prepared.finalizeIn(
                () -> {
                    calls.add("complete");
                    prepared.requireCompletionReady();
                });
        assertEquals(
                List.of(
                        "load",
                        "lockInCanonicalOrder",
                        "current",
                        "apply",
                        "finishTerminal",
                        "complete",
                        "requireCompletionReady",
                        "publish"),
                calls);
        assertEquals(
                IdentityRequestStore.Outcome.CHANGED, stored.terminal().orElseThrow().outcome());
        assertTrue(participant().prepare(request.requestId()).isEmpty());
    }

    @Test
    void l3_technical_not_rejected() {
        technical = new IllegalStateException("technical");
        var prepared = participant().prepare(request.requestId()).orElseThrow();
        assertSame(
                technical,
                assertThrows(
                        IllegalStateException.class,
                        () -> prepared.finalizeIn(() -> fail("must not complete"))));
        assertFalse(calls.contains("finishTerminal"));
        assertFalse(calls.contains("publish"));
    }

    @Test
    void l3_rejection_terminal() {
        var bound =
                new ExternalUserIdentity(
                        new ExternalUserIdentityId(new UUID(0, 1)),
                        key,
                        1,
                        new PocomaUserId(UUID.randomUUID()),
                        new BindingToken("active"));
        IdentityAuthority a =
                (IdentityAuthority)
                        Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {IdentityAuthority.class},
                                (p, m, args) -> {
                                    if (m.getName().equals("lockInCanonicalOrder"))
                                        return Map.of(key, bound.id());
                                    if (m.getName().equals("current")) return Optional.of(bound);
                                    fail("rejection must not apply");
                                    return null;
                                });
        var prepared =
                new ExecuteIdentityRequest(
                                store,
                                (id, c) -> RecoveryObservation.NOT_COMPLETED,
                                a,
                                () -> new BindingToken("fresh"),
                                UUID::randomUUID)
                        .prepare(request.requestId())
                        .orElseThrow();
        prepared.finalizeIn(() -> prepared.requireCompletionReady());
        assertEquals(
                IdentityRequestStore.Outcome.REJECTED, stored.terminal().orElseThrow().outcome());
    }
}
