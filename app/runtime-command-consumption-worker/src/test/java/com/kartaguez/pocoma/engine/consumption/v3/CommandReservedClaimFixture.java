package com.kartaguez.pocoma.engine.consumption.v3;

/** I2 handoff only; new I3 tests acquire through the productive Engine. */
public final class CommandReservedClaimFixture {
    public static ConsumptionEngine.Attempt execute(ConsumptionEngine e, ClaimedSlot c) {
        return e.execute(
                new ConsumptionEngine.AcquiredClaim(
                        e, c, e.capacity.reserve(c.consumptionId(), 1).getFirst()));
    }
}
