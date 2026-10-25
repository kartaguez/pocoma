package com.kartaguez.pocoma.domain.pot.v3;

import java.util.Objects;

public record Amount(Fraction value) {
    public Amount {
        Objects.requireNonNull(value);
        if (value.compareTo(Fraction.ZERO) < 0) throw new IllegalArgumentException("Amount must be nonnegative");
    }
}
