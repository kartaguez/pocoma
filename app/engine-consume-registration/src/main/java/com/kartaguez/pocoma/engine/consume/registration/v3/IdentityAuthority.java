package com.kartaguez.pocoma.engine.consume.registration.v3;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.domain.useridentity.User;
import com.kartaguez.pocoma.domain.useridentity.v3.ExternalUserIdentity;
import com.kartaguez.pocoma.domain.useridentity.v3.ExternalUserIdentityId;
import com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionResult;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Cohesive authority/history port, using only the caller's physical transaction. */
public interface IdentityAuthority {
    /**
     * Sole acquisition path: validate ALL keys, deduplicate by exact equality, sort with
     * ExternalUserIdentity.compareKeys, then reserve/protect each key in that order.
     * Returns the stable reserved IDs, including for business absence. Missing anchors get
     * internally supplied candidate IDs; a key conflict retains its existing ID. Retain all
     * protections until physical resolution; never expose an anchor at zero as a business state.
     */
    Map<ExternalIdentity, ExternalUserIdentityId> lockInCanonicalOrder(Collection<ExternalIdentity> identities);

    /** Current business state under the already acquired protection; empty for absent/zero anchor. */
    Optional<ExternalUserIdentity> current(ExternalIdentity identity);

    /**
     * Apply exactly these new states/User with request provenance, under existing key protections.
     * No extra acquisition, anchor replacement, User deletion or independent transaction.
     * Enforce unique external key, immutable ID/key/history, exact local successor and globally
     * unused token in retained history. A candidate conflict is a technical failure of the unit.
     */
    void apply(IdentityTransitionResult.Changed changes, UUID requestId);

    /** Exact immutable historical state, never current fallback. Reject version <= 0. */
    Optional<ExternalUserIdentity> at(ExternalUserIdentityId id, long version);

    Optional<User> user(PocomaUserId id);
}
