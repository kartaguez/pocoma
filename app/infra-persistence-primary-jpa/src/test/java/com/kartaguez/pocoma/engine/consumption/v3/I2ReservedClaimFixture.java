package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;

import java.util.*;

/** I2 only: SQL fixture claim + real ticket. No lifecycle cession; I2 assertions stay unchanged. */
public final class I2ReservedClaimFixture {
    private final ConsumptionEngine engine;

    public I2ReservedClaimFixture(
            ConsumptionDefinition definition,
            AttemptTransactions tx,
            ConsumptionSlotStore slots,
            ConsumptionParticipant participant) {
        var ownership =
                (PartitionOwnershipStore)
                        java.lang.reflect.Proxy.newProxyInstance(
                                PartitionOwnershipStore.class.getClassLoader(),
                                new Class<?>[] {PartitionOwnershipStore.class},
                                (proxy, method, args) -> {
                                    throw new AssertionError("I2 does not call lifecycle SQL");
                                });
        engine =
                new ConsumptionEngine(
                        definition,
                        tx,
                        slots,
                        participant,
                        ownership,
                        LifecycleSettings.pilot(),
                        new WorkerCapacity(1024, Map.of(definition.id(), 1024)),
                        cause -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
    }

    public ConsumptionEngine.Attempt execute(ClaimedSlot claim) {
        var ticket = engine.capacity.reserve(claim.consumptionId(), 1).getFirst();
        return engine.execute(new ConsumptionEngine.AcquiredClaim(engine, claim, ticket));
    }

    public RecoveryObservation observe(ClaimedSlot claim) {
        return engine.observe(claim);
    }
}
