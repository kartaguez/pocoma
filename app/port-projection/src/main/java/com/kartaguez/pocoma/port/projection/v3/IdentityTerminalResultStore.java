package com.kartaguez.pocoma.port.projection.v3;
import com.kartaguez.pocoma.domain.projection.v3.*;

import java.util.*;
/** Caller-owned READ transaction required for lookup as well as effects. */
public interface IdentityTerminalResultStore {
    void insertOrVerify(IdentityTerminalResult value);
    void requireStored(IdentityTerminalResult value);
    Optional<IdentityTerminalResult> find(UUID requestId);
    /** Close the existing READ publication after L2 complete, without committing. */
    void closePublication();
}
