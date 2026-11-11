package com.kartaguez.pocoma.contract.consumption.invocation;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import java.util.Map;

public interface CapacityView {
    public record Snapshot(int worker, Map<ConsumptionId, Integer> consumptions) {}
    int workerLimit();
    boolean supports(ConsumptionId id);
    Snapshot snapshot();
}
