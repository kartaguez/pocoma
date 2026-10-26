package com.kartaguez.pocoma.port.transaction.v3;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** One physical authority. Callers must enter without an ambient transaction. */
public interface AttemptTransactions {
    <T> T read(Function<RecoveryReadContext, T> action);

    <T> T write(Supplier<T> action);

    void poison(Throwable cause);

    void requirePublicationClosed();

    enum Resolution {
        COMMITTED,
        ROLLED_BACK,
        UNKNOWN
    }

    /** Physical resolution is independent of any subsequent durable observation. */
    final class Failure extends RuntimeException {
        private final Resolution resolution;

        public Failure(Resolution resolution, Throwable cause) {
            super("Attempt transaction " + resolution, Objects.requireNonNull(cause));
            this.resolution = Objects.requireNonNull(resolution);
        }

        public Resolution resolution() {
            return resolution;
        }
    }
}
