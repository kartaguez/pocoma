package com.kartaguez.pocoma.engine.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation;

import java.time.Duration;
import java.util.*;

/** One instance per Worker, shared by every Consumption and physical authority. */
public final class WorkerCapacity {
    private final int workerLimit;
    private final Map<ConsumptionId, Integer> limits;
    private final Map<ConsumptionId, Integer> used = new HashMap<>();
    private int total;
    private final ConsumptionObservation observation;

    public WorkerCapacity(int workerLimit, Map<ConsumptionId, Integer> consumptionLimits) {
        this(workerLimit, consumptionLimits, ConsumptionObservation.noop());
    }

    public WorkerCapacity(
            int workerLimit,
            Map<ConsumptionId, Integer> consumptionLimits,
            ConsumptionObservation observation) {
        this.observation = Objects.requireNonNull(observation);
        if (workerLimit < 1
                || consumptionLimits.isEmpty()
                || consumptionLimits.values().stream().anyMatch(n -> n == null || n < 1))
            throw new IllegalArgumentException("positive Worker and Consumption limits required");
        this.workerLimit = workerLimit;
        this.limits = Map.copyOf(consumptionLimits);
    }

    public int workerLimit() {
        return workerLimit;
    }

    public boolean supports(ConsumptionId id) {
        return limits.containsKey(id);
    }

    synchronized List<Ticket> reserve(ConsumptionId id, int wanted) {
        if (wanted < 0 || !supports(id)) throw new IllegalArgumentException("invalid reservation");
        int count =
                Math.min(
                        wanted,
                        Math.min(workerLimit - total, limits.get(id) - used.getOrDefault(id, 0)));
        var result = new ArrayList<Ticket>(count);
        for (int i = 0; i < count; i++) result.add(new Ticket(id));
        total += count;
        used.merge(id, count, Integer::sum);
        if (count > 0)
            ConsumptionObservation.safely(
                    observation,
                    id,
                    ConsumptionObservation.Phase.CAPACITY_RESERVED,
                    ConsumptionObservation.Outcome.CONFIRMED,
                    count,
                    Duration.ZERO);
        return List.copyOf(result);
    }

    public record Snapshot(int worker, Map<ConsumptionId, Integer> consumptions) {}

    public synchronized Snapshot snapshot() {
        return new Snapshot(total, Map.copyOf(used));
    }

    /** Only Engine may transfer/release a ticket; repeat release changes nothing. */
    public final class Ticket {
        private final ConsumptionId id;
        private boolean released;

        private Ticket(ConsumptionId id) {
            this.id = id;
        }

        boolean belongsTo(WorkerCapacity capacity, ConsumptionId id) {
            return WorkerCapacity.this == capacity && this.id.equals(id);
        }

        boolean retained() {
            synchronized (WorkerCapacity.this) {
                return !released;
            }
        }

        boolean release() {
            synchronized (WorkerCapacity.this) {
                if (released) return false;
                released = true;
                total--;
                used.compute(id, (key, count) -> count - 1);
                ConsumptionObservation.safely(
                        observation,
                        id,
                        ConsumptionObservation.Phase.CAPACITY_RETURNED,
                        ConsumptionObservation.Outcome.CONFIRMED,
                        1,
                        Duration.ZERO);
                return true;
            }
        }
    }
}
