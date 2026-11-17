package com.kartaguez.pocoma.domain.pot;

import java.util.Objects;

public record Weight(Fraction value) {
    public Weight {
        Objects.requireNonNull(value);
        if (value.compareTo(Fraction.ZERO) <= 0) throw new IllegalArgumentException("Weight must be positive");
    }
}
