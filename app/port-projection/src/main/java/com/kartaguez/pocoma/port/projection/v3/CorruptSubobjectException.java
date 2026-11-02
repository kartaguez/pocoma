package com.kartaguez.pocoma.port.projection.v3;

import com.kartaguez.pocoma.domain.projection.v3.SubobjectKey;

/** Diagnostics deliberately exclude business payloads. */
public final class CorruptSubobjectException extends RuntimeException {
    public CorruptSubobjectException(SubobjectKey key) { super("Invalid persisted subobject: " + key); }
}
