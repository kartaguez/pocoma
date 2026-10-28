package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.WorkerCapacity;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;

import io.micrometer.core.instrument.*;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Local bounded telemetry. Capacity callbacks use pre-registered counters only, without SQL/IO. */
public final class MicrometerV3ConsumptionObservation implements ConsumptionObservation {
    private final MeterRegistry registry;
    private final Map<ConsumptionId, String> names;
    private final Map<ConsumptionId, String> authorities;
    private final Map<ConsumptionId, Counter[]> capacityChanges;
    private final Map<ConsumptionId, AtomicLong> pending = new HashMap<>();
    private static final Duration[] BUCKETS = {
        Duration.ofMillis(1),
        Duration.ofMillis(5),
        Duration.ofMillis(10),
        Duration.ofMillis(50),
        Duration.ofMillis(100),
        Duration.ofMillis(500),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofSeconds(5),
        Duration.ofSeconds(30),
        Duration.ofSeconds(60),
        Duration.ofSeconds(120)
    };

    public MicrometerV3ConsumptionObservation(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
        String[] canonical = {
            "Command",
            "IdentityRequest",
            "PotVersion",
            "PotBalance",
            "PotVisibility",
            "Invitation",
            "CurrentBinding",
            "CommandResult",
            "IdentityRequestResult"
        };
        var labels = new LinkedHashMap<ConsumptionId, String>();
        var authorityLabels = new LinkedHashMap<ConsumptionId, String>();
        var defs = new ArrayList<>(V3ConsumptionCatalogConfiguration.definitions());
        defs.addAll(V3ConsumptionWorkerSettings.probeDefinitions());
        for (int i = 0; i < defs.size(); i++) {
            var d = defs.get(i);
            labels.put(
                    d.id(),
                    i < 9 ? canonical[i] : i == 9 ? "I5ReadPotProbe" : "I5ReadCompletionProbe");
            authorityLabels.put(d.id(), d.authority().name().toLowerCase(Locale.ROOT));
        }
        names = Map.copyOf(labels);
        authorities = Map.copyOf(authorityLabels);
        var changes = new HashMap<ConsumptionId, Counter[]>();
        for (var e : names.entrySet())
            changes.put(
                    e.getKey(),
                    new Counter[] {
                        registry.counter(
                                "pocoma.v3.capacity.changes",
                                "consumption",
                                e.getValue(),
                                "kind",
                                "reserved"),
                        registry.counter(
                                "pocoma.v3.capacity.changes",
                                "consumption",
                                e.getValue(),
                                "kind",
                                "returned")
                    });
        capacityChanges = Map.copyOf(changes);
    }

    public void bindCapacity(WorkerCapacity capacity, Map<ConsumptionId, Integer> limits) {
        Gauge.builder("pocoma.v3.capacity.used", capacity, c -> c.snapshot().worker())
                .tag("scope", "worker")
                .register(registry);
        Gauge.builder("pocoma.v3.capacity.limit", capacity, WorkerCapacity::workerLimit)
                .tag("scope", "worker")
                .register(registry);
        for (var entry : limits.entrySet()) {
            var id = entry.getKey();
            var name = names.get(id);
            if (name == null) throw new IllegalArgumentException("unknown capacity label");
            Gauge.builder(
                            "pocoma.v3.capacity.used",
                            capacity,
                            c -> c.snapshot().consumptions().getOrDefault(id, 0))
                    .tag("scope", name)
                    .register(registry);
            Gauge.builder("pocoma.v3.capacity.limit", entry, Map.Entry::getValue)
                    .tag("scope", name)
                    .register(registry);
            var count = new AtomicLong();
            pending.put(id, count);
            Gauge.builder("pocoma.v3.physical.pending", count, AtomicLong::get)
                    .tag("consumption", name)
                    .register(registry);
        }
    }

    public void bind(V3ConsumptionWorker worker) {
        for (var state : V3ConsumptionWorker.State.values())
            Gauge.builder(
                            "pocoma.v3.worker.state",
                            worker,
                            w -> w.snapshot().state() == state ? 1 : 0)
                    .tag("state", state.name().toLowerCase(Locale.ROOT))
                    .register(registry);
        for (var authority : ConsumptionDefinition.Authority.values()) {
            String a = authority.name().toLowerCase(Locale.ROOT);
            Gauge.builder(
                            "pocoma.v3.lease.local",
                            worker,
                            w -> w.localLeases().getOrDefault(authority, 0L))
                    .tag("authority", a)
                    .register(registry);
            registry.counter(
                    "pocoma.v3.takeover.observed",
                    "authority",
                    a); // No transition inferred from acquisition.
            for (String kind : List.of("lease", "claim"))
                DistributionSummary.builder("pocoma.v3.renewal.margin")
                        .baseUnit("seconds")
                        .tags("authority", a, "kind", kind)
                        .register(registry);
            // No runtime margin samples: these ports do not return a coherent DB clock. The SQL
            // certification observer measures them independently, never from the JVM wall clock.
            for (int lane = 0; lane < 3; lane++)
                for (boolean unknown : List.of(false, true)) {
                    int selected = lane;
                    Gauge.builder(
                                    "pocoma.v3.renewal.inflight",
                                    worker,
                                    w ->
                                            w.renewalSnapshot().stream()
                                                    .filter(
                                                            c ->
                                                                    c.authority() == authority
                                                                            && c.lane() == selected
                                                                            && c.unknown()
                                                                                    == unknown)
                                                    .count())
                            .tags(
                                    "authority",
                                    a,
                                    "lane",
                                    Integer.toString(lane),
                                    "kind",
                                    unknown ? "unknown" : "active")
                            .register(registry);
                }
        }
    }

    private void count(String metric, long amount, Tags tags) {
        if (amount > 0) registry.counter("pocoma.v3." + metric, tags).increment(amount);
    }

    private void time(String metric, Duration elapsed, Tags tags) {
        io.micrometer.core.instrument.Timer.builder("pocoma.v3." + metric)
                .tags(tags)
                .publishPercentileHistogram()
                .serviceLevelObjectives(BUCKETS)
                .register(registry)
                .record(elapsed);
    }

    @Override
    public void record(
            ConsumptionId id, Phase phase, Outcome outcome, long amount, Duration elapsed) {
        var name = names.get(id);
        if (name == null) throw new IllegalArgumentException("unknown metric Consumption");
        if (phase == Phase.CAPACITY_RESERVED || phase == Phase.CAPACITY_RETURNED) {
            // Called inside WorkerCapacity's monitor: no registry registration/lookup or other
            // lock.
            capacityChanges.get(id)[phase == Phase.CAPACITY_RESERVED ? 0 : 1].increment(amount);
            return;
        }
        if (phase == Phase.PENDING) {
            var value = pending.get(id);
            if (value != null) value.addAndGet(amount);
            return;
        }
        var c = Tags.of("consumption", name);
        var tags = c.and("outcome", outcome.name().toLowerCase(Locale.ROOT));
        switch (phase) {
            case ATTEMPT_STARTED -> count("attempt.started", amount, c);
            case ATTEMPT -> {
                count("attempt.outcomes", 1, tags);
                time("attempt.duration", elapsed, tags);
            }
            case PREPARE, FINALIZE, OBSERVE ->
                    time(
                            "attempt.phase.duration",
                            elapsed,
                            tags.and("phase", phase.name().toLowerCase(Locale.ROOT)));
            case CESSION -> {
                if (outcome == Outcome.CONFIRMED) count("retry.cessions", amount, c);
                time("attempt.phase.duration", elapsed, tags.and("phase", "cession"));
            }
            case QUARANTINE -> count("quarantine.transitions", amount, c);
            case ACQUIRE, CLAIM_RENEW, CLAIM_LOST -> {
                String operation =
                        phase == Phase.ACQUIRE
                                ? "acquire"
                                : phase == Phase.CLAIM_RENEW ? "renew" : "lost";
                count("claim.operations", amount, tags.and("operation", operation));
                if (phase == Phase.ACQUIRE) time("claim.acquisition.duration", elapsed, tags);
            }
            case ACQUISITION_EXAMINED -> count("slot.acquisition.examined", amount, c);
            case NAVIGATION -> {
                count("slot.navigation.keys", amount, c);
                count("slot.navigation.pages", 1, c);
                time("slot.navigation.duration", elapsed, tags);
            }
            case LEASE_ACQUIRE, LEASE_RENEW, LEASE_SUSPEND ->
                    count(
                            "lease.operations",
                            1,
                            Tags.of(
                                    "authority",
                                    authorities.get(id),
                                    "operation",
                                    phase == Phase.LEASE_ACQUIRE
                                            ? "acquire"
                                            : phase == Phase.LEASE_RENEW ? "renew" : "suspend",
                                    "outcome",
                                    outcome.name().toLowerCase(Locale.ROOT)));
            case DISCOVERY_EXAMINED, DISCOVERY_ADVANCED, DISCOVERY_ADMITTED ->
                    count(
                            "discovery.references",
                            amount,
                            c.and(
                                    "kind",
                                    phase == Phase.DISCOVERY_EXAMINED
                                            ? "examined"
                                            : phase == Phase.DISCOVERY_ADVANCED
                                                    ? "advanced"
                                                    : "admitted"));
            case DISCOVERY -> time("discovery.duration", elapsed, tags);
            case OPPORTUNITY -> time("consumption.opportunity.duration", elapsed, tags);
            case OPPORTUNITY_WAIT -> time("consumption.opportunity.wait", elapsed, c);
            case DISCOVERY_ROTATION, CONSUMPTION_ROTATION, DISCOVERY_TOUR, CONSUMPTION_TOUR ->
                    count(
                            phase == Phase.DISCOVERY_TOUR || phase == Phase.CONSUMPTION_TOUR
                                    ? "rotation.tours"
                                    : "rotation.opportunities",
                            amount,
                            c.and(
                                    "loop",
                                    phase == Phase.DISCOVERY_ROTATION
                                                    || phase == Phase.DISCOVERY_TOUR
                                            ? "discovery"
                                            : "consumption"));
            case DISCOVERY_CYCLE, CONSUMPTION_CYCLE -> {
                var cycle =
                        Tags.of(
                                "loop",
                                phase == Phase.DISCOVERY_CYCLE ? "discovery" : "consumption",
                                "outcome",
                                outcome.name().toLowerCase(Locale.ROOT));
                count("worker.cycles", 1, cycle);
                time("worker.cycle.duration", elapsed, cycle);
            }
            case RENEWAL_S1,
                    RENEWAL_S2,
                    RENEWAL_LEASE,
                    RENEWAL_LAG_S1,
                    RENEWAL_LAG_S2,
                    RENEWAL_LAG_LEASE -> {
                int lane =
                        switch (phase) {
                            case RENEWAL_S1, RENEWAL_LAG_S1 -> 0;
                            case RENEWAL_S2, RENEWAL_LAG_S2 -> 1;
                            default -> 2;
                        };
                boolean lag =
                        phase == Phase.RENEWAL_LAG_S1
                                || phase == Phase.RENEWAL_LAG_S2
                                || phase == Phase.RENEWAL_LAG_LEASE;
                time(
                        lag ? "renewal.schedule.lag" : "renewal.duration",
                        elapsed,
                        Tags.of(
                                "authority",
                                authorities.get(id),
                                "lane",
                                Integer.toString(lane),
                                "outcome",
                                outcome.name().toLowerCase(Locale.ROOT)));
            }
            case SATURATION_WORKER,
                            SATURATION_CONSUMPTION,
                            SATURATION_EXECUTOR,
                            SATURATION_SOFTCAP ->
                    count(
                            "saturation",
                            1,
                            c.and(
                                    "kind",
                                    phase == Phase.SATURATION_WORKER
                                            ? "worker"
                                            : phase == Phase.SATURATION_CONSUMPTION
                                                    ? "consumption"
                                                    : phase == Phase.SATURATION_EXECUTOR
                                                            ? "executor"
                                                            : "softcap"));
            case DISPATCH_REJECTED, DISPATCH_STOPPED, DISPATCH_SATURATED ->
                    count(
                            "dispatch.rejected",
                            1,
                            c.and(
                                    "reason",
                                    phase == Phase.DISPATCH_STOPPED
                                            ? "stopped"
                                            : phase == Phase.DISPATCH_SATURATED
                                                    ? "saturated"
                                                    : "race"));
            case SETTLE -> count("settle.released", amount, c);
            case DRAIN -> {
                if (outcome == Outcome.UNKNOWN) count("drain.deadline", 1, Tags.empty());
                time(
                        "drain.duration",
                        elapsed,
                        Tags.of("outcome", outcome == Outcome.UNKNOWN ? "deadline" : "completed"));
            }
            default -> {}
        }
        if (outcome == Outcome.UNKNOWN) {
            String physical =
                    switch (phase) {
                        case ACQUIRE -> "acquire";
                        case PREPARE, FINALIZE -> "attempt";
                        case CESSION -> "cession";
                        case RENEWAL_S1, RENEWAL_S2, RENEWAL_LEASE -> "renewal";
                        default -> null;
                    };
            if (physical != null) count("physical.unknown", 1, c.and("phase", physical));
        }
    }
}
