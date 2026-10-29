package com.kartaguez.pocoma.orchestrator.poll.consumption.v3;

import com.kartaguez.pocoma.engine.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation.*;

import java.time.Duration;
import java.util.*;

/** One bounded C/p opportunity, independent of business threads and capacity. */
public final class V3DiscoveryLoop {
    private final V3ConsumptionWorker worker;
    private final int[] nextPartitions;
    private final Map<V3ConsumptionWorker.Pair, DiscoveryEngine.RequestWalk> walks =
            new HashMap<>();
    private int nextConsumption;
    private final Set<V3ConsumptionWorker.Pair> saturated = new HashSet<>();

    V3DiscoveryLoop(V3ConsumptionWorker worker) {
        this.worker = worker;
        nextPartitions = new int[worker.bindings.size()];
    }

    public boolean runOnePass() {
        if (!worker.beginOperation()) return false;
        long began = System.nanoTime();
        var binding = worker.bindings.get(nextConsumption);
        int selected = nextConsumption;
        nextConsumption = (nextConsumption + 1) % worker.bindings.size();
        int p = binding.partitions().get(nextPartitions[selected]);
        nextPartitions[selected] = (nextPartitions[selected] + 1) % binding.partitions().size();
        Outcome outcome = Outcome.UNKNOWN;
        Throwable failure = null;
        worker.event(binding, Phase.DISCOVERY_ROTATION, Outcome.CONFIRMED, 1, Duration.ZERO);
        try {
            var lease = worker.lease(binding, p);
            if (lease == null) return false;
            var pair = new V3ConsumptionWorker.Pair(binding, p);
            DiscoveryEngine.Status status;
            int examined, advanced, admitted;
            if (binding.definition().family()
                            == com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily.COMMAND
                    || binding.definition().family()
                            == com.kartaguez.pocoma.domain.consumption.v3.ConsumableFamily
                                    .IDENTITY_REQUEST) {
                var result =
                        binding.discovery()
                                .discoverRequests(
                                        lease,
                                        walks.getOrDefault(
                                                pair, DiscoveryEngine.RequestWalk.fresh()));
                walks.put(
                        pair,
                        result.next()); // Only the confirmed I4 result controls source progress.
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
            if (status == DiscoveryEngine.Status.BUSY_OR_NOT_OWNER) worker.suspend(binding, lease);
            worker.event(
                    binding, Phase.DISCOVERY_EXAMINED, Outcome.CONFIRMED, examined, Duration.ZERO);
            worker.event(
                    binding, Phase.DISCOVERY_ADVANCED, Outcome.CONFIRMED, advanced, Duration.ZERO);
            worker.event(
                    binding, Phase.DISCOVERY_ADMITTED, Outcome.CONFIRMED, admitted, Duration.ZERO);
            return admitted > 0 || advanced > 0;
        } catch (RuntimeException | Error exit) {
            failure = exit;
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
