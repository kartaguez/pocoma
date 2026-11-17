package com.kartaguez.pocoma.supra.polling.consumption;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import com.kartaguez.pocoma.contract.consumption.invocation.CapacityView;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionNavigation;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.Outcome;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.Phase;

/** C first, then p, then a raw circular key walk. No SQL, future joins or ticket authority. */
public final class ConsumptionLoop {
    private final ConsumptionWorker worker;
    private final int[] nextPartitions;
    private final long[] errorUntilNanos;
    private final Map<ConsumptionWorker.Pair, ConsumptionNavigation.Walk> walks = new HashMap<>();
    private int nextConsumption;
    private final long[] lastOpportunity;
    private final boolean[] consumptionSaturated;
    private Phase globalSaturation;

    ConsumptionLoop(ConsumptionWorker worker) {
        this.worker = worker;
        nextPartitions = new int[worker.bindings.size()];
        errorUntilNanos = new long[worker.bindings.size()];
        lastOpportunity = new long[worker.bindings.size()];
        consumptionSaturated = new boolean[worker.bindings.size()];
    }

    boolean hasReadyBinding() {
        long now = System.nanoTime();
        for (long until : errorUntilNanos) if (now - until >= 0) return true;
        return false;
    }

    private static final class PassOutcome {
        Outcome outcome = Outcome.UNKNOWN;
        Throwable exit;
    }

    private record BindingSlot(int index, ConsumptionWorker.Binding binding, int partition) {}

    public boolean runOnePass() {
        if (!worker.running()) return false;
        var capacity = worker.capacity.snapshot();
        if (isGloballySaturated(capacity)) return false;
        if (!worker.beginOperation()) return false;
        long began = System.nanoTime();
        int selected = selectBindingIndex(began);
        var binding = worker.bindings.get(selected);
        nextConsumption = (selected + 1) % worker.bindings.size(); // Consumed once, even when a slow call fails.
        int p = advancePartition(selected, binding);
        recordOpportunityRotation(selected, binding, began);
        var state = new PassOutcome();
        try {
            return executeBindingPass(new BindingSlot(selected, binding, p), capacity, state);
        } catch (RuntimeException | Error failure) {
            state.exit = failure;
            state.outcome = Outcome.UNKNOWN;
            errorUntilNanos[selected] =
                    System.nanoTime() + worker.settings.errorPoll().toNanos();
            throw failure;
        } finally {
            worker.event(
                    binding,
                    Phase.OPPORTUNITY,
                    state.outcome,
                    1,
                    Duration.ofNanos(System.nanoTime() - began));
            worker.endOperation(state.exit);
        }
    }

    private boolean isGloballySaturated(CapacityView.Snapshot capacity) {
        Phase saturated = detectGlobalSaturation(capacity);
        if (saturated == null) {
            globalSaturation = null;
            return false;
        }
        if (globalSaturation != saturated) {
            worker.event(
                    worker.bindings.get(nextConsumption),
                    saturated,
                    Outcome.CAPACITY,
                    1,
                    Duration.ZERO);
        }
        globalSaturation = saturated;
        return true;
    }

    private Phase detectGlobalSaturation(CapacityView.Snapshot capacity) {
        if (capacity.worker() >= worker.capacity.workerLimit()) return Phase.SATURATION_WORKER;
        return !worker.dispatchAvailable() ? Phase.SATURATION_EXECUTOR : null;
    }

    private int selectBindingIndex(long began) {
        if (worker.bindings.size() <= 1) return nextConsumption;
        for (int i = 0; i < worker.bindings.size(); i++) {
            int candidate = (nextConsumption + i) % worker.bindings.size();
            if (began - errorUntilNanos[candidate] >= 0) return candidate;
        }
        return nextConsumption;
    }

    private int advancePartition(int selected, ConsumptionWorker.Binding binding) {
        int p = binding.partitions().get(nextPartitions[selected]);
        nextPartitions[selected] = (nextPartitions[selected] + 1) % binding.partitions().size();
        return p;
    }

    private void recordOpportunityRotation(
            int selected, ConsumptionWorker.Binding binding, long began) {
        if (lastOpportunity[selected] != 0) {
            worker.event(
                    binding,
                    Phase.OPPORTUNITY_WAIT,
                    Outcome.CONFIRMED,
                    1,
                    Duration.ofNanos(began - lastOpportunity[selected]));
        }
        lastOpportunity[selected] = began;
        worker.event(binding, Phase.CONSUMPTION_ROTATION, Outcome.CONFIRMED, 1, Duration.ZERO);
    }

    private boolean executeBindingPass(
            BindingSlot slot, CapacityView.Snapshot capacity, PassOutcome state) {
        if (isConsumptionSaturated(slot.index(), slot.binding(), capacity)) {
            state.outcome = Outcome.CAPACITY;
            return false;
        }
        consumptionSaturated[slot.index()] = false;
        state.outcome = Outcome.CONFIRMED;
        var pair = new ConsumptionWorker.Pair(slot.binding(), slot.partition());
        synchronized (worker.pairLock(pair)) {
            return acquireOnPair(slot.index(), slot.binding(), pair);
        }
    }

    private boolean isConsumptionSaturated(
            int selected, ConsumptionWorker.Binding binding, CapacityView.Snapshot capacity) {
        int used = capacity.consumptions().getOrDefault(binding.definition().id(), 0);
        if (used < worker.limits.get(binding.definition().id())) return false;
        if (!consumptionSaturated[selected]) {
            worker.event(
                    binding, Phase.SATURATION_CONSUMPTION, Outcome.CAPACITY, 1, Duration.ZERO);
        }
        consumptionSaturated[selected] = true;
        return true;
    }

    private boolean acquireOnPair(
            int selected, ConsumptionWorker.Binding binding, ConsumptionWorker.Pair pair) {
        var lease = worker.lease(binding, pair.partition());
        if (lease == null) return false;
        var previous = walks.getOrDefault(pair, ConsumptionNavigation.Walk.fresh());
        var page = binding.engine().navigate(lease, previous, worker.settings.pageSize());
        walks.put(pair, page.continuation());
        if (page.tourComplete()) {
            worker.event(binding, Phase.CONSUMPTION_TOUR, Outcome.CONFIRMED, 1, Duration.ZERO);
            errorUntilNanos[selected] = 0;
            return false;
        }
        boolean progressed = acquireQuanta(binding, pair, lease, page);
        errorUntilNanos[selected] = 0;
        return progressed;
    }

    private boolean acquireQuanta(
            ConsumptionWorker.Binding binding,
            ConsumptionWorker.Pair pair,
            ConsumptionProtocol.Partition lease,
            ConsumptionProtocol.Navigation page) {
        var continuation = page.continuation();
        int offset = 0;
        int totalExamined = 0;
        int totalClaims = 0;
        while (offset < page.keys().size() && worker.running()) {
            int end = Math.min(offset + worker.settings.quantum(), page.keys().size());
            var keys = page.keys().subList(offset, end);
            var acquired = binding.engine().acquire(lease, keys, continuation);
            int examined = dispatchAcquiredBatch(binding, pair, lease, acquired);
            continuation =
                    acquired.confirmedContinuation()
                            .orElseThrow(() -> new IllegalStateException("invalid examined prefix"));
            totalExamined += examined;
            totalClaims += acquired.claims().size();
            if (totalClaims > 0 || examined < keys.size()) break;
            offset = end;
        }
        return totalClaims > 0
                || (totalExamined > 0 && page.keys().size() == worker.settings.pageSize());
    }

    private int dispatchAcquiredBatch(
            ConsumptionWorker.Binding binding,
            ConsumptionWorker.Pair pair,
            ConsumptionProtocol.Partition lease,
            com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol.Acquisition
                    acquired) {
        try {
            long acquiredNanos = System.nanoTime();
            for (var handle : acquired.claims()) {
                worker.attemptHook()
                        .onSlotAcquired(
                                binding.definition().id(),
                                handle.consumableId(),
                                lease.partitionId(),
                                acquiredNanos);
            }
            var continuation =
                    acquired.confirmedContinuation()
                            .orElseThrow(() -> new IllegalStateException("invalid examined prefix"));
            walks.put(pair, continuation);
            if (acquired.suspendPartition()) worker.suspend(binding, lease);
            return acquired.examinedCount();
        } finally {
            // Every confirmed handle transfers to registration/cleanup, even on a validation failure.
            worker.dispatch(binding, acquired.claims());
        }
    }

    ConsumptionNavigation.Walk walk(ConsumptionWorker.Binding binding, int partition) {
        return walks.getOrDefault(
                new ConsumptionWorker.Pair(binding, partition), ConsumptionNavigation.Walk.fresh());
    }

    int nextConsumption() {
        return nextConsumption;
    }
}
