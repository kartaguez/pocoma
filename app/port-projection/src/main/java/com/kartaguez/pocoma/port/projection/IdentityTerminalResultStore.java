package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.IdentityTerminalResult;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.Optional;
import java.util.UUID;

/** Caller-owned READ transaction required for lookup as well as effects. */
public interface IdentityTerminalResultStore {
    boolean insertOrVerify(IdentityTerminalResult value);

    void requireCompletionReady(IdentityTerminalResult value, boolean inserted);

    Optional<IdentityTerminalResult> find(UUID requestId);

    ReadEvidence evidence(IdentityTerminalResult expected, RecoveryReadContext context);

    void closePublication(UUID requestId, boolean inserted);
}
