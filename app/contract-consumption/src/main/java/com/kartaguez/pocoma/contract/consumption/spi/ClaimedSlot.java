package com.kartaguez.pocoma.contract.consumption.spi;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.ConsumptionId;

/** Input from an already committed acquisition; never itself proof of ownership. */
public record ClaimedSlot(ConsumptionId consumptionId, UUID consumableId, UUID claimToken) {
    public ClaimedSlot {
        Objects.requireNonNull(consumptionId);
        Objects.requireNonNull(consumableId);
        Objects.requireNonNull(claimToken);
    }
}
