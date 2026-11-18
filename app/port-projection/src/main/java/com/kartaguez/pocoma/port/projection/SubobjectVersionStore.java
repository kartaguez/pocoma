package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.*;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** Immutable READ storage, participating exclusively in the caller's L2 owner. */
public interface SubobjectVersionStore {
    Map<SubobjectKey, SubobjectVersion> readAll(Collection<SubobjectKey> keys, RecoveryReadContext context);
    Set<SubobjectKey> existingKeys(Collection<SubobjectKey> keys, RecoveryReadContext context);
    void insertOrVerifyAll(Collection<SubobjectVersion> candidates);
    void requireStored(Collection<SubobjectVersion> expected);
    void requireKeysPresent(Collection<SubobjectKey> expectedKeys);
    ReadEvidence evidence(Collection<SubobjectVersion> expected, RecoveryReadContext context);
}
