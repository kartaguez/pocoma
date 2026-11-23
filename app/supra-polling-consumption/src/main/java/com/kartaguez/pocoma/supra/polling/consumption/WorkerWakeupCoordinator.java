package com.kartaguez.pocoma.supra.polling.consumption;

import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupKey;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupMetrics;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupState;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Package-private wakeup subscription and state-transition coordinator for ConsumptionWorker. */
final class WorkerWakeupCoordinator {
    private final ConsumptionWorker.WakeupOptions options;
    private final WakeupMetrics metrics;
    private final AtomicReference<WakeupState> state = new AtomicReference<>(WakeupState.NOOP);

    WorkerWakeupCoordinator(ConsumptionWorker.WakeupOptions options) {
        this.options = options;
        this.metrics = options.metrics();
    }

    WakeupState state() {
        return state.get();
    }

    ConsumptionWorker.WakeupOptions options() {
        return options;
    }

    void start(
            List<ConsumptionWorker.Binding> bindings,
            DiscoveryLoop discovery,
            Runnable signalDiscovery,
            Runnable signalConsumption) {
        var keys = new HashSet<WakeupKey>();
        for (var b : bindings) {
            for (int p : b.partitions()) {
                if (p >= 0 && p < WakeupKey.DEFAULT_PARTITION_COUNT) {
                    keys.add(new WakeupKey(b.definition().family(), p));
                }
            }
        }
        try {
            options.subscriber()
                    .start(
                            Set.copyOf(keys),
                            key -> discovery.onExternalWakeup(key.family(), key.partition()),
                            next -> onStateChanged(next, discovery, signalDiscovery, signalConsumption));
        } catch (Throwable ignored) {
            onStateChanged(WakeupState.DEGRADED, discovery, signalDiscovery, signalConsumption);
        }
    }

    void closeQuietly() {
        try {
            options.subscriber().close();
        } catch (Throwable ignored) {
        }
    }

    private void onStateChanged(
            WakeupState next,
            DiscoveryLoop discovery,
            Runnable signalDiscovery,
            Runnable signalConsumption) {
        if (next == null) return;
        WakeupState prev = state.getAndSet(next);
        metrics.onStateChanged(next);
        if (next == WakeupState.DEGRADED && prev != WakeupState.DEGRADED) {
            metrics.onFallbackTransition();
            signalDiscovery.run();
        } else if (next == WakeupState.CONNECTED && prev != WakeupState.CONNECTED) {
            if (prev == WakeupState.DEGRADED) {
                metrics.onReconnect();
            }
            discovery.onExternalReconnect();
            signalConsumption.run();
        }
    }
}
