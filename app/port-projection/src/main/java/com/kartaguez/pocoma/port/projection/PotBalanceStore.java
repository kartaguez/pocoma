package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface PotBalanceStore {
    record AggregateRef(long version, ContentHash balanceHash, ContentHash aggregateHash) {
        public AggregateRef {
            if (version < 1) {
                throw new IllegalArgumentException("Positive version required");
            }
            Objects.requireNonNull(balanceHash, "balanceHash");
            Objects.requireNonNull(aggregateHash, "aggregateHash");
        }
    }

    enum ContentWriteOutcome {
        INSERTED,
        REUSED
    }

    record WriteResult(ContentWriteOutcome outcome, Duration encodeDuration) {
        public WriteResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(encodeDuration, "encodeDuration");
        }
    }

    void insertOrVerify(PotBalance balance);

    void requireStored(UUID pot, long version, Optional<PotBalance> expected);

    Optional<PotBalance> read(UUID pot, long version, RecoveryReadContext context);

    ReadEvidence evidence(
            UUID pot, long version, Optional<PotBalance> expected, RecoveryReadContext context);

    Optional<PotBalanceAggregate> readLatestAggregateBefore(
            UUID pot,
            long beforeVersionExclusive,
            long minVersionInclusive,
            RecoveryReadContext context);

    void insertOrVerifyAggregate(PotBalanceAggregate aggregate);

    void requireAggregateStored(PotBalanceAggregate expected);

    /** Close the existing READ publication after L2 complete, without committing. */
    void closePublication();

    default Optional<AggregateRef> readLatestAggregateRefBefore(
            UUID pot,
            long beforeVersionExclusive,
            long minVersionInclusive,
            RecoveryReadContext context) {
        return Optional.empty();
    }

    default Optional<PotBalanceAggregate> readAggregateByHash(
            UUID pot, long version, ContentHash hash, RecoveryReadContext context) {
        return Optional.empty();
    }

    default void linkExistingVersion(
            UUID pot, long version, ContentHash balanceHash, ContentHash aggregateHash) {
        throw new UnsupportedOperationException("linkExistingVersion not supported by this store");
    }

    default void requireVersionHashesStored(
            UUID pot, long version, ContentHash balanceHash, ContentHash aggregateHash) {
        throw new UnsupportedOperationException(
                "requireVersionHashesStored not supported by this store");
    }

    default WriteResult insertOrVerifyWithResult(PotBalance balance) {
        insertOrVerify(balance);
        return new WriteResult(ContentWriteOutcome.INSERTED, Duration.ZERO);
    }

    default WriteResult insertOrVerifyAggregateWithResult(PotBalanceAggregate aggregate) {
        insertOrVerifyAggregate(aggregate);
        return new WriteResult(ContentWriteOutcome.INSERTED, Duration.ZERO);
    }
}
