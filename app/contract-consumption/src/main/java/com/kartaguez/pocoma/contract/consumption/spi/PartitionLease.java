package com.kartaguez.pocoma.contract.consumption.spi;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.ConsumptionId;

/** Returned DB values, never a substitute for transactional ownership validation. */
public record PartitionLease(
        ConsumptionId consumptionId, int partitionId, UUID ownerToken, Instant ownedUntil) {
    public PartitionLease {
        Objects.requireNonNull(consumptionId);
        Objects.requireNonNull(ownerToken);
        Objects.requireNonNull(ownedUntil);
        if (partitionId < 0) throw new IllegalArgumentException("negative partition");
    }
}
