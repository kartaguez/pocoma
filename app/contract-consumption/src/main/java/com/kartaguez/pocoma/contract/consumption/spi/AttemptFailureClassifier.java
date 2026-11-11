package com.kartaguez.pocoma.contract.consumption.spi;
import com.kartaguez.pocoma.contract.consumption.participation.*;
import com.kartaguez.pocoma.contract.consumption.spi.*;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;

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
