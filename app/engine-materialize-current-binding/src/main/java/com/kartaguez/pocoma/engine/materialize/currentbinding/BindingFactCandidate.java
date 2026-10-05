package com.kartaguez.pocoma.engine.materialize.currentbinding;

import java.util.UUID;

public record BindingFactCandidate(UUID eventId, BindingFactCursor cursor) {}
