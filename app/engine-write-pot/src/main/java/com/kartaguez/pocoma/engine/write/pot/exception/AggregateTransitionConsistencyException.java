package com.kartaguez.pocoma.engine.write.pot.exception;

import java.util.Objects;

/** A persistence transition invariant failed. Rollback is mandatory; retry is not implied. */
public final class AggregateTransitionConsistencyException extends RuntimeException {
    public AggregateTransitionConsistencyException(String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
    }
}
