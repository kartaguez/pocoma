package com.kartaguez.pocoma.domain.pot;

import java.util.Objects;

/** Distinguishes omitted loading from authoritative absence before object creation. */
public sealed interface ComponentRead<T> {
    record NotLoaded<T>() implements ComponentRead<T> {}

    record Absent<T>() implements ComponentRead<T> {}

    record Present<T>(Versioned<T> revision) implements ComponentRead<T> {
        public Present {
            Objects.requireNonNull(revision);
        }
    }
}
