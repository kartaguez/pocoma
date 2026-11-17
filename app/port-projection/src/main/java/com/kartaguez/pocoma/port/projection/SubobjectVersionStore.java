package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.Collection;
import java.util.Map;

/** Immutable READ storage, participating exclusively in the caller's L2 owner. */
public interface SubobjectVersionStore {
    Map<SubobjectKey, SubobjectVersion> readAll(Collection<SubobjectKey> keys, RecoveryReadContext context);
    void insertOrVerifyAll(Collection<SubobjectVersion> candidates);
    void requireStored(Collection<SubobjectVersion> expected);
    ReadEvidence evidence(Collection<SubobjectVersion> expected, RecoveryReadContext context);
}
