package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.ExternalUserIdentity;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.*;
public interface CurrentBindingStore {
    boolean apply(ExternalUserIdentity value);
    void requireCompletionReady(ExternalUserIdentity value, boolean advanced);
    Optional<ExternalUserIdentity> read(ExternalIdentity key,RecoveryReadContext context);
    /** Close the existing READ publication after L2 complete, without committing. */
    void closePublication(ExternalUserIdentity value, boolean advanced);

    ReadEvidence evidence(ExternalUserIdentity incoming, ExternalUserIdentity expected, RecoveryReadContext context);
}
