package com.kartaguez.pocoma.port.projection.v3;
import com.kartaguez.pocoma.domain.projection.v3.PotBalance;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;
import java.util.*;
public interface PotBalanceStore {
    void insertOrVerify(PotBalance balance);
    void requireStored(UUID pot,long version,Optional<PotBalance> expected);
    Optional<PotBalance> read(UUID pot,long version,RecoveryReadContext context);
    ReadEvidence evidence(UUID pot,long version,Optional<PotBalance> expected,RecoveryReadContext context);
    /** Close the existing READ publication after L2 complete, without committing. */
    void closePublication();
}
