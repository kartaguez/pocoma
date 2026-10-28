package com.kartaguez.pocoma.engine.consumption.v3.port;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.util.*;

/** Global PENDING navigation precedes partition filtering. No durable cursor or claims. */
public interface BurnableReferences {
    ConsumableFamily family();

    Optional<UUID> upperPending(RecoveryReadContext read);

    List<UUID> pendingPage(
            Optional<UUID> afterExclusive,
            UUID upperInclusive,
            int pageSize,
            RecoveryReadContext read);

    /** Same RC effects unit as the following existence read and INSERT; retains source lock. */
    boolean tryProtectPending(UUID requestId);
}
