package com.kartaguez.pocoma.engine.materialize.pot;

import com.kartaguez.pocoma.domain.pot.invitation.Invitation;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import java.util.UUID;

public interface InvitationSource {
    Invitation load(UUID eventId, RecoveryReadContext context);
}
