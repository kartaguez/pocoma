package com.kartaguez.pocoma.supra.polling.consumption;
import com.kartaguez.pocoma.contract.consumption.invocation.DiscoveryReports;

import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.*;

import java.time.Duration;
import java.util.*;

/** One bounded C/p opportunity, independent of business threads and capacity. */
public final class DiscoveryLoop {
    private final ConsumptionWorker worker;
    private final int[] nextPartitions;
    private final long[] errorUntilNanos;
    private int nextConsumption;
    private final Set<ConsumptionWorker.Pair> saturated = new HashSet<>();

    DiscoveryLoop(ConsumptionWorker worker) {
        this.worker = worker;
        nextPartitions = new int[worker.bindings.size()];
        errorUntilNanos = new long[worker.bindings.size()];
    }

    boolean hasReadyBinding() {
        long now = System.nanoTime();
        for (long until : errorUntilNanos) if (now - until >= 0) return true;
        return false;
    }

    private static final class DiscoveryState {
        Outcome outcome = Outcome.UNKNOWN;
        Throwable failure;
    }

    public boolean runOnePass() {
        if (!worker.beginOperation()) return false;
        long began = System.nanoTime();
        int selected = selectBindingIndex(began);
        var binding = worker.bindings.get(selected);
        nextConsumption = (selected + 1) % worker.bindings.size();
        int p = advancePartition(selected, binding);
        var state = new DiscoveryState();
        worker.event(binding, Phase.DISCOVERY_ROTATION, Outcome.CONFIRMED, 1, Duration.ZERO);
        try {
            return executeDiscoveryPass(selected, binding, p, state);
        } catch (RuntimeException | Error exit) {
            state.failure = exit;
            errorUntilNanos[selected] =
                    System.nanoTime() + worker.settings.errorPoll().toNanos();
            throw exit;
        } finally {
            worker.event(
                    binding,
                    Phase.DISCOVERY,
                    state.outcome,
                    1,
                    Duration.ofNanos(System.nanoTime() - began));
            worker.endOperation(state.failure);
        }
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

    private boolean executeDiscoveryPass(
            int selected, ConsumptionWorker.Binding binding, int p, DiscoveryState state) {
        var pair = new ConsumptionWorker.Pair(binding, p);
        var result = discoverLocked(binding, pair, p);
        if (result == null) return false;
        if (result.committedAdmissions() > 0) worker.consumptionWait.signal();
        updateSoftCapSaturation(binding, pair, result.status());
        state.outcome = mapDiscoveryOutcome(result.status());
        recordDiscoveryCounts(binding, result);
        errorUntilNanos[selected] = 0;
        return hasDiscoveryProgress(result);
    }

    private DiscoveryReports.Progress discoverLocked(
            ConsumptionWorker.Binding binding, ConsumptionWorker.Pair pair, int partition) {
        synchronized (worker.pairLock(pair)) {
            var lease = worker.lease(binding, partition);
            if (lease == null) return null;
            var result = binding.protocol().discover(lease);
            if (result.status() == DiscoveryReports.Status.BUSY_OR_NOT_OWNER) {
                worker.suspend(binding, lease);
            }
            return result;
        }
    }

    private void updateSoftCapSaturation(
            ConsumptionWorker.Binding binding,
            ConsumptionWorker.Pair pair,
            DiscoveryReports.Status status) {
        if (status != DiscoveryReports.Status.CAPACITY) {
            saturated.remove(pair);
            return;
        }
        if (saturated.add(pair)) {
            worker.event(binding, Phase.SATURATION_SOFTCAP, Outcome.CAPACITY, 1, Duration.ZERO);
        }
    }

    private static Outcome mapDiscoveryOutcome(DiscoveryReports.Status status) {
        return switch (status) {
            case CAPACITY -> Outcome.CAPACITY;
            case BUSY_OR_NOT_OWNER -> Outcome.BUSY;
            default -> Outcome.CONFIRMED;
        };
    }

    private void recordDiscoveryCounts(
            ConsumptionWorker.Binding binding, DiscoveryReports.Progress result) {
        worker.event(
                binding, Phase.DISCOVERY_EXAMINED, Outcome.CONFIRMED, result.examined(), Duration.ZERO);
        worker.event(
                binding, Phase.DISCOVERY_ADVANCED, Outcome.CONFIRMED, result.advanced(), Duration.ZERO);
        worker.event(
                binding,
                Phase.DISCOVERY_ADMITTED,
                Outcome.CONFIRMED,
                result.committedAdmissions(),
                Duration.ZERO);
    }

    private static boolean hasDiscoveryProgress(DiscoveryReports.Progress result) {
        if (result.committedAdmissions() > 0 || result.advanced() > 0) return true;
        return result.status() == DiscoveryReports.Status.COMMITTED && result.examined() > 0;
    }
}
