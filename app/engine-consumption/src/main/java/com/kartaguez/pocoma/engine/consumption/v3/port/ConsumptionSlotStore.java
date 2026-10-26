package com.kartaguez.pocoma.engine.consumption.v3.port;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.engine.consumption.v3.ClaimedSlot;
import com.kartaguez.pocoma.port.transaction.v3.RecoveryReadContext;

import java.util.UUID;

public interface ConsumptionSlotStore {
    enum Protection {
        VALID,
        ABSENT,
        STALE_OR_EXPIRED
    }

    enum SlotObservation {
        ABSENT,
        PRESENT
    }

    Protection protectFinal(ClaimedSlot claim);

    void deleteProtected(ClaimedSlot claim);

    SlotObservation observe(ConsumptionId id, UUID consumableId, RecoveryReadContext context);
}
