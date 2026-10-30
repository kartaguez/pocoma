package com.kartaguez.pocoma.engine.consume.registration.v3;

import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.domain.useridentity.v3.ExternalUserIdentityId;
import com.kartaguez.pocoma.domain.useridentity.v3.IdentityTransitionResult;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Request/result/publication port. All writes participate in the caller's transaction. */
public interface IdentityRequestStore {
    enum Status { PENDING, TERMINAL }
    enum Outcome { CHANGED, REJECTED }

    /** Public-result shape: no token, destination UserId, or copied authority state. */
    record Result(Outcome outcome, ExternalUserIdentityId targetId, Long resultingVersion,
                  IdentityTransitionResult.Rejection rejection) {
        public Result {
            Objects.requireNonNull(outcome, "outcome");
            if (outcome == Outcome.CHANGED
                    ? targetId == null || resultingVersion == null || resultingVersion < 1 || rejection != null
                    : targetId != null || resultingVersion != null || rejection == null)
                throw new IllegalArgumentException("Invalid Identity terminal result");
        }

        public static Result from(IdentityTransitionResult transition) {
            return switch (transition) {
                case IdentityTransitionResult.Changed changed -> new Result(Outcome.CHANGED,
                        changed.identity().id(), changed.identity().version(), null);
                case IdentityTransitionResult.Rejected rejected -> new Result(
                        Outcome.REJECTED, null, null, rejected.reason());
            };
        }
    }

    record Stored(IdentityRequest request, Status status, Optional<Result> terminal) {
        public Stored {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(terminal, "terminal");
            if ((status == Status.TERMINAL) != terminal.isPresent())
                throw new IllegalArgumentException("TERMINAL iff result exists");
        }
    }

    Optional<Stored> load(UUID requestId);

    /**
     * Internal admission: insert immutable PENDING, without slot. Same ID + exact same intention
     * returns the original stored request/result; same ID + different intention must fail.
     */
    Stored insertPending(IdentityRequest request);

    /** One-way terminalization, atomic with effects and publications; never replace a terminal. */
    void finishTerminal(IdentityRequest request, Result result);

    /** Verify actual durable result/effects/provenance against this transition before slot DELETE. */
    void requireCompletionReady(IdentityRequest request, IdentityTransitionResult transition);

    /**
     * Publish exactly one completion and mutations for this transition (User v1 then EUI for
     * Registration; EUI only for Link/Unlink; no mutations for rejection). Envelopes contain only
     * references/provenance, no tokens or copied states. Flush and force business constraints,
     * close publication, then assign dense IDENTITY -> IDENTITY_COMPLETION positions as the final
     * suffix. No business write or lock after closure. No transaction ownership or slot creation.
     */
    void publish(IdentityRequest request, IdentityTransitionResult transition);
}
