package com.kartaguez.pocoma.engine.materialize.pot;

import java.time.Duration;

/** Bounded, low-cardinality telemetry for PotVersion incremental N-m materialization. */
public interface PotVersionObservation {
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
        INCREMENTAL_DISABLED,
        INITIAL_VERSION,
        NO_REFERENCE,
        EMPTY_DELTA,
        DELTA_TOO_LARGE,
        GLOBAL_SHAREHOLDER_EFFECT
    }

    enum Stage {
        REFERENCE_LOOKUP,
        DELTA_RESOLUTION,
        COMPOSE,
        PERSIST_PUBLISH
    }

    PotVersionObservation NOOP =
            new PotVersionObservation() {
                @Override
                public void referenceLookup(LookupOutcome outcome, long distance, Duration duration) {}

                @Override
                public void deltaResolved(int changedSubobjects, Duration duration) {}

                @Override
                public void composed(Duration duration) {}

                @Override
                public void pathSelected(Path path, Reason reason) {}

                @Override
                public void persistedAndPublished(Path path, Duration duration) {}
            };

    void referenceLookup(LookupOutcome outcome, long distance, Duration duration);

    void deltaResolved(int changedSubobjects, Duration duration);

    void composed(Duration duration);

    void pathSelected(Path path, Reason reason);

    void persistedAndPublished(Path path, Duration duration);
}
