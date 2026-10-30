package com.kartaguez.pocoma.engine.consume.registration.v3;

import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest.*;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.RecoveryObservation;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static com.kartaguez.pocoma.engine.consume.registration.v3.IdentityRequestStore.*;
import static org.junit.jupiter.api.Assertions.*;

class IdentityRequestContractTest {
    private static final UUID REQUEST_ID = new UUID(0, 1);
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final ExternalIdentity E1 = new ExternalIdentity("issuer", "one");
    private static final ExternalIdentity E2 = new ExternalIdentity("issuer", "two");
    private static final BindingToken TOKEN = new BindingToken("initiator-secret");
    private static final ExternalUserIdentityId ID = new ExternalUserIdentityId(new UUID(1, 1));

    @Test
    void registrationTargetsOnlyItsAuthenticatedRequesterAndOmitsToken() {
        var request = request(new Registration(), null);
        assertEquals(E1, request.requesterEui());
        assertEquals(E1, request.target());
        assertNull(request.bindingToken());
        assertEquals(REQUEST_ID, request.requestId());
        assertEquals(NOW, request.createdAt());
        assertThrows(IllegalArgumentException.class, () -> request(new Registration(), TOKEN));
    }

    @Test
    void linkAndUnlinkRequireInitiatorTokenAndAnExactTargetIncludingSelf() {
        for (Operation operation : List.of(new Link(E2), new Unlink(E2), new Link(E1), new Unlink(E1))) {
            var request = request(operation, TOKEN);
            assertEquals(TOKEN, request.bindingToken());
            assertEquals(E1, request.requesterEui());
            assertEquals(target(operation), request.target());
            assertThrows(IllegalArgumentException.class, () -> request(operation, null));
        }
        assertThrows(NullPointerException.class, () -> new Link(null));
        assertThrows(NullPointerException.class, () -> new Unlink(null));
    }

    @Test
    void externalUnicodeRemainsExactAndUnstorableKeysFailBeforeAnyAdapter() {
        var unicode = new ExternalIdentity("é", "😀e\u0301");
        var operation = new Link(unicode);
        var request = new IdentityRequest(REQUEST_ID, E1, operation, TOKEN, NOW,
                context(E1, unicode, Map.of()));
        assertSame(unicode, request.target());
        assertNotEquals(new ExternalIdentity("e\u0301", "😀é"), request.target());
        for (var invalid : List.of(new ExternalIdentity("a\0b", "s"),
                new ExternalIdentity("i", "\ud800"), new ExternalIdentity("\udc00", "s"))) {
            assertThrows(IllegalArgumentException.class, () -> new Link(invalid));
            assertThrows(IllegalArgumentException.class, () -> new Unlink(invalid));
            assertThrows(IllegalArgumentException.class, () -> context(invalid, E2, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> new IdentityRequest(
                    REQUEST_ID, invalid, new Registration(), null, NOW, context(E1, E1, Map.of())));
        }
    }

    @Test
    void immutableAdmissionCapturesProvenanceAndMustMatchBothIdentities() {
        var mutable = new HashMap<String, String>();
        mutable.put("proof", "provider-secret");
        var context = context(E1, E2, mutable);
        var request = new IdentityRequest(REQUEST_ID, E1, new Link(E2), TOKEN, NOW, context);
        mutable.put("proof", "substitution");
        assertEquals(Map.of("proof", "provider-secret"), request.admission().attestations());
        assertThrows(UnsupportedOperationException.class, () -> context.attestations().put("x", "y"));
        assertEquals("validator-v1", context.validator());
        assertEquals("contract-v1", context.contractVersion());
        assertEquals("canonical-intention-fingerprint", context.intentionFingerprint());
        assertEquals("grant-secret", context.grantOrCorrelationReference());
        assertEquals(NOW, context.validatedAt());
        assertEquals(Origin.CONTROLLED_INTERNAL, context.origin());
        assertThrows(IllegalArgumentException.class, () -> new IdentityRequest(
                REQUEST_ID, E1, new Link(E2), TOKEN, NOW, context(E2, E2, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> new IdentityRequest(
                REQUEST_ID, E1, new Link(E2), TOKEN, NOW, context(E1, E1, Map.of())));
        assertThrows(IllegalArgumentException.class, () -> new IdentityRequest(
                REQUEST_ID, E1, new Registration(), null, NOW, context));
    }

    @Test
    void missingIntentionAndProvenanceAreInvalid() {
        var context = context(E1, E1, Map.of());
        assertThrows(NullPointerException.class, () -> new IdentityRequest(null, E1, new Registration(), null, NOW, context));
        assertThrows(NullPointerException.class, () -> new IdentityRequest(REQUEST_ID, null, new Registration(), null, NOW, context));
        assertThrows(NullPointerException.class, () -> new IdentityRequest(REQUEST_ID, E1, null, null, NOW, context));
        assertThrows(NullPointerException.class, () -> new IdentityRequest(REQUEST_ID, E1, new Registration(), null, null, context));
        assertThrows(NullPointerException.class, () -> new IdentityRequest(REQUEST_ID, E1, new Registration(), null, NOW, null));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionContext(" ", "v1", E1, E1,
                "hash", "correlation", NOW, Map.of(), Origin.CONTROLLED_INTERNAL));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionContext("validator", " ", E1, E1,
                "hash", "correlation", NOW, Map.of(), Origin.CONTROLLED_INTERNAL));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionContext("validator", "v1", E1, E1,
                " ", "correlation", NOW, Map.of(), Origin.CONTROLLED_INTERNAL));
        assertThrows(IllegalArgumentException.class, () -> new AdmissionContext("validator", "v1", E1, E1,
                "hash", " ", NOW, Map.of(), Origin.CONTROLLED_INTERNAL));
        assertThrows(NullPointerException.class, () -> context(E1, E1, null));
        assertThrows(NullPointerException.class, () -> new AdmissionContext("validator", "v1", E1, E1,
                "hash", "correlation", null, Map.of(), Origin.CONTROLLED_INTERNAL));
        assertThrows(NullPointerException.class, () -> new AdmissionContext("validator", "v1", E1, E1,
                "hash", "correlation", NOW, Map.of(), null));
    }

    @Test
    void exactIntentionEqualityIncludesOperationTokenTargetAndAdmission() {
        var request = request(new Link(E2), TOKEN);
        assertEquals(request, request(new Link(new ExternalIdentity("issuer", "two")), new BindingToken(TOKEN.value())));
        assertNotEquals(request, request(new Unlink(E2), TOKEN));
        assertNotEquals(request, request(new Link(E1), TOKEN));
        assertNotEquals(request, request(new Link(E2), new BindingToken("different")));
        assertNotEquals(request, new IdentityRequest(REQUEST_ID, E1, new Link(E2), TOKEN, NOW,
                context(E1, E2, Map.of("proof", "different"))));
        assertFalse(request.toString().contains(TOKEN.value()));
        assertFalse(request.toString().contains("grant-secret"));
        assertFalse(request.admission().toString().contains("grant-secret"));
    }

    @Test
    void publicResultExposesOnlyChangedTargetAndVersionOrClosedRejection() {
        var change = IdentityTransitions.registration(E1, Optional.empty(), ID,
                new PocomaUserId(new UUID(2, 1)), new BindingToken("new-secret"));
        var result = Result.from(change);
        assertEquals(new Result(Outcome.CHANGED, ID, 1L, null), result);
        assertFalse(result.toString().contains("new-secret"));
        for (var reason : IdentityTransitionResult.Rejection.values())
            assertEquals(new Result(Outcome.REJECTED, null, null, reason),
                    Result.from(new IdentityTransitionResult.Rejected(reason)));
        assertThrows(IllegalArgumentException.class, () -> new Result(Outcome.CHANGED, ID, 0L, null));
        assertThrows(IllegalArgumentException.class, () -> new Result(Outcome.CHANGED, null, 1L, null));
        assertThrows(IllegalArgumentException.class, () -> new Result(Outcome.CHANGED, ID, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Result(Outcome.CHANGED, ID, 1L,
                IdentityTransitionResult.Rejection.INVALID_BINDING));
        assertThrows(IllegalArgumentException.class, () -> new Result(Outcome.REJECTED, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Result(Outcome.REJECTED, ID, null,
                IdentityTransitionResult.Rejection.INVALID_BINDING));
        assertThrows(IllegalArgumentException.class, () -> new Result(Outcome.REJECTED, null, 1L,
                IdentityTransitionResult.Rejection.INVALID_BINDING));
    }

    @Test
    void storedRequestDistinguishesPendingAndTerminalAndRequiresExactResultPresence() {
        var request = request(new Registration(), null);
        var rejection = new Result(Outcome.REJECTED, null, null, IdentityTransitionResult.Rejection.TARGET_ALREADY_BOUND);
        assertEquals(Status.PENDING, new Stored(request, Status.PENDING, Optional.empty()).status());
        assertEquals(Optional.of(rejection), new Stored(request, Status.TERMINAL, Optional.of(rejection)).terminal());
        assertThrows(IllegalArgumentException.class, () -> new Stored(request, Status.PENDING, Optional.of(rejection)));
        assertThrows(IllegalArgumentException.class, () -> new Stored(request, Status.TERMINAL, Optional.empty()));
        assertThrows(NullPointerException.class, () -> new Stored(request, Status.PENDING, null));
    }

    @Test
    void clientIntentionCannotChooseUserAndResultCannotDiscloseTokenOrUser() {
        for (Class<?> type : List.of(IdentityRequest.class, Registration.class, Link.class,
                Unlink.class, AdmissionContext.class, Result.class)) {
            for (var component : type.getRecordComponents()) {
                assertNotEquals(PocomaUserId.class, component.getType(), type.getName());
                if (type == Result.class) assertNotEquals(BindingToken.class, component.getType());
            }
        }
        assertTrue(Operation.class.isSealed());
        assertEquals(Set.of(Registration.class, Link.class, Unlink.class), Set.of(Operation.class.getPermittedSubclasses()));
    }

    @Test
    void observationRequiresEngineReadSnapshotAndReturnsExistingRecoveryVocabulary() throws Exception {
        var method = IdentityRequestObservation.class.getMethod("observe", UUID.class, RecoveryReadContext.class);
        assertEquals(RecoveryObservation.class, method.getReturnType());
        assertTrue(IdentityAuthority.class.isInterface());
        assertTrue(IdentityRequestStore.class.isInterface());
    }

    private static IdentityRequest request(Operation operation, BindingToken token) {
        return new IdentityRequest(REQUEST_ID, E1, operation, token, NOW, context(E1, target(operation), Map.of()));
    }

    private static ExternalIdentity target(Operation operation) {
        return switch (operation) {
            case Registration ignored -> E1;
            case Link link -> link.target();
            case Unlink unlink -> unlink.target();
        };
    }

    private static AdmissionContext context(ExternalIdentity initiator, ExternalIdentity target,
                                             Map<String, String> attestations) {
        return new AdmissionContext("validator-v1", "contract-v1", initiator, target,
                "canonical-intention-fingerprint", "grant-secret", NOW, attestations, Origin.CONTROLLED_INTERNAL);
    }
}
