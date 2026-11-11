package com.kartaguez.pocoma.port.projection.v3;
import com.kartaguez.pocoma.domain.projection.v3.*;

import java.util.*;
/** All operations require the caller's READ attempt; no autonomous transaction or WRITE lookup. */
public interface CommandTerminalResultStore {
    void insertOrVerify(CommandTerminalResult value);
    void requireStored(CommandTerminalResult value);
    Optional<CommandTerminalResult> find(UUID requestId);
    /** Close the existing READ publication after L2 complete, without committing. */
    void closePublication();
}
