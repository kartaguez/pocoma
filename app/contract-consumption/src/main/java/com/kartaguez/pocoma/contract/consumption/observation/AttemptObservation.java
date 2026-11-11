package com.kartaguez.pocoma.contract.consumption.observation;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import java.util.UUID;

public interface AttemptObservation {
        default void onSlotAcquired(
                ConsumptionId id, UUID consumableId, int partition, long startNanos) {}

        void onAttemptStarting(ConsumptionId id, UUID consumableId, long startNanos);
        void onAttemptFinished(
                ConsumptionId id,
                UUID consumableId,
                ConsumptionProtocol.Status status,
                long finishNanos);

        AttemptObservation NOOP = new AttemptObservation() {
            @Override
            public void onAttemptStarting(ConsumptionId id, UUID consumableId, long startNanos) {}

            @Override
            public void onAttemptFinished(
                    ConsumptionId id,
                    UUID consumableId,
                    ConsumptionProtocol.Status status,
                    long finishNanos) {}
        };
    }
