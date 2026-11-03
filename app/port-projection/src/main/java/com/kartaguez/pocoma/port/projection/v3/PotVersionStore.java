package com.kartaguez.pocoma.port.projection.v3;

import com.kartaguez.pocoma.domain.projection.v3.PotVersion;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.util.*;

public interface PotVersionStore {
    void insertAndAdvance(PotVersion value);

    void requireStored(PotVersion value);

    OptionalLong currentVersion(UUID pot, RecoveryReadContext context);

    Optional<PotVersion> read(UUID pot, long version, RecoveryReadContext context);

    ReadEvidence evidence(PotVersion expected, RecoveryReadContext context);

    /** Close the existing READ publication after L2 complete, without committing. */
    void closePublication();
}
