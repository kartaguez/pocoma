package com.kartaguez.pocoma.port.projection.v3;

import com.kartaguez.pocoma.domain.projection.v3.SubobjectKey;

/** Diagnostics deliberately exclude business payloads. */
public final class SubobjectConflictException extends RuntimeException {
    public SubobjectConflictException(SubobjectKey key) { super("Divergent immutable subobject: " + key); }
}
