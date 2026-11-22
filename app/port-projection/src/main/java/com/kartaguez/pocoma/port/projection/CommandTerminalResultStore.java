package com.kartaguez.pocoma.port.projection;
import com.kartaguez.pocoma.domain.projection.CommandTerminalResult;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import java.util.Optional;
import java.util.UUID;
/** All operations require the caller's READ attempt; no autonomous transaction or WRITE lookup. */
public interface CommandTerminalResultStore {
    /** Reports whether this attempt inserted the logical result. */
    boolean insertOrVerify(CommandTerminalResult value);

    void requireCompletionReady(CommandTerminalResult value, boolean inserted);

    Optional<CommandTerminalResult> find(UUID requestId);

    ReadEvidence evidence(CommandTerminalResult expected, RecoveryReadContext context);

    /** Close and perform the applicable publication suffix after L2 completion, without committing. */
    void closePublication(UUID requestId, boolean inserted);
}
