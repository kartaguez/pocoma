package com.kartaguez.pocoma.domain.useridentity.v3;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.User;
import com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionResult.Changed;
import com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionResult.Rejected;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.UUID;

import static com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionResult.Rejection.*;
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
    void linkAbsentTargetDeducesUserFromInitiatorWithoutCreatingUser() {
        var result = changed(link(Optional.of(INITIATOR), A, Optional.empty()));
        assertEquals(Optional.empty(), result.createdUser());
        assertEquals(state(ID2, E2, 1, U1, B), result.identity());
        assertEquals(7, INITIATOR.version());
    }

    @Test
    void linkFreeTargetRetainsIdAndExactSuccessor() {
        var free = state(ID2, E2, 12, null, null);
        assertEquals(state(ID2, E2, 13, U1, B),
                changed(link(Optional.of(INITIATOR), A, Optional.of(free))).identity());
        assertEquals(state(ID2, E2, 12, null, null), free);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void linkRejectsAlreadyBoundTargetEvenToSameUser(boolean sameUser) {
        var bound = state(ID2, E2, 3, sameUser ? U1 : U2, B);
        assertEquals(new Rejected(TARGET_ALREADY_BOUND), link(Optional.of(INITIATOR), A, Optional.of(bound)));
    }

    @Test
    void selfLinkIsStrictlyRejected() {
        assertEquals(new Rejected(TARGET_ALREADY_BOUND), IdentityTransitions.link(
                E1, A, Optional.of(INITIATOR), E1, Optional.of(INITIATOR), ID1, B));
    }

    @Test
    void absentFreeOrStaleInitiatorCannotLinkOrUnlink() {
        var free = state(ID1, E1, 8, null, null);
        for (var initiator : java.util.List.of(Optional.<ExternalUserIdentity>empty(), Optional.of(free))) {
            assertEquals(new Rejected(INVALID_BINDING), link(initiator, A, Optional.empty()));
            assertEquals(new Rejected(INVALID_BINDING), unlink(initiator, A, Optional.empty()));
        }
        for (var invalid : java.util.List.of(B, new BindingToken("invented"))) {
            var boundTarget = state(ID2, E2, 3, U1, B);
            assertEquals(new Rejected(INVALID_BINDING), link(Optional.of(INITIATOR), invalid, Optional.empty()));
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
    void localHistoryOneThroughFivePreservesOldStatesAndRenewsTokensEvenForSameUser() {
        var other = state(id(3), new ExternalIdentity("other", "initiator"), 1, U2, new BindingToken("X"));
        var v1 = changed(link(Optional.of(INITIATOR), A, Optional.empty())).identity();
        var v2 = changed(unlink(Optional.of(INITIATOR), A, Optional.of(v1))).identity();
        var v3 = changed(IdentityTransitions.link(other.identity(), other.bindingToken(), Optional.of(other),
                E2, Optional.of(v2), ID2, new BindingToken("C"))).identity();
        var v4 = changed(IdentityTransitions.unlink(other.identity(), other.bindingToken(), Optional.of(other),
                E2, Optional.of(v3))).identity();
        var v5 = changed(IdentityTransitions.link(E1, A, Optional.of(INITIATOR), E2, Optional.of(v4),
                ID2, new BindingToken("D"))).identity();
        assertEquals(java.util.List.of(
                state(ID2, E2, 1, U1, B), state(ID2, E2, 2, null, null),
                state(ID2, E2, 3, U2, new BindingToken("C")), state(ID2, E2, 4, null, null),
                state(ID2, E2, 5, U1, new BindingToken("D"))), java.util.List.of(v1, v2, v3, v4, v5));
        assertEquals(new Rejected(INVALID_BINDING), IdentityTransitions.unlink(
                E2, B, Optional.of(v5), E2, Optional.of(v5)));
    }

    @Test
    void crossedUnlinksLoseInitiatorAuthorityAfterFirstSuccess() {
        var second = state(ID2, E2, 1, U1, B);
        var freed = changed(unlink(Optional.of(INITIATOR), A, Optional.of(second))).identity();
        assertEquals(new Rejected(INVALID_BINDING), IdentityTransitions.unlink(
                E2, B, Optional.of(freed), E1, Optional.of(INITIATOR)));
    }

    @Test
    void inconsistentSnapshotsIdsAndKnownUsedCandidateTokenAreFaults() {
        assertThrows(IllegalArgumentException.class, () -> IdentityTransitions.registration(
                E2, Optional.of(INITIATOR), ID2, U2, B));
        assertThrows(IllegalArgumentException.class, () -> IdentityTransitions.registration(
                E1, Optional.of(INITIATOR), ID2, U2, B));
        assertThrows(IllegalArgumentException.class, () -> IdentityTransitions.link(
                E1, A, Optional.of(INITIATOR), E1, Optional.empty(), ID1, B));
        assertThrows(IllegalArgumentException.class, () -> link(Optional.of(INITIATOR), A,
                Optional.of(state(ID1, E2, 1, null, null))));
        assertThrows(IllegalArgumentException.class, () -> IdentityTransitions.link(
                E1, A, Optional.of(INITIATOR), E2, Optional.empty(), ID1, B));
        assertThrows(IllegalArgumentException.class, () -> IdentityTransitions.link(
                E1, A, Optional.of(INITIATOR), E2, Optional.empty(), ID2, A));
    }

    @Test
    void versionOverflowIsAFaultAndNeverWrapsToZeroOrNegative() {
        var free = state(ID2, E2, Long.MAX_VALUE, null, null);
        assertThrows(ArithmeticException.class, () -> link(Optional.of(INITIATOR), A, Optional.of(free)));
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
        assertThrows(NullPointerException.class, () -> link(Optional.of(INITIATOR), null, Optional.empty()));
        assertThrows(NullPointerException.class, () -> unlink(Optional.of(INITIATOR), null, Optional.empty()));
    }

    private static IdentityTransitionResult link(Optional<ExternalUserIdentity> initiator,
                                                 BindingToken token, Optional<ExternalUserIdentity> current) {
        return IdentityTransitions.link(E1, token, initiator, E2, current, ID2, B);
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
