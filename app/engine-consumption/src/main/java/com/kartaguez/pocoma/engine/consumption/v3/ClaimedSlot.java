package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;

import java.util.Objects;
import java.util.UUID;

/** Input from an already committed acquisition; never itself proof of ownership. */
public record ClaimedSlot(ConsumptionId consumptionId, UUID consumableId, UUID claimToken) {
    public ClaimedSlot {
        Objects.requireNonNull(consumptionId);
        Objects.requireNonNull(consumableId);
        Objects.requireNonNull(claimToken);
    }
}
