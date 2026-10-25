package com.kartaguez.pocoma.domain.pot.v3;

import java.util.Objects;

/** Applicability starts at a global Pot version, independently of the local revision. */
public record Versioned<T>(long localVersion, long activeFrom, T value) {
    public Versioned {
        if (localVersion < 1 || activeFrom < 1) throw new IllegalArgumentException("Positive versions required");
        Objects.requireNonNull(value);
    }
}
