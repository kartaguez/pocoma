package com.kartaguez.pocoma.engine.write.pot.port.persistence.v3;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;

import java.util.*;

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
