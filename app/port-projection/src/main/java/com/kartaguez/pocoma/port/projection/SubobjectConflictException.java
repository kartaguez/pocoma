package com.kartaguez.pocoma.port.projection;

import com.kartaguez.pocoma.domain.projection.SubobjectKey;

/** Diagnostics deliberately exclude business payloads. */
public final class SubobjectConflictException extends RuntimeException {
    public SubobjectConflictException(SubobjectKey key) { super("Divergent immutable subobject: " + key); }
}
