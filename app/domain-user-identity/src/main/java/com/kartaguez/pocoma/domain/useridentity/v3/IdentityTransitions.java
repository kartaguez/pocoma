package com.kartaguez.pocoma.domain.useridentity.v3;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.User;
import com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionResult.Changed;
import com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionResult.Rejected;

import java.util.Objects;
import java.util.Optional;

import static com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionResult.Rejection.*;

/**
 * Pure decisions on a consistent authority snapshot. The caller retains protection of all keys
 * through commit. Candidates are supplied, never generated here: reservedId is the authority's
 * stable ID, newUserId is unused and freshToken is globally unused in the retained token history.
 * Violations of these infrastructure preconditions are faults, not business rejections.
 */
public final class IdentityTransitions {
    private IdentityTransitions() {}

    public static IdentityTransitionResult registration(
            ExternalIdentity requester, Optional<ExternalUserIdentity> current,
            ExternalUserIdentityId reservedId, PocomaUserId newUserId, BindingToken freshToken) {
        checkState(requester, current);
        checkReservedId(current, reservedId);
        Objects.requireNonNull(newUserId, "newUserId");
        Objects.requireNonNull(freshToken, "freshToken");
        if (current.filter(ExternalUserIdentity::isBound).isPresent())
            return new Rejected(TARGET_ALREADY_BOUND);
        return new Changed(Optional.of(new User(newUserId)),
                new ExternalUserIdentity(reservedId, requester, nextVersion(current), newUserId, freshToken));
    }

    /** U is resolved exclusively from the authenticated initiator and its active token. */
    public static IdentityTransitionResult link(
            ExternalIdentity requester, BindingToken presentedToken, Optional<ExternalUserIdentity> initiator,
            ExternalIdentity target, Optional<ExternalUserIdentity> current,
            ExternalUserIdentityId reservedId, BindingToken freshToken) {
        checkPair(requester, initiator, target, current);
        checkReservedId(current, reservedId);
        if (!requester.equals(target) && initiator.isPresent() && initiator.get().id().equals(reservedId))
            throw new IllegalArgumentException("Distinct external keys cannot share an EUI ID");
        Objects.requireNonNull(presentedToken, "presentedToken");
        Objects.requireNonNull(freshToken, "freshToken");
        if (!authorized(initiator, presentedToken)) return new Rejected(INVALID_BINDING);
        if (current.filter(ExternalUserIdentity::isBound).isPresent())
            return new Rejected(TARGET_ALREADY_BOUND);
        if (freshToken.equals(initiator.orElseThrow().bindingToken()))
            throw new IllegalArgumentException("Candidate token is already in use");
        return new Changed(Optional.empty(), new ExternalUserIdentity(
                reservedId, target, nextVersion(current), initiator.orElseThrow().userId(), freshToken));
    }

    /** No target token is needed; self-Unlink and last-Unlink preserve the autonomous User. */
    public static IdentityTransitionResult unlink(
            ExternalIdentity requester, BindingToken presentedToken, Optional<ExternalUserIdentity> initiator,
            ExternalIdentity target, Optional<ExternalUserIdentity> current) {
        checkPair(requester, initiator, target, current);
        Objects.requireNonNull(presentedToken, "presentedToken");
        if (!authorized(initiator, presentedToken)) return new Rejected(INVALID_BINDING);
        if (current.isEmpty() || !current.get().isBound()) return new Rejected(TARGET_NOT_BOUND);
        if (!initiator.orElseThrow().userId().equals(current.get().userId()))
            return new Rejected(TARGET_BOUND_TO_ANOTHER_USER);
        return new Changed(Optional.empty(), new ExternalUserIdentity(
                current.get().id(), target, nextVersion(current), null, null));
    }

    private static boolean authorized(Optional<ExternalUserIdentity> initiator, BindingToken token) {
        return initiator.filter(ExternalUserIdentity::isBound)
                .filter(state -> state.bindingToken().equals(token)).isPresent();
    }

    private static long nextVersion(Optional<ExternalUserIdentity> state) {
        return state.isEmpty() ? 1 : Math.incrementExact(state.get().version());
    }

    private static void checkReservedId(Optional<ExternalUserIdentity> state, ExternalUserIdentityId id) {
        Objects.requireNonNull(id, "reservedId");
        if (state.isPresent() && !state.get().id().equals(id))
            throw new IllegalArgumentException("Reserved ID differs from existing EUI ID");
    }

    private static void checkState(ExternalIdentity key, Optional<ExternalUserIdentity> state) {
        ExternalUserIdentity.compareKeys(key, key);
        Objects.requireNonNull(state, "state");
        if (state.isPresent() && !state.get().identity().equals(key))
            throw new IllegalArgumentException("State belongs to another external identity");
    }

    private static void checkPair(ExternalIdentity requester, Optional<ExternalUserIdentity> initiator,
                                  ExternalIdentity target, Optional<ExternalUserIdentity> current) {
        checkState(requester, initiator);
        checkState(target, current);
        if (requester.equals(target) && !initiator.equals(current))
            throw new IllegalArgumentException("One external key must have one current state");
        if (!requester.equals(target) && initiator.isPresent() && current.isPresent()
                && initiator.get().id().equals(current.get().id()))
            throw new IllegalArgumentException("Distinct external keys cannot share an EUI ID");
    }
}
