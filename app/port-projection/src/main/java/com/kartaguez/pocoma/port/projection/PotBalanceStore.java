package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceAggregateBinaryCodec;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceBinaryCodec;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface PotBalanceStore {
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

    record PersistVersionResult(
            ContentHash balanceHash,
            WriteResult balanceWrite,
            Optional<ContentHash> aggregateHash,
            Optional<WriteResult> aggregateWrite,
            boolean pointerInserted) {
        public PersistVersionResult {
            Objects.requireNonNull(balanceHash, "balanceHash");
            Objects.requireNonNull(balanceWrite, "balanceWrite");
            Objects.requireNonNull(aggregateHash, "aggregateHash");
            Objects.requireNonNull(aggregateWrite, "aggregateWrite");
            if (aggregateHash.isPresent() != aggregateWrite.isPresent()) {
                throw new IllegalArgumentException(
                        "aggregateHash and aggregateWrite presence must match");
            }
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

    default WriteResult insertOrVerifyWithResult(PotBalance balance) {
        insertOrVerify(balance);
        return new WriteResult(ContentWriteOutcome.INSERTED, Duration.ZERO);
    }

    default WriteResult insertOrVerifyAggregateWithResult(PotBalanceAggregate aggregate) {
        insertOrVerifyAggregate(aggregate);
        return new WriteResult(ContentWriteOutcome.INSERTED, Duration.ZERO);
    }

    default PersistVersionResult insertOrVerifyVersionWithResult(
            PotBalance balance, Optional<PotBalanceAggregate> aggregate) {
        Objects.requireNonNull(balance, "balance");
        Objects.requireNonNull(aggregate, "aggregate");
        WriteResult balWrite = insertOrVerifyWithResult(balance);
        ContentHash balHash = PotBalanceBinaryCodec.encode(balance).content().hash();
        Optional<ContentHash> aggHash = Optional.empty();
        Optional<WriteResult> aggWrite = Optional.empty();
        if (aggregate.isPresent()) {
            aggWrite = Optional.of(insertOrVerifyAggregateWithResult(aggregate.get()));
            aggHash = Optional.of(
                    PotBalanceAggregateBinaryCodec.encode(aggregate.get()).content().hash());
        }
        return new PersistVersionResult(balHash, balWrite, aggHash, aggWrite, false);
    }

    default void closePublication(UUID pot, long version, boolean pointerInserted) {
        closePublication();
    }

    default void requireCompletionReady(UUID pot, long version,
            Optional<ContentHash> balanceHash, Optional<ContentHash> aggregateHash,
            boolean pointerInserted) {
        requireHashesStored(pot, version, balanceHash, aggregateHash);
    }

    default void requireHashesStored(
            UUID pot,
            long version,
            Optional<ContentHash> expectedBalanceHash,
            Optional<ContentHash> expectedAggregateHash) {
        throw new UnsupportedOperationException(
                "requireHashesStored not supported by this store");
    }
}
