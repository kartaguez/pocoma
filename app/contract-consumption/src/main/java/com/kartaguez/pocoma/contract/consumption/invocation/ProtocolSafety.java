package com.kartaguez.pocoma.contract.consumption.invocation;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition.Authority;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.Resolution;
import java.time.Duration;
import java.util.*;

/** Scope-owned protocol verdicts. Local scheduling never resolves a physical transaction. */
public interface ProtocolSafety {
    @FunctionalInterface interface Factory {
        ProtocolSafety create(List<ConsumptionProtocol> protocols, CapacityView capacity);
    }
    interface RenewalPermit {}
    record PhysicalOutcome(Resolution resolution, boolean physicallyResolved, boolean requestDrain) {}
    record LeaseDecision(boolean remove, ConsumptionProtocol.Partition lease, boolean usable,
                         long retryUntil, long retryDue, long renewedAt, boolean suspended) {}
    boolean renewalsQuiescent();
    PhysicalOutcome opportunityOutcome(Throwable failure);
    PhysicalOutcome attemptOutcome(ConsumptionProtocol.Attempt attempt);
    Optional<RenewalPermit> beginRenewal(Authority authority, int lane, ConsumptionProtocol.Handle claim);
    PhysicalOutcome finishRenewal(RenewalPermit permit, Throwable failure);
    LeaseDecision settleLease(ConsumptionProtocol.Partition current,
            List<ConsumptionProtocol.Partition> returned, PhysicalOutcome outcome, boolean running,
            boolean usable, long retryUntil, long retryDue, long due, long callBegan, long renewedAt,
            Duration renewalBudget, Duration renewalInterval, Duration minimumPoll);
}
