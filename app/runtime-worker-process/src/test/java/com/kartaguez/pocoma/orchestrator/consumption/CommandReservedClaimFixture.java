package com.kartaguez.pocoma.orchestrator.consumption;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.spi.ClaimedSlot;

/** I2 handoff only; new I3 tests acquire through the productive Engine. */
public final class CommandReservedClaimFixture {
    public static ConsumptionProtocol.Attempt execute(ConsumptionEngine e, ClaimedSlot c) {
        return e.execute(
                new ConsumptionEngine.AcquiredClaim(
                        e, c, e.capacity.reserve(c.consumptionId(), 1).getFirst()));
    }
}
