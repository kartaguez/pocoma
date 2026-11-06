package com.kartaguez.pocoma.runtime.consumption.v3.maintenance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer metrics instrumentation for READ rebuild and maintenance operations.
 */
public final class ReadRebuildObservation {

    private final MeterRegistry registry;
    private final AtomicInteger stateGauge = new AtomicInteger(0); // 0=READY, 1=MAINTENANCE, 2=REBUILDING, 3=FAILED
    private final AtomicInteger satisfiedCheckpoints = new AtomicInteger(28);
    private final AtomicInteger remainingSlots = new AtomicInteger(0);
    private final AtomicLong rebuildDurationNanos = new AtomicLong(0);
    private final Counter errorCounter;
    private final Timer rebuildTimer;

    public ReadRebuildObservation(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry cannot be null");

        Gauge.builder("pocoma.v3.read.rebuild.state", stateGauge, AtomicInteger::get)
                .description("Current READ rebuild state (0=READY, 1=MAINTENANCE, 2=REBUILDING, 3=FAILED)")
                .register(registry);

        Gauge.builder("pocoma.v3.read.rebuild.checkpoints.satisfied", satisfiedCheckpoints, AtomicInteger::get)
                .description("Number of satisfied consumption checkpoints towards target prefix B (out of 28)")
                .register(registry);

        Gauge.builder("pocoma.v3.read.rebuild.slots.remaining", remainingSlots, AtomicInteger::get)
                .description("Number of residual consumption slots less than or equal to target prefix B")
                .register(registry);

        Gauge.builder("pocoma.v3.read.rebuild.duration.seconds", rebuildDurationNanos, n -> n.get() / 1_000_000_000.0)
                .description("Duration of the active READ reconstruction operation in seconds")
                .register(registry);

        this.errorCounter = Counter.builder("pocoma.v3.read.rebuild.errors")
                .description("Total number of errors encountered during READ reconstruction")
                .register(registry);

        this.rebuildTimer = Timer.builder("pocoma.v3.read.rebuild.operation.duration")
                .description("Total elapsed time of completed READ reconstruction operations")
                .publishPercentileHistogram()
                .register(registry);
    }

    public void updateState(ReadMaintenanceState.State state) {
        int code = switch (state) {
            case READY -> 0;
            case MAINTENANCE -> 1;
            case REBUILDING -> 2;
            case FAILED -> 3;
        };
        stateGauge.set(code);
    }

    public void updateProgress(int satisfiedCp, int residualSlots, Duration currentDuration) {
        satisfiedCheckpoints.set(satisfiedCp);
        remainingSlots.set(residualSlots);
        if (currentDuration != null) {
            rebuildDurationNanos.set(currentDuration.toNanos());
        }
    }

    public void recordError() {
        errorCounter.increment();
    }

    public void recordCompletion(Duration totalDuration) {
        updateState(ReadMaintenanceState.State.READY);
        satisfiedCheckpoints.set(28);
        remainingSlots.set(0);
        if (totalDuration != null) {
            rebuildDurationNanos.set(totalDuration.toNanos());
            rebuildTimer.record(totalDuration);
        }
    }
}
