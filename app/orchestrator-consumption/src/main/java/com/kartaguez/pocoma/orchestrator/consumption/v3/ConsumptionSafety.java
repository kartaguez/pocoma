package com.kartaguez.pocoma.orchestrator.consumption.v3;

import com.kartaguez.pocoma.contract.consumption.invocation.*;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition.Authority;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.Resolution;
import java.time.Duration;
import java.util.*;

/** The original physical-outcome and renewal exclusions, owned once per worker scope. */
public final class ConsumptionSafety implements ProtocolSafety {
    private final List<AbstractConsumptionProtocol> protocols;
    private record Lane(Authority authority, int index) {}
    private final Map<Lane, Permit> lanes = new HashMap<>();
    private final Map<ConsumptionProtocol.Handle, Permit> claims = new IdentityHashMap<>();
    private final class Permit implements RenewalPermit {
        final ConsumptionSafety issuer = ConsumptionSafety.this;
        final Lane lane;
        final ConsumptionProtocol.Handle claim;
        boolean finished;
        Permit(Lane lane, ConsumptionProtocol.Handle claim) { this.lane = lane; this.claim = claim; }
    }
    public ConsumptionSafety(List<ConsumptionProtocol> protocols, CapacityView capacity) {
        this.protocols = protocols.stream().map(p -> {
            if (!(p instanceof AbstractConsumptionProtocol owned))
                throw new IllegalArgumentException("Unrecognized protocol provider in worker scope");
            return owned;
        }).toList();
        if (this.protocols.isEmpty()) throw new IllegalArgumentException("Empty protocol scope");
        if (!(capacity instanceof WorkerCapacity owner)
                || this.protocols.stream().anyMatch(p -> p.engine.capacity != owner))
            throw new IllegalArgumentException("Protocol and polling capacity scopes differ");
        owner.attachPollingScope(this);
    }
    public synchronized boolean renewalsQuiescent() { return lanes.isEmpty(); }
    public PhysicalOutcome opportunityOutcome(Throwable failure) {
        Resolution resolution = failure == null ? Resolution.COMMITTED
                : failure instanceof AttemptTransactions.Failure tx ? tx.resolution() : Resolution.UNKNOWN;
        boolean resolved = resolution != Resolution.UNKNOWN;
        return new PhysicalOutcome(resolution, resolved, !resolved);
    }
    public PhysicalOutcome attemptOutcome(ConsumptionProtocol.Attempt attempt) {
        boolean unresolved = attempt.resolution() == Resolution.UNKNOWN;
        return new PhysicalOutcome(attempt.resolution(), !unresolved,
                unresolved || attempt.status() == ConsumptionProtocol.Status.INCONSISTENT);
    }
    public synchronized Optional<RenewalPermit> beginRenewal(Authority authority, int lane,
            ConsumptionProtocol.Handle claim) {
        if (lane < 0 || lane > 2 || (lane == 2) != (claim == null))
            throw new IllegalArgumentException("Invalid renewal lane/candidate");
        boolean allocated = protocols.stream().anyMatch(p -> p.definition.authority() == authority
                && (claim == null || p.owns(claim)));
        if (!allocated) throw new IllegalArgumentException("Foreign worker scope or renewal candidate");
        var key = new Lane(authority, lane);
        if (lanes.containsKey(key) || (claim != null && claims.containsKey(claim))) return Optional.empty();
        var permit = new Permit(key, claim);
        lanes.put(key, permit);
        if (claim != null) claims.put(claim, permit);
        return Optional.of(permit);
    }
    public synchronized PhysicalOutcome finishRenewal(RenewalPermit value, Throwable failure) {
        if (!(value instanceof ConsumptionSafety.Permit permit) || permit.issuer != this
                || permit.finished || lanes.get(permit.lane) != permit)
            throw new IllegalArgumentException("Foreign, fabricated or already used renewal permit");
        permit.finished = true;
        var outcome = opportunityOutcome(failure);
        if (outcome.physicallyResolved()) {
            lanes.remove(permit.lane);
            if (permit.claim != null) claims.remove(permit.claim);
        }
        // UNKNOWN retains the lane and candidate. A local deadline never frees either.
        return outcome;
    }
    public LeaseDecision settleLease(ConsumptionProtocol.Partition current,
            List<ConsumptionProtocol.Partition> returned, PhysicalOutcome outcome, boolean running,
            boolean usable, long retryUntil, long retryDue, long due, long callBegan, long renewedAt,
            Duration renewalBudget, Duration renewalInterval, Duration minimumPoll) {
        if (protocols.stream().noneMatch(p -> p.owns(current)))
            throw new IllegalArgumentException("Foreign partition scope");
        var renewed = returned.stream().filter(l -> l.sameIncarnation(current)).findFirst();
        if (renewed.isPresent() && outcome.physicallyResolved() && running)
            return new LeaseDecision(false, renewed.get(), true, 0, 0, renewedAt, false);
        if (outcome.physicallyResolved() && running) {
            long returnedAt = System.nanoTime();
            if (retryUntil == 0) {
                retryDue = due;
                retryUntil = returnedAt + renewalBudget.toNanos();
            }
            if (returnedAt - retryUntil < 0)
                return new LeaseDecision(false, current, false, retryUntil, retryDue,
                        callBegan - renewalInterval.toNanos() + minimumPoll.toNanos(), usable);
        }
        return new LeaseDecision(true, current, false, retryUntil, retryDue, renewedAt, false);
    }
}
