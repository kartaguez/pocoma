package com.kartaguez.pocoma.engine.consume.registration.v3;

import com.kartaguez.pocoma.engine.consumption.v3.RecoveryObservation;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.util.UUID;

/**
 * R9 business evidence in the Engine's one active read snapshot (requireActiveRead mandatory).
 * Compare immutable request, terminal result, exact request-owned User/EUI versions, events and
 * positions. COMPLETED means coherent terminal facts; NOT_COMPLETED means PENDING without effects;
 * partial/contradictory facts mean INCONSISTENT; inability to read means UNAVAILABLE.
 * The Engine combines this with slot evidence. Never infer rollback from time or repair facts.
 */
public interface IdentityRequestObservation {
    RecoveryObservation observe(UUID requestId, RecoveryReadContext context);
}
