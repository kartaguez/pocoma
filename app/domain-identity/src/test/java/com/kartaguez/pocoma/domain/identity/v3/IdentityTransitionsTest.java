package com.kartaguez.pocoma.domain.identity.v3;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.PocomaUserId;
import com.kartaguez.pocoma.domain.identity.User;
import com.kartaguez.pocoma.domain.identity.v3.IdentityTransitionResult.Changed;
import com.kartaguez.pocoma.domain.identity.v3.IdentityTransitionResult.Rejected;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static com.kartaguez.pocoma.domain.identity.v3.IdentityTransitionResult.Rejection.*;
import static org.junit.jupiter.api.Assertions.*;

class IdentityTransitionsTest {
    private static final ExternalIdentity E1 = new ExternalIdentity("issuer", "one");
    private static final ExternalIdentity E2 = new ExternalIdentity("issuer", "two");
    private static final ExternalUserIdentityId ID1 = id(1), ID2 = id(2);
    private static final PocomaUserId U1 = user(1), U2 = user(2);
    private static final BindingToken A = new BindingToken("A"), B = new BindingToken("B");
    private static final ExternalUserIdentity INITIATOR = state(ID1, E1, 7, U1, A);

    @Test
    void registrationOfAbsentIdentityCreatesUserAndDirectlyBoundVersionOne() {
        var changed = changed(IdentityTransitions.registration(E2, Optional.empty(), ID2, U2, B));
        assertEquals(Optional.of(new User(U2)), changed.createdUser());
        assertEquals(state(ID2, E2, 1, U2, B), changed.identity());
    }

    @Test
    void registrationOfFreeIdentityKeepsIdAndCreatesExactSuccessorAndNewUser() {
        var free = state(ID2, E2, 19, null, null);
        var changed = changed(IdentityTransitions.registration(E2, Optional.of(free), ID2, U2, B));
        assertEquals(state(ID2, E2, 20, U2, B), changed.identity());
        assertEquals(Optional.of(new User(U2)), changed.createdUser());
        assertFalse(free.isBound());
        assertEquals(19, free.version());
    }

    @Test
    void registrationRejectsBoundIdentityWithoutAUserOrStateChange() {
        assertEquals(new Rejected(TARGET_ALREADY_BOUND), IdentityTransitions.registration(
                E1, Optional.of(INITIATOR), ID1, U2, B));
        assertEquals(state(ID1, E1, 7, U1, A), INITIATOR);
    }

    @Test
    void absentFreeOrStaleInitiatorCannotUnlink() {
        var free = state(ID1, E1, 8, null, null);
        for (var initiator : java.util.List.of(Optional.<ExternalUserIdentity>empty(), Optional.of(free))) {
            assertEquals(new Rejected(INVALID_BINDING), unlink(initiator, A, Optional.empty()));
        }
        for (var invalid : java.util.List.of(B, new BindingToken("invented"))) {
            var boundTarget = state(ID2, E2, 3, U1, B);
            assertEquals(new Rejected(INVALID_BINDING), unlink(Optional.of(INITIATOR), invalid, Optional.of(boundTarget)));
        }
    }

    @Test
    void unlinkOtherIdentityOfSameUserDoesNotRequireItsToken() {
        var bound = state(ID2, E2, 42, U1, B);
        var result = changed(unlink(Optional.of(INITIATOR), A, Optional.of(bound)));
        assertEquals(state(ID2, E2, 43, null, null), result.identity());
        assertEquals(Optional.empty(), result.createdUser());
        assertEquals(B, bound.bindingToken());
    }

    @Test
    void selfAndLastUnlinkOnlyChangeEuiAndNeverDeleteUser() {
        var result = changed(IdentityTransitions.unlink(E1, A, Optional.of(INITIATOR), E1, Optional.of(INITIATOR)));
        assertEquals(state(ID1, E1, 8, null, null), result.identity());
        assertTrue(result.createdUser().isEmpty());
        assertEquals(U1, new User(U1).id());
    }

    @Test
    void unlinkRejectsAbsentFreeAndAnotherUsersTarget() {
        assertEquals(new Rejected(TARGET_NOT_BOUND), unlink(Optional.of(INITIATOR), A, Optional.empty()));
        assertEquals(new Rejected(TARGET_NOT_BOUND), unlink(Optional.of(INITIATOR), A,
                Optional.of(state(ID2, E2, 4, null, null))));
        assertEquals(new Rejected(TARGET_BOUND_TO_ANOTHER_USER), unlink(Optional.of(INITIATOR), A,
                Optional.of(state(ID2, E2, 4, U2, B))));
    }

    @Test
    void registrationAfterHistoricalUnlinkPreservesEarlierStatesAndInvalidatesOldToken() {
        var v1 = state(ID2, E2, 1, U1, B);
        var v2 = changed(unlink(Optional.of(INITIATOR), A, Optional.of(v1))).identity();
        var fresh = new BindingToken("C");
        var v3 = changed(IdentityTransitions.registration(E2, Optional.of(v2), ID2, U2, fresh)).identity();
        assertEquals(java.util.List.of(
                state(ID2, E2, 1, U1, B), state(ID2, E2, 2, null, null),
                state(ID2, E2, 3, U2, fresh)), java.util.List.of(v1, v2, v3));
        assertEquals(new Rejected(INVALID_BINDING), IdentityTransitions.unlink(
                E2, B, Optional.of(v3), E2, Optional.of(v3)));
    }

    @Test
    void crossedUnlinksLoseInitiatorAuthorityAfterFirstSuccess() {
        var second = state(ID2, E2, 1, U1, B);
        var freed = changed(unlink(Optional.of(INITIATOR), A, Optional.of(second))).identity();
        assertEquals(new Rejected(INVALID_BINDING), IdentityTransitions.unlink(
                E2, B, Optional.of(freed), E1, Optional.of(INITIATOR)));
    }

    @Test
    void inconsistentSnapshotsAndIdsAreFaults() {
        assertThrows(IllegalArgumentException.class, () -> IdentityTransitions.registration(
                E2, Optional.of(INITIATOR), ID2, U2, B));
        assertThrows(IllegalArgumentException.class, () -> IdentityTransitions.registration(
                E1, Optional.of(INITIATOR), ID2, U2, B));
        assertThrows(IllegalArgumentException.class, () -> IdentityTransitions.unlink(
                E1, A, Optional.of(INITIATOR), E1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> unlink(Optional.of(INITIATOR), A,
                Optional.of(state(ID1, E2, 1, null, null))));
    }

    @Test
    void versionOverflowIsAFaultAndNeverWrapsToZeroOrNegative() {
        var free = state(ID2, E2, Long.MAX_VALUE, null, null);
        assertThrows(ArithmeticException.class, () -> IdentityTransitions.registration(
                E2, Optional.of(free), ID2, U2, B));
        assertThrows(ArithmeticException.class, () -> unlink(Optional.of(INITIATOR), A,
                Optional.of(state(ID2, E2, Long.MAX_VALUE, U1, B))));
    }

    @Test
    void requiredCandidatesAndSnapshotsCannotBeNull() {
        assertThrows(NullPointerException.class, () -> IdentityTransitions.registration(E2, null, ID2, U2, B));
        assertThrows(NullPointerException.class, () -> IdentityTransitions.registration(E2, Optional.empty(), null, U2, B));
        assertThrows(NullPointerException.class, () -> IdentityTransitions.registration(E2, Optional.empty(), ID2, null, B));
        assertThrows(NullPointerException.class, () -> IdentityTransitions.registration(E2, Optional.empty(), ID2, U2, null));
        assertThrows(NullPointerException.class, () -> unlink(Optional.of(INITIATOR), null, Optional.empty()));
    }

    private static IdentityTransitionResult unlink(Optional<ExternalUserIdentity> initiator,
                                                   BindingToken token, Optional<ExternalUserIdentity> current) {
        return IdentityTransitions.unlink(E1, token, initiator, E2, current);
    }

    private static Changed changed(IdentityTransitionResult result) {
        return assertInstanceOf(Changed.class, result);
    }

    private static ExternalUserIdentity state(ExternalUserIdentityId id, ExternalIdentity key, long version,
                                              PocomaUserId user, BindingToken token) {
        return new ExternalUserIdentity(id, key, version, user, token);
    }

    private static ExternalUserIdentityId id(long n) { return new ExternalUserIdentityId(new UUID(0, n)); }
    private static PocomaUserId user(long n) { return new PocomaUserId(new UUID(1, n)); }
}
