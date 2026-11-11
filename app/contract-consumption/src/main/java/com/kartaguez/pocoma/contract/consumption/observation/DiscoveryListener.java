package com.kartaguez.pocoma.contract.consumption.observation;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import java.util.UUID;

public interface DiscoveryListener {
        default void onCandidateDiscovered(
                ConsumptionDefinition definition, int partition, UUID id, long nanos) {}

        default void onSlotAdmitted(
                ConsumptionDefinition definition, int partition, UUID id, long nanos) {}

        DiscoveryListener NOOP = new DiscoveryListener() {};
    }
