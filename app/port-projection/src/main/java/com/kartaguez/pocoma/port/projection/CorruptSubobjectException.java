package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.SubobjectKey;

/** Diagnostics deliberately exclude business payloads. */
public final class CorruptSubobjectException extends RuntimeException {
    public CorruptSubobjectException(SubobjectKey key) { super("Invalid persisted subobject: " + key); }
}
