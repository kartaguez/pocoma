package com.kartaguez.pocoma.domain.useridentity.v3;

import java.util.Objects;

/** Opaque occurrence witness. Generation and historical uniqueness belong to the authority. */
public record BindingToken(String value) {
    public BindingToken {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("Binding token required");
    }

    @Override
    public String toString() {
        return "BindingToken[REDACTED]";
    }
}
