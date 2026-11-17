package com.kartaguez.pocoma.domain.consumption;

import java.util.Objects;
import java.util.UUID;

public record ConsumptionId(UUID value) {
    public ConsumptionId {
        Objects.requireNonNull(value, "value");
    }
}
