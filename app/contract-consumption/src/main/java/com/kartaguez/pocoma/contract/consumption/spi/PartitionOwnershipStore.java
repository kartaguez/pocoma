package com.kartaguez.pocoma.contract.consumption.spi;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;

public interface PartitionOwnershipStore {
    Optional<PartitionLease> acquire(
            ConsumptionDefinition expected, int partition, UUID freshToken, Duration duration);

    List<PartitionLease> renew(
            ConsumptionDefinition expected, List<PartitionLease> leases, Duration duration);

    /**
     * Renews a batch of partition leases potentially spanning multiple {@link ConsumptionDefinition}s
     * in a single grouped persistence operation.
     */
    default List<PartitionLease> renewBatch(
            List<ConsumptionDefinition> definitions,
            List<PartitionLease> leases,
            Duration duration) {
        if (leases.isEmpty()) return List.of();
        var byId = new java.util.LinkedHashMap<com.kartaguez.pocoma.domain.consumption.ConsumptionId, ConsumptionDefinition>();
        for (var def : definitions) byId.put(def.id(), def);
        var grouped = new java.util.LinkedHashMap<com.kartaguez.pocoma.domain.consumption.ConsumptionId, List<PartitionLease>>();
        for (var lease : leases) {
            grouped.computeIfAbsent(lease.consumptionId(), k -> new java.util.ArrayList<>()).add(lease);
        }
        var result = new java.util.ArrayList<PartitionLease>(leases.size());
        for (var entry : grouped.entrySet()) {
            var def = byId.get(entry.getKey());
            if (def == null) throw new IllegalArgumentException("foreign lease");
            result.addAll(renew(def, entry.getValue(), duration));
        }
        return List.copyOf(result);
    }

    /** Locks and validates the durable ownership in the SAME transaction as slot acquisition. */
    Optional<PartitionLease> protectAcquisition(
            ConsumptionDefinition expected, PartitionLease lease);

    /** Set local SQL timeouts inside the Engine-owned technical transaction. */
    void bound(Duration lockTimeout, Duration statementTimeout);
}
