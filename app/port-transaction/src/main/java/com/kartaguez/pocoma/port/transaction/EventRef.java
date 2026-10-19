package com.kartaguez.pocoma.port.transaction;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of an accepted publication; no claim of commit or external visibility. */
public record EventRef(EventFamily family, UUID eventId) {
    public EventRef {
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(eventId, "eventId");
    }
}
