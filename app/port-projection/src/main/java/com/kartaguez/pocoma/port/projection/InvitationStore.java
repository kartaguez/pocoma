package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.pot.invitation.Invitation;

import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.Optional;
import java.util.UUID;

/** READ-only queries and monotone materialization use their caller's READ transaction. */
public interface InvitationStore {
    Optional<Invitation> byRequest(UUID requestId);

    Optional<Invitation> byHash(String tokenHash);

    boolean put(Invitation expected);

    void requireCompletionReady(Invitation expected, boolean advanced);

    ReadEvidence evidence(Invitation expected, RecoveryReadContext context);

    void closePublication(Invitation expected, boolean advanced);
}
