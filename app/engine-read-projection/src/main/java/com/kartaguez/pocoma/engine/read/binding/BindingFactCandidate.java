package com.kartaguez.pocoma.engine.read.binding;

import java.util.UUID;

public record BindingFactCandidate(UUID eventId, BindingFactCursor cursor) {}
