package com.kartaguez.pocoma.compose.polling.materialize;

import com.kartaguez.pocoma.engine.materialize.pot.PotVersionObservation;
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

/** Micrometer metrics for PotVersion N-m incremental materialization (bounded low-cardinality tags). */
public final class MicrometerPotVersionObservation implements PotVersionObservation {
    private static final Duration[] BUCKETS = {
        Duration.ofMillis(1),
        Duration.ofMillis(5),
        Duration.ofMillis(10),
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(250),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5)
    };

    private final Map<LookupOutcome, Counter> lookupCounters = new EnumMap<>(LookupOutcome.class);
    private final Map<Path, Map<Reason, Counter>> pathCounters = new EnumMap<>(Path.class);
    private final DistributionSummary referenceDistance;
    private final DistributionSummary deltaSubobjects;
    private final DistributionSummary nodesInserted;
    private final DistributionSummary nodesLoaded;
    private final DistributionSummary nodesReused;
    private final Timer referenceLookupTimer;
    private final Timer deltaResolutionTimer;
    private final Timer composeTimer;
    private final Map<Path, Timer> treeBuildTimers = new EnumMap<>(Path.class);
    private final Map<Path, Timer> persistPublishTimers = new EnumMap<>(Path.class);

    public MicrometerPotVersionObservation(MeterRegistry registry, Tags scope) {
        Objects.requireNonNull(registry);
        Objects.requireNonNull(scope);
        for (var outcome : LookupOutcome.values()) {
            lookupCounters.put(
                    outcome,
                    Counter.builder("pocoma.v3.potversion.reference.lookup")
                            .tags(scope)
                            .tag("outcome", label(outcome))
                            .register(registry));
        }
        for (var path : Path.values()) {
            var byReason = new EnumMap<Reason, Counter>(Reason.class);
            for (var reason : Reason.values()) {
                byReason.put(
                        reason,
                        Counter.builder("pocoma.v3.potversion.materialize.path")
                                .tags(scope)
                                .tag("path", label(path))
                                .tag("reason", label(reason))
                                .register(registry));
            }
            pathCounters.put(path, byReason);
            treeBuildTimers.put(
                    path,
                    Timer.builder("pocoma.v3.potversion.stage.duration")
                            .tags(scope)
                            .tag("stage", label(Stage.TREE_BUILD))
                            .tag("path", label(path))
                            .serviceLevelObjectives(BUCKETS)
                            .register(registry));
            persistPublishTimers.put(
                    path,
                    Timer.builder("pocoma.v3.potversion.stage.duration")
                            .tags(scope)
                            .tag("stage", label(Stage.PERSIST_PUBLISH))
                            .tag("path", label(path))
                            .serviceLevelObjectives(BUCKETS)
                            .register(registry));
        }
        this.referenceDistance =
                DistributionSummary.builder("pocoma.v3.potversion.reference.distance")
                        .tags(scope)
                        .serviceLevelObjectives(1, 2, 4, 8, 16, 32, 64, 128)
                        .register(registry);
        this.deltaSubobjects =
                DistributionSummary.builder("pocoma.v3.potversion.delta.subobjects")
                        .tags(scope)
                        .serviceLevelObjectives(1, 3, 10, 32, 64, 256, 1024)
                        .register(registry);
        this.nodesInserted =
                DistributionSummary.builder("pocoma.v3.potversion.nodes.inserted")
                        .tags(scope)
                        .serviceLevelObjectives(1, 2, 4, 8, 16, 64, 256)
                        .register(registry);
        this.nodesLoaded =
                DistributionSummary.builder("pocoma.v3.potversion.nodes.loaded")
                        .tags(scope)
                        .serviceLevelObjectives(1, 2, 4, 8, 16, 64, 256)
                        .register(registry);
        this.nodesReused =
                DistributionSummary.builder("pocoma.v3.potversion.nodes.reused")
                        .tags(scope)
                        .serviceLevelObjectives(1, 4, 16, 64, 256, 1024)
                        .register(registry);
        this.referenceLookupTimer = stageTimer(registry, scope, Stage.REFERENCE_LOOKUP);
        this.deltaResolutionTimer = stageTimer(registry, scope, Stage.DELTA_RESOLUTION);
        this.composeTimer = stageTimer(registry, scope, Stage.COMPOSE);
    }

    private static Timer stageTimer(MeterRegistry registry, Tags scope, Stage stage) {
        return Timer.builder("pocoma.v3.potversion.stage.duration")
                .tags(scope)
                .tag("stage", label(stage))
                .tag("path", "incremental")
                .serviceLevelObjectives(BUCKETS)
                .register(registry);
    }

    private static String label(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public void referenceLookup(LookupOutcome outcome, long distance, Duration duration) {
        lookupCounters.get(outcome).increment();
        referenceLookupTimer.record(duration);
        if (outcome == LookupOutcome.HIT && distance > 0) {
            referenceDistance.record(distance);
        }
    }

    @Override
    public void deltaResolved(int changedSubobjects, Duration duration) {
        deltaSubobjects.record(changedSubobjects);
        deltaResolutionTimer.record(duration);
    }

    @Override
    public void composed(Duration duration) {
        composeTimer.record(duration);
    }

    @Override
    public void treeBuilt(
            Path path, Duration duration, int inserted, int loaded, int reused) {
        treeBuildTimers.get(path).record(duration);
        nodesInserted.record(inserted);
        nodesLoaded.record(loaded);
        nodesReused.record(reused);
    }

    @Override
    public void pathSelected(Path path, Reason reason) {
        pathCounters.get(path).get(reason).increment();
    }

    @Override
    public void persistedAndPublished(Path path, Duration duration) {
        persistPublishTimers.get(path).record(duration);
    }
}
