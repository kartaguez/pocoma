package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.ExternalUserIdentity;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.*;
public interface CurrentBindingStore {
    void apply(ExternalUserIdentity value);
    void requireStored(ExternalUserIdentity value);
    Optional<ExternalUserIdentity> read(ExternalIdentity key,RecoveryReadContext context);
    /** Close the existing READ publication after L2 complete, without committing. */
    void closePublication();
}
