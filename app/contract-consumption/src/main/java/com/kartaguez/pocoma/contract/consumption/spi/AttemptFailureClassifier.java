package com.kartaguez.pocoma.contract.consumption.spi;

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
