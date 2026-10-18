package com.kartaguez.pocoma.engine.write.pot.exception;

import java.util.Objects;

/** A lost conditional update of the global Pot version. Must escape Execute and roll back. */
public final class PotVersionCasConflictException extends RuntimeException {
    public PotVersionCasConflictException(String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
    }
}
