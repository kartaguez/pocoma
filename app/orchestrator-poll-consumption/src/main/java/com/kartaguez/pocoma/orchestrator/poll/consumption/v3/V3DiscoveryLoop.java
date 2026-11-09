package com.kartaguez.pocoma.orchestrator.poll.consumption.v3;

import com.kartaguez.pocoma.engine.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation.*;

import java.time.Duration;
import java.util.*;

/** One bounded C/p opportunity, independent of business threads and capacity. */
public final class V3DiscoveryLoop {
    private final V3ConsumptionWorker worker;
    private final int[] nextPartitions;
    private final long[] errorUntilNanos;
    private int nextConsumption;
    private final Set<V3ConsumptionWorker.Pair> saturated = new HashSet<>();

    V3DiscoveryLoop(V3ConsumptionWorker worker) {
        this.worker = worker;
        nextPartitions = new int[worker.bindings.size()];
        errorUntilNanos = new long[worker.bindings.size()];
    }

    boolean hasReadyBinding() {
        long now = System.nanoTime();
        for (long until : errorUntilNanos) if (now - until >= 0) return true;
        return false;
    }

    public boolean runOnePass() {
        if (!worker.beginOperation()) return false;
        long began = System.nanoTime();
        int selected = nextConsumption;
        if (worker.bindings.size() > 1) {
            for (int i = 0; i < worker.bindings.size(); i++) {
                int candidate = (nextConsumption + i) % worker.bindings.size();
                if (began - errorUntilNanos[candidate] >= 0) {
                    selected = candidate;
                    break;
                }
            }
        }
        var binding = worker.bindings.get(selected);
        nextConsumption = (selected + 1) % worker.bindings.size();
        int p = binding.partitions().get(nextPartitions[selected]);
        nextPartitions[selected] = (nextPartitions[selected] + 1) % binding.partitions().size();
        Outcome outcome = Outcome.UNKNOWN;
        Throwable failure = null;
        worker.event(binding, Phase.DISCOVERY_ROTATION, Outcome.CONFIRMED, 1, Duration.ZERO);
        try {
            var pair = new V3ConsumptionWorker.Pair(binding, p);
            DiscoveryEngine.Status status;
            int examined, advanced, admitted;
            synchronized (worker.pairLock(pair)) {
                var lease = worker.lease(binding, p);
                if (lease == null) return false;
                if (binding.definition().family()
                                == com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily
                                        .COMMAND
                        || binding.definition().family()
                                == com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily
                                        .IDENTITY_REQUEST) {
                    var result = binding.discovery().discoverRequests(lease);
                    status = result.status();
                    examined = result.examined();
                    advanced = result.advanced();
                    admitted = result.committedAdmissions();
                } else {
                    var result = binding.discovery().discoverEvents(lease);
                    status = result.status();
                    examined = result.examined();
                    advanced = 0;
                    admitted = result.inserted();
                }
                if (status == DiscoveryEngine.Status.BUSY_OR_NOT_OWNER)
                    worker.suspend(binding, lease);
            }
            if (admitted > 0) worker.consumptionWait.signal();
            if (status == DiscoveryEngine.Status.CAPACITY) {
                if (saturated.add(pair))
                    worker.event(
                            binding, Phase.SATURATION_SOFTCAP, Outcome.CAPACITY, 1, Duration.ZERO);
            } else saturated.remove(pair);
            outcome =
                    switch (status) {
                        case CAPACITY -> Outcome.CAPACITY;
                        case BUSY_OR_NOT_OWNER -> Outcome.BUSY;
                        default -> Outcome.CONFIRMED;
                    };
            worker.event(
                    binding, Phase.DISCOVERY_EXAMINED, Outcome.CONFIRMED, examined, Duration.ZERO);
            worker.event(
                    binding, Phase.DISCOVERY_ADVANCED, Outcome.CONFIRMED, advanced, Duration.ZERO);
            worker.event(
                    binding, Phase.DISCOVERY_ADMITTED, Outcome.CONFIRMED, admitted, Duration.ZERO);
            errorUntilNanos[selected] = 0;
            return admitted > 0
                    || advanced > 0
                    || (status == DiscoveryEngine.Status.COMMITTED && examined > 0);
        } catch (RuntimeException | Error exit) {
            failure = exit;
            errorUntilNanos[selected] =
                    System.nanoTime() + worker.settings.errorPoll().toNanos();
            throw exit;
        } finally {
            worker.event(
                    binding,
                    Phase.DISCOVERY,
                    outcome,
                    1,
                    Duration.ofNanos(System.nanoTime() - began));
            worker.endOperation(failure);
        }
    }
}
