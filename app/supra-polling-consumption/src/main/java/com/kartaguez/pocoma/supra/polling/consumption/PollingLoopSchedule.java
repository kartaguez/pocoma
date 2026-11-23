package com.kartaguez.pocoma.supra.polling.consumption;

import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupState;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Package-private polling backoff and wakeup-aware delay calculator for ConsumptionWorker loops. */
final class PollingLoopSchedule {
    private final int tour;
    private final Duration stepPoll;
    private final Duration minPoll;
    private final Duration maxPoll;
    private final ConsumptionWorker.WakeupOptions wakeupOptions;
    private final Supplier<WakeupState> wakeupState;
    private final BooleanSupplier hasPendingWakeup;
    private Duration backoff;
    private int remaining;
    private boolean tourProgressed;

    PollingLoopSchedule(
            List<ConsumptionWorker.Binding> bindings,
            ConsumptionWorker.Settings settings,
            ConsumptionWorker.WakeupOptions wakeupOptions,
            Supplier<WakeupState> wakeupState,
            BooleanSupplier hasPendingWakeup) {
        this.tour =
                bindings.size()
                        * bindings.stream()
                                .mapToInt(b -> b.partitions().size())
                                .max()
                                .orElseThrow();
        this.minPoll = settings.minPoll();
        this.maxPoll = settings.maxPoll();
        this.stepPoll = minPoll.dividedBy(tour);
        this.wakeupOptions = wakeupOptions;
        this.wakeupState = wakeupState;
        this.hasPendingWakeup = hasPendingWakeup;
        this.backoff = minPoll;
        this.remaining = tour;
    }

    Duration stepPoll() {
        return stepPoll;
    }

    Duration nextDelay(boolean progressed, boolean isDiscovery) {
        if (isDiscovery && hasPendingWakeup.getAsBoolean()) {
            backoff = minPoll;
            return Duration.ZERO;
        }
        Duration effectiveMax = effectiveMaxPoll(isDiscovery);
        if (backoff.compareTo(effectiveMax) > 0) {
            backoff = effectiveMax;
        }
        Duration delay = stepPoll;
        if (progressed) {
            delay = Duration.ZERO;
            backoff = minPoll;
            tourProgressed = true;
        }
        if (--remaining == 0) {
            remaining = tour;
            if (tourProgressed) {
                tourProgressed = false;
            } else {
                delay = backoff;
                Duration doubled = backoff.multipliedBy(2);
                backoff = doubled.compareTo(effectiveMax) > 0 ? effectiveMax : doubled;
            }
        }
        return delay;
    }

    private Duration effectiveMaxPoll(boolean isDiscovery) {
        WakeupState st = wakeupState.get();
        if (isDiscovery) {
            return switch (st) {
                case CONNECTED -> wakeupOptions.reconcilePoll();
                case DEGRADED -> wakeupOptions.fallbackMaxPoll();
                case NOOP -> maxPoll;
            };
        }
        return st == WakeupState.CONNECTED ? wakeupOptions.consumptionMaxPoll() : maxPoll;
    }
}
