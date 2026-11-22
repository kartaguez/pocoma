package com.kartaguez.pocoma.infra.observability.micrometer;

import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupMetrics;
import com.kartaguez.pocoma.contract.consumption.wakeup.WakeupState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Low-cardinality Micrometer metrics for Core NATS event-driven worker wakeup. */
public final class MicrometerWakeupMetrics implements WakeupMetrics {
    private final Counter published;
    private final Counter coalesced;
    private final Counter publishErrors;
    private final Counter received;
    private final Counter useful;
    private final Counter spurious;
    private final Counter ignoredNoLease;
    private final Counter fallbackTransitions;
    private final Counter reconnects;
    private final Timer discoveryDelay;
    private final AtomicInteger stateCode = new AtomicInteger(WakeupState.NOOP.code());

    public MicrometerWakeupMetrics(MeterRegistry registry, Tags tags) {
        this.published = Counter.builder("pocoma.v3.wakeup.published").tags(tags).register(registry);
        this.coalesced = Counter.builder("pocoma.v3.wakeup.coalesced").tags(tags).register(registry);
        this.publishErrors =
                Counter.builder("pocoma.v3.wakeup.publish.errors").tags(tags).register(registry);
        this.received = Counter.builder("pocoma.v3.wakeup.received").tags(tags).register(registry);
        this.useful = Counter.builder("pocoma.v3.wakeup.useful").tags(tags).register(registry);
        this.spurious = Counter.builder("pocoma.v3.wakeup.spurious").tags(tags).register(registry);
        this.ignoredNoLease =
                Counter.builder("pocoma.v3.wakeup.ignored.no_lease").tags(tags).register(registry);
        this.fallbackTransitions =
                Counter.builder("pocoma.v3.wakeup.fallback.transitions")
                        .tags(tags)
                        .register(registry);
        this.reconnects =
                Counter.builder("pocoma.v3.wakeup.reconnects").tags(tags).register(registry);
        this.discoveryDelay =
                Timer.builder("pocoma.v3.wakeup.discovery.delay")
                        .description("Elapsed time from wakeup notification receipt to discovery pass start")
                        .tags(tags)
                        .register(registry);
        Gauge.builder("pocoma.v3.wakeup.state", stateCode, AtomicInteger::get)
                .tags(tags)
                .register(registry);
    }

    @Override
    public void onPublished() {
        published.increment();
    }

    @Override
    public void onCoalesced() {
        coalesced.increment();
    }

    @Override
    public void onPublishError() {
        publishErrors.increment();
    }

    @Override
    public void onReceived() {
        received.increment();
    }

    @Override
    public void onWakeupUseful() {
        useful.increment();
    }

    @Override
    public void onWakeupSpurious() {
        spurious.increment();
    }

    @Override
    public void onWakeupIgnoredNoLease() {
        ignoredNoLease.increment();
    }

    @Override
    public void onFallbackTransition() {
        fallbackTransitions.increment();
    }

    @Override
    public void onReconnect() {
        reconnects.increment();
    }

    @Override
    public void onStateChanged(WakeupState state) {
        if (state != null) stateCode.set(state.code());
    }

    @Override
    public void onDiscoveryDelay(long nanos) {
        if (nanos >= 0) discoveryDelay.record(nanos, TimeUnit.NANOSECONDS);
    }
}
