package com.kartaguez.pocoma.port.projection.v3;

import com.kartaguez.pocoma.domain.pot.v3.Invitation;

import java.util.*;

/** READ-only queries and monotone materialization use their caller's READ transaction. */
public interface InvitationStore {
    Optional<Invitation> byRequest(UUID requestId);

    Optional<Invitation> byHash(String tokenHash);

    void put(Invitation expected);

    void closePublication();
}
