package com.kartaguez.pocoma.engine.consumption.v3.port;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.engine.consumption.v3.PartitionLease;

import java.time.Duration;
import java.util.*;

public interface PartitionOwnershipStore {
    Optional<PartitionLease> acquire(
            ConsumptionDefinition expected, int partition, UUID freshToken, Duration duration);

    List<PartitionLease> renew(
            ConsumptionDefinition expected, List<PartitionLease> leases, Duration duration);

    /** Locks and validates the durable ownership in the SAME transaction as slot acquisition. */
    Optional<PartitionLease> protectAcquisition(
            ConsumptionDefinition expected, PartitionLease lease);

    /** Set local SQL timeouts inside the Engine-owned technical transaction. */
    void bound(Duration lockTimeout, Duration statementTimeout);
}
