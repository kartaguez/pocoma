package com.kartaguez.pocoma.engine.materialize.pot;

import java.time.Duration;

/** Bounded, low-cardinality telemetry for PotBalance incremental N-m materialization. */
public interface PotBalanceObservation {
    enum LookupOutcome {
        HIT,
        MISS,
        CORRUPT
    }

    enum Path {
        INCREMENTAL,
        FULL
    }

    enum Reason {
        INCREMENTAL_HIT,
        TERMINAL_DELETED,
        INITIAL_VERSION,
        INCREMENTAL_DISABLED,
        NO_REFERENCE,
        DELTA_TOO_LARGE,
        SHAREHOLDER_DELETED,
        BUCKET_CAP
    }

    enum PersistTarget {
        BALANCE,
        AGGREGATE
    }

    enum ContentOutcome {
        INSERTED,
        REUSED
    }

    PotBalanceObservation NOOP =
            new PotBalanceObservation() {
                @Override
                public void aggregateLookup(
                        LookupOutcome outcome, long distance, Duration duration) {}

                @Override
                public void deltaResolved(int changedExpenses, Duration duration) {}

                @Override
                public void aggregateComputed(
                        int bucketCount, int bucketEntryCount, Duration duration) {}

                @Override
                public void balanceEvaluated(Path path, Duration duration) {}

                @Override
                public void persisted(PersistTarget target, Duration duration) {}

                @Override
                public void pathSelected(Path path, Reason reason) {}
            };

    void aggregateLookup(LookupOutcome outcome, long distance, Duration duration);

    void deltaResolved(int changedExpenses, Duration duration);

    void aggregateComputed(int bucketCount, int bucketEntryCount, Duration duration);

    void balanceEvaluated(Path path, Duration duration);

    void persisted(PersistTarget target, Duration duration);

    void pathSelected(Path path, Reason reason);

    default void contentWritten(PersistTarget target, ContentOutcome outcome) {}

    default void contentEncoded(PersistTarget target, Duration duration) {}
}
