package com.kartaguez.pocoma.engine.consumption.v3.port;

@FunctionalInterface
public interface AttemptFailureClassifier {
    enum FailureKind {
        COLLISION,
        INCONSISTENT_FACTS,
        SQL_OR_JPA,
        TECHNICAL,
        NON_COUNTABLE
    }

    FailureKind classify(Throwable originalCause);
}
