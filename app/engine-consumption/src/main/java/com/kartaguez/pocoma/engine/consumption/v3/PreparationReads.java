package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.util.function.Function;

/**
 * Invocation-bound preparation snapshots, opened consecutively on the invoking thread. Every
 * successful scope has physically resolved before returning; only detached values escape. Caught
 * guarded failures still invalidate the invocation. Scopes cannot be nested or reopened after
 * preparation, and this capability never authorizes a business effect.
 */
public interface PreparationReads {
    <T> T source(Function<RecoveryReadContext, T> action);

    <T> T effects(Function<RecoveryReadContext, T> action);

    final class Invalid extends IllegalStateException {
        public Invalid(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
