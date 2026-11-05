package com.kartaguez.pocoma.engine.write.pot.port.execution.v3;

import com.kartaguez.pocoma.domain.pot.v3.Invitation;

import java.util.Optional;

/** Caller-owned WRITE operations; no independent transaction or publication. */
public interface InvitationStore {
    Invitation prepare(PotCommand command, PotCommand.CreateInvitation intent);

    Optional<Invitation> find(String tokenHash);

    /** Fresh database clock, called after Identity and Pot protection, before any effect. */
    boolean unexpired(java.time.Instant expiresAt);

    void insert(Invitation invitation);
}
