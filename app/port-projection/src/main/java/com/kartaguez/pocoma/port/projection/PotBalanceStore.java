package com.kartaguez.pocoma.port.projection;
import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import java.util.*;
public interface PotBalanceStore {
    void insertOrVerify(PotBalance balance);
    void requireStored(UUID pot,long version,Optional<PotBalance> expected);
    Optional<PotBalance> read(UUID pot,long version,RecoveryReadContext context);
    ReadEvidence evidence(UUID pot,long version,Optional<PotBalance> expected,RecoveryReadContext context);
    Optional<PotBalanceAggregate> readLatestAggregateBefore(
            UUID pot, long beforeVersionExclusive, long minVersionInclusive, RecoveryReadContext context);
    void insertOrVerifyAggregate(PotBalanceAggregate aggregate);
    void requireAggregateStored(PotBalanceAggregate expected);
    /** Close the existing READ publication after L2 complete, without committing. */
    void closePublication();
}
