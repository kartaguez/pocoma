package com.kartaguez.pocoma.orchestrator.poll.consumption.v3;

import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.SlotKeyNavigation;

import java.time.Duration;
import java.util.*;

/** C first, then p, then a raw circular key walk. No SQL, future joins or ticket authority. */
public final class V3ConsumptionLoop {
    private final V3ConsumptionWorker worker;
    private final int[] nextPartitions;
    private final Map<V3ConsumptionWorker.Pair, SlotKeyNavigation.Walk> walks = new HashMap<>();
    private int nextConsumption;
    private final long[] lastOpportunity;
    private final boolean[] consumptionSaturated;
    private Phase globalSaturation;

    V3ConsumptionLoop(V3ConsumptionWorker worker) {
        this.worker = worker;
        nextPartitions = new int[worker.bindings.size()];
        lastOpportunity = new long[worker.bindings.size()];
        consumptionSaturated = new boolean[worker.bindings.size()];
    }

    public boolean runOnePass() {
        if (!worker.running()) return false;
        var capacity = worker.capacity.snapshot();
        Phase saturated =
                capacity.worker() >= worker.capacity.workerLimit()
                        ? Phase.SATURATION_WORKER
                        : !worker.dispatchAvailable() ? Phase.SATURATION_EXECUTOR : null;
        if (saturated != null) {
            if (globalSaturation != saturated)
                worker.event(
                        worker.bindings.get(nextConsumption),
                        saturated,
                        Outcome.CAPACITY,
                        1,
                        Duration.ZERO);
            globalSaturation = saturated;
            return false;
        }
        globalSaturation = null;
        if (!worker.beginOperation()) return false;
        int selected = nextConsumption;
        var binding = worker.bindings.get(selected);
        nextConsumption =
                (selected + 1)
                        % worker.bindings.size(); // Consumed once, even when a slow call fails.
        int p = binding.partitions().get(nextPartitions[selected]);
        nextPartitions[selected] = (nextPartitions[selected] + 1) % binding.partitions().size();
        long began = System.nanoTime();
        Outcome outcome = Outcome.UNKNOWN;
        Throwable exit = null;
        if (lastOpportunity[selected] != 0)
            worker.event(
                    binding,
                    Phase.OPPORTUNITY_WAIT,
                    Outcome.CONFIRMED,
                    1,
                    Duration.ofNanos(began - lastOpportunity[selected]));
        lastOpportunity[selected] = began;
        worker.event(binding, Phase.CONSUMPTION_ROTATION, Outcome.CONFIRMED, 1, Duration.ZERO);
        try {
            if (capacity.consumptions().getOrDefault(binding.definition().id(), 0)
                    >= worker.limits.get(binding.definition().id())) {
                if (!consumptionSaturated[selected])
                    worker.event(
                            binding,
                            Phase.SATURATION_CONSUMPTION,
                            Outcome.CAPACITY,
                            1,
                            Duration.ZERO);
                consumptionSaturated[selected] = true;
                outcome = Outcome.CAPACITY;
                return false;
            }
            consumptionSaturated[selected] = false;
            outcome = Outcome.CONFIRMED;
            var lease = worker.lease(binding, p);
            if (lease == null) return false;
            var pair = new V3ConsumptionWorker.Pair(binding, p);
            var previous = walks.getOrDefault(pair, SlotKeyNavigation.Walk.fresh());
            var page = binding.engine().navigate(lease, previous, worker.settings.pageSize());
            // U is remembered, but reading keys never advances afterExclusive.
            var bounded =
                    new SlotKeyNavigation.Walk(previous.afterExclusive(), page.upperInclusive());
            walks.put(pair, bounded);
            if (page.keys().isEmpty()) {
                walks.put(pair, SlotKeyNavigation.Walk.fresh());
                worker.event(binding, Phase.CONSUMPTION_TOUR, Outcome.CONFIRMED, 1, Duration.ZERO);
                return false;
            }
            var keys =
                    page.keys().subList(0, Math.min(worker.settings.quantum(), page.keys().size()));
            var acquired = binding.engine().acquire(lease, keys);
            try {
                int e = acquired.examinedCount();
                if (e < 0 || e > keys.size())
                    throw new IllegalStateException("invalid examined prefix");
                if (e > 0)
                    walks.put(
                            pair,
                            new SlotKeyNavigation.Walk(
                                    Optional.of(keys.get(e - 1)), page.upperInclusive()));
                if (e == 0) worker.suspend(binding, lease);
            } finally {
                // Every confirmed handle transfers to registration/cleanup, even on a validation
                // failure.
                worker.dispatch(binding, acquired.claims());
            }
            return acquired.examinedCount() > 0;
        } catch (RuntimeException | Error failure) {
            exit = failure;
            outcome = Outcome.UNKNOWN;
            throw failure;
        } finally {
            worker.event(
                    binding,
                    Phase.OPPORTUNITY,
                    outcome,
                    1,
                    Duration.ofNanos(System.nanoTime() - began));
            worker.endOperation(exit);
        }
    }

    SlotKeyNavigation.Walk walk(V3ConsumptionWorker.Binding binding, int partition) {
        return walks.getOrDefault(
                new V3ConsumptionWorker.Pair(binding, partition), SlotKeyNavigation.Walk.fresh());
    }

    int nextConsumption() {
        return nextConsumption;
    }
}
