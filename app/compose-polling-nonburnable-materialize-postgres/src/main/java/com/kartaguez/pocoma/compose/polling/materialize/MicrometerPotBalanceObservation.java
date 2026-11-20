package com.kartaguez.pocoma.compose.polling.materialize;

import com.kartaguez.pocoma.engine.materialize.pot.PotBalanceObservation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Micrometer metrics for PotBalance N-m incremental materialization (bounded low-cardinality tags). */
public final class MicrometerPotBalanceObservation implements PotBalanceObservation {
    private static final Duration[] BUCKETS = {
        Duration.ofMillis(1),
        Duration.ofMillis(5),
        Duration.ofMillis(10),
        Duration.ofMillis(25),
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(250),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2)
    };

    private final Map<LookupOutcome, Counter> lookupCounters = new EnumMap<>(LookupOutcome.class);
    private final Map<Path, Map<Reason, Counter>> pathCounters = new EnumMap<>(Path.class);
    private final Map<PersistTarget, Map<ContentOutcome, Counter>> contentWriteCounters =
            new EnumMap<>(PersistTarget.class);
    private final DistributionSummary referenceDistance;
    private final DistributionSummary deltaExpenses;
    private final DistributionSummary aggregateBuckets;
    private final DistributionSummary aggregateEntries;
    private final Timer lookupTimer;
    private final Timer deltaTimer;
    private final Timer composeTimer;
    private final Map<Path, Timer> evaluateTimers = new EnumMap<>(Path.class);
    private final Map<PersistTarget, Timer> persistTimers = new EnumMap<>(PersistTarget.class);
    private final Map<PersistTarget, Timer> encodeTimers = new EnumMap<>(PersistTarget.class);

    public MicrometerPotBalanceObservation(MeterRegistry registry, Tags scope) {
        Objects.requireNonNull(registry);
        Objects.requireNonNull(scope);
        for (var outcome : LookupOutcome.values()) {
            lookupCounters.put(
                    outcome,
                    Counter.builder("pocoma.v3.potbalance.aggregate.lookup")
                            .tags(scope)
                            .tag("outcome", label(outcome))
                            .register(registry));
        }
        for (var path : Path.values()) {
            var byReason = new EnumMap<Reason, Counter>(Reason.class);
            for (var reason : Reason.values()) {
                byReason.put(
                        reason,
                        Counter.builder("pocoma.v3.potbalance.materialize.path")
                                .tags(scope)
                                .tag("path", label(path))
                                .tag("reason", label(reason))
                                .register(registry));
            }
            pathCounters.put(path, byReason);
            evaluateTimers.put(
                    path,
                    Timer.builder("pocoma.v3.potbalance.evaluate.duration")
                            .tags(scope)
                            .tag("path", label(path))
                            .serviceLevelObjectives(BUCKETS)
                            .register(registry));
        }
        for (var target : PersistTarget.values()) {
            persistTimers.put(
                    target,
                    Timer.builder("pocoma.v3.potbalance.persist.duration")
                            .tags(scope)
                            .tag("target", label(target))
                            .serviceLevelObjectives(BUCKETS)
                            .register(registry));
            encodeTimers.put(
                    target,
                    Timer.builder("pocoma.v3.potbalance.encode.duration")
                            .tags(scope)
                            .tag("target", label(target))
                            .serviceLevelObjectives(BUCKETS)
                            .register(registry));
            var byOutcome = new EnumMap<ContentOutcome, Counter>(ContentOutcome.class);
            for (var outcome : ContentOutcome.values()) {
                byOutcome.put(
                        outcome,
                        Counter.builder("pocoma.v3.potbalance.content.write")
                                .tags(scope)
                                .tag("target", label(target))
                                .tag("outcome", label(outcome))
                                .register(registry));
            }
            contentWriteCounters.put(target, byOutcome);
        }
        this.referenceDistance =
                DistributionSummary.builder("pocoma.v3.potbalance.reference.distance")
                        .tags(scope)
                        .serviceLevelObjectives(1, 2, 4, 8, 16, 32, 64, 128)
                        .register(registry);
        this.deltaExpenses =
                DistributionSummary.builder("pocoma.v3.potbalance.delta.expenses")
                        .tags(scope)
                        .serviceLevelObjectives(1, 2, 5, 16, 32, 64, 128)
                        .register(registry);
        this.aggregateBuckets =
                DistributionSummary.builder("pocoma.v3.potbalance.aggregate.buckets")
                        .tags(scope)
                        .serviceLevelObjectives(1, 5, 16, 64, 128, 256)
                        .register(registry);
        this.aggregateEntries =
                DistributionSummary.builder("pocoma.v3.potbalance.aggregate.entries")
                        .tags(scope)
                        .serviceLevelObjectives(10, 50, 100, 500, 2000, 10000)
                        .register(registry);
        this.lookupTimer =
                Timer.builder("pocoma.v3.potbalance.aggregate.lookup.duration")
                        .tags(scope)
                        .serviceLevelObjectives(BUCKETS)
                        .register(registry);
        this.deltaTimer =
                Timer.builder("pocoma.v3.potbalance.delta.duration")
                        .tags(scope)
                        .serviceLevelObjectives(BUCKETS)
                        .register(registry);
        this.composeTimer =
                Timer.builder("pocoma.v3.potbalance.compose.duration")
                        .tags(scope)
                        .serviceLevelObjectives(BUCKETS)
                        .register(registry);
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public void aggregateLookup(LookupOutcome outcome, long distance, Duration duration) {
        lookupCounters.get(outcome).increment();
        lookupTimer.record(duration);
        if (outcome == LookupOutcome.HIT && distance > 0) {
            referenceDistance.record(distance);
        }
    }

    @Override
    public void deltaResolved(int changedExpenses, Duration duration) {
        deltaExpenses.record(changedExpenses);
        deltaTimer.record(duration);
    }

    @Override
    public void aggregateComputed(int bucketCount, int bucketEntryCount, Duration duration) {
        aggregateBuckets.record(bucketCount);
        aggregateEntries.record(bucketEntryCount);
        composeTimer.record(duration);
    }

    @Override
    public void balanceEvaluated(Path path, Duration duration) {
        evaluateTimers.get(path).record(duration);
    }

    @Override
    public void persisted(PersistTarget target, Duration duration) {
        persistTimers.get(target).record(duration);
    }

    @Override
    public void pathSelected(Path path, Reason reason) {
        pathCounters.get(path).get(reason).increment();
    }

    @Override
    public void contentWritten(PersistTarget target, ContentOutcome outcome) {
        contentWriteCounters.get(target).get(outcome).increment();
    }

    @Override
    public void contentEncoded(PersistTarget target, Duration duration) {
        encodeTimers.get(target).record(duration);
    }
}
