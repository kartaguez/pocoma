package com.kartaguez.pocoma.domain.consumption.v3;

import java.util.Objects;

/** Fixed pilot contract. No transformation or worker protocol is activated by this value. */
public record ConsumptionDefinition(
        ConsumptionId id, ConsumableFamily family, Authority authority) {
    public enum Authority {
        WRITE,
        READ
    }

    public ConsumptionDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(family, "family");
        Objects.requireNonNull(authority, "authority");
        if ((family == ConsumableFamily.COMMAND || family == ConsumableFamily.IDENTITY_REQUEST)
                != (authority == Authority.WRITE))
            throw new IllegalArgumentException("wrong effects authority");
    }

    public int partitionCount() {
        return 4;
    }

    public String routingVersion() {
        return "sha256-v1";
    }
}
