package com.kartaguez.pocoma.contract.consumption.spi;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;

/** Caller owns the physical unit and protects ownership; this port never begins or commits. */
public interface DiscoveryAdmissionStore {
    long checkpoint(ConsumptionId id, int partition, RecoveryReadContext read);

    long currentCheckpoint(PartitionLease protectedLease);

    long liveSlots(PartitionLease protectedLease);

    /** MVCC identity check, without a slot row lock; QUARANTINED also means present. */
    boolean contains(ConsumptionDefinition definition, int partition, UUID id);

    void insertInitial(ConsumptionDefinition definition, int partition, UUID id);

    void advanceCheckpoint(PartitionLease protectedLease, long expected, long next);
}
