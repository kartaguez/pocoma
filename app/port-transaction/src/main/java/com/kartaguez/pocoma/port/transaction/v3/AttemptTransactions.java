package com.kartaguez.pocoma.port.transaction.v3;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** One physical authority. Callers must enter without an ambient transaction. */
public interface AttemptTransactions {
    /** Pure monotone poison shared by consecutive units; invalidate must be thread-safe. */
    interface Guard {
        void requireValid();

        void invalidate(Throwable cause);
    }

    <T> T read(Function<RecoveryReadContext, T> action, Guard guard);

    <T> T write(Supplier<T> action, Guard guard);

    default <T> T read(Function<RecoveryReadContext, T> action) {
        return read(action, unguarded());
    }

    default <T> T write(Supplier<T> action) {
        return write(action, unguarded());
    }

    private static Guard unguarded() {
        return new Guard() {
            public void requireValid() {}

            public void invalidate(Throwable cause) {}
        };
    }

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
