package com.kartaguez.pocoma.runtime.command.v3;

import static com.kartaguez.pocoma.runtime.command.v3.V3WorkerPostgresTest.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.runtime.consumption.v3.*;

import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.*;

@Testcontainers
class V3WorkerMetricsTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void initialize() throws Exception {
        V3WorkerPostgresTest.initialize(PG);
    }

    static double count(MeterRegistry registry, String metric, String... tags) {
        var counter = registry.find(metric).tags(tags).counter();
        return counter == null ? 0 : counter.count();
    }

    static void startsMatchSql(MeterRegistry registry, Fixture f) {
        int expected =
                f.write.queryForObject("select count(*) from v3_command_result", Integer.class);
        assertEquals(
                expected, count(registry, "pocoma.v3.attempt.started", "consumption", "Command"));
    }

    @Test
    void i5_m01() throws Exception {
        try (var f = new Fixture(PG)) {
            var ctx = f.start();
            var registry = ctx.getBean(MeterRegistry.class);
            for (int n = 0; n < 3; n++) {
                var id = f.inject(10000 + n);
                f.verify(id);
            }
            await(
                    () ->
                            count(
                                            registry,
                                            "pocoma.v3.attempt.outcomes",
                                            "consumption",
                                            "Command",
                                            "outcome",
                                            "committed")
                                    == 3);
            startsMatchSql(registry, f);
            for (String name : List.of("Command", "I5ReadPotProbe", "I5ReadCompletionProbe")) {
                assertEquals(3, count(registry, "pocoma.v3.attempt.started", "consumption", name));
                assertEquals(
                        3,
                        count(
                                registry,
                                "pocoma.v3.claim.operations",
                                "consumption",
                                name,
                                "operation",
                                "acquire",
                                "outcome",
                                "confirmed"));
            }
            await(() -> ctx.getBean(WorkerCapacity.class).snapshot().worker() == 0);
            for (String name : List.of("Command", "I5ReadPotProbe", "I5ReadCompletionProbe")) {
                assertEquals(
                        count(
                                registry,
                                "pocoma.v3.capacity.changes",
                                "consumption",
                                name,
                                "kind",
                                "reserved"),
                        count(
                                registry,
                                "pocoma.v3.capacity.changes",
                                "consumption",
                                name,
                                "kind",
                                "returned"));
                assertEquals(
                        0,
                        registry.get("pocoma.v3.physical.pending")
                                .tag("consumption", name)
                                .gauge()
                                .value());
            }
            var allowed =
                    Set.of(
                            "Command",
                            "IdentityRequest",
                            "PotVersion",
                            "PotBalance",
                            "PotVisibility",
                            "Invitation",
                            "CurrentBinding",
                            "CommandResult",
                            "IdentityRequestResult",
                            "I5ReadPotProbe",
                            "I5ReadCompletionProbe");
            var cardinalities = new HashMap<String, Integer>();
            for (var meter : registry.getMeters()) {
                if (!meter.getId().getName().startsWith("pocoma.v3.")) continue;
                cardinalities.merge(meter.getId().getName(), 1, Integer::sum);
                for (var tag : meter.getId().getTags()) {
                    assertFalse(
                            tag.getValue()
                                    .matches(
                                            ".*[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}.*"));
                    assertTrue(
                            Set.of(
                                            "consumption",
                                            "authority",
                                            "scope",
                                            "kind",
                                            "phase",
                                            "state",
                                            "loop",
                                            "outcome",
                                            "lane",
                                            "operation",
                                            "reason")
                                    .contains(tag.getKey()),
                            tag.toString());
                    if (tag.getKey().equals("consumption"))
                        assertTrue(allowed.contains(tag.getValue()));
                }
            }
            assertTrue(cardinalities.get("pocoma.v3.capacity.changes") <= 22);
            assertEquals(12, cardinalities.get("pocoma.v3.renewal.inflight"));
            assertEquals(4, cardinalities.get("pocoma.v3.worker.state"));
            var worker = ctx.getBean(V3ConsumptionWorker.class);
            worker.requestDrain();
            await(() -> worker.snapshot().state() == V3ConsumptionWorker.State.STOPPED);
            assertEquals(
                    1,
                    registry.get("pocoma.v3.worker.state").tag("state", "stopped").gauge().value());
            f.trace.add(
                    "metric cardinalities="
                            + cardinalities
                            + "; started and successful claims match independent SQL"
                            + " results/effects; capacity changes balance");
            f.evidence("i5_m01");
            ctx.close();
            f.contexts.remove(ctx);
        }
        try (var f = new Fixture(PG);
                var runtime =
                        new V3WorkerPostgresTest.Runtime(
                                f,
                                (id, phase, outcome, n, elapsed) -> {
                                    throw new AssertionError("observer must not affect protocol");
                                })) {
            runtime.worker.start();
            var id = f.inject(10099);
            f.verify(id);
            assertEquals(
                    1,
                    f.write.queryForObject(
                            "select count(*) from v3_command_result", Integer.class));
            f.evidence("i5_m01-observer-failure");
        }
    }

    @Test
    void i5_m02() throws Exception {
        try (var f = new Fixture(PG)) {
            var registry = new SimpleMeterRegistry();
            var metrics = new MicrometerV3ConsumptionObservation(registry);
            ConsumptionObservation dropped =
                    (id, phase, outcome, n, elapsed) -> {
                        if (phase != ConsumptionObservation.Phase.ATTEMPT_STARTED)
                            metrics.record(id, phase, outcome, n, elapsed);
                    };
            try (var runtime = new V3WorkerPostgresTest.Runtime(f, dropped)) {
                metrics.bindCapacity(
                        runtime.capacity,
                        Map.of(
                                V3ConsumptionWorkerSettings.definition("Command").id(),
                                1,
                                V3ConsumptionWorkerSettings.definition("I5ReadPotProbe").id(),
                                2,
                                V3ConsumptionWorkerSettings.definition("I5ReadCompletionProbe")
                                        .id(),
                                3));
                metrics.bind(runtime.worker);
                runtime.worker.start();
                var id = f.inject(20099);
                f.verify(id);
                assertThrows(AssertionError.class, () -> startsMatchSql(registry, f));
                f.trace.add(
                        "O01-N negative witness: real SQL terminal effect=1, intentionally omitted"
                                + " start metric=0; independent checker rejected it");
                f.evidence("i5_m02");
            }
        }
    }

    static void discoveryCount(MeterRegistry registry, int expected) {
        assertEquals(
                expected,
                count(
                        registry,
                        "pocoma.v3.rotation.tours",
                        "consumption",
                        "Command",
                        "loop",
                        "discovery"),
                "independent page/owner boundary oracle");
    }

    @Test
    void i5_m03() throws Exception {
        for (String scenario :
                List.of(
                        "multipage",
                        "omitted",
                        "premature",
                        "empty",
                        "capacity",
                        "no-owner",
                        "rollback",
                        "observer-failure")) {
            try (var f = new Fixture(PG)) {
                var registry = new SimpleMeterRegistry();
                f.properties.put("pocoma.v3.worker.lifecycle.lease-duration-ms", "90000");
                var metrics = new MicrometerV3ConsumptionObservation(registry);
                ConsumptionObservation observation =
                        (id, phase, outcome, n, elapsed) -> {
                            if (!scenario.equals("omitted")
                                    || phase != ConsumptionObservation.Phase.DISCOVERY_TOUR)
                                metrics.record(id, phase, outcome, n, elapsed);
                            if (scenario.equals("premature")
                                    && phase == ConsumptionObservation.Phase.DISCOVERY_EXAMINED)
                                metrics.record(
                                        id,
                                        ConsumptionObservation.Phase.DISCOVERY_TOUR,
                                        ConsumptionObservation.Outcome.CONFIRMED,
                                        1,
                                        java.time.Duration.ZERO);
                            if (scenario.equals("observer-failure"))
                                throw new AssertionError("observer failure after recording");
                        };
                try (var runtime = new V3WorkerPostgresTest.Runtime(f, observation)) {
                    var binding = runtime.bindings.getFirst();
                    var definition = binding.definition();
                    var requests = new ArrayList<UUID>();
                    if (!scenario.equals("empty"))
                        for (int i = 0; i < 3; i++)
                            requests.add(pendingAtPartition(f, 700000 + i * 100, 0));
                    requests.sort(
                            com.kartaguez.pocoma.engine.consumption.v3.port.SlotKeyNavigation
                                    ::compare);
                    replace(
                            binding.discovery(),
                            "settings",
                            new DiscoveryEngine.Settings(
                                    32,
                                    1,
                                    32,
                                    scenario.equals("capacity") ? 1 : 100,
                                    java.time.Duration.ofMillis(500),
                                    java.time.Duration.ofSeconds(2)));
                    replace(
                            runtime.worker,
                            "bindings",
                            List.of(
                                    new V3ConsumptionWorker.Binding(
                                            definition,
                                            binding.engine(),
                                            binding.discovery(),
                                            List.of(0))));
                    if (scenario.equals("no-owner"))
                        f.write.update(
                                "update partition_ownership set"
                                    + " owner_token=?,owned_until=clock_timestamp()+interval '90"
                                    + " seconds' where consumption_id=? and partition_id=0",
                                UUID.randomUUID(),
                                definition.id().value());
                    if (scenario.equals("rollback")) {
                        var owner =
                                (com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions)
                                        field(binding.discovery(), "effects");
                        replace(
                                binding.discovery(),
                                "effects",
                                new com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions() {
                                    public <T> T read(
                                            java.util.function.Function<
                                                            com.kartaguez.pocoma.port.transaction.v3
                                                                    .RecoveryReadContext,
                                                            T>
                                                    body) {
                                        return owner.read(body);
                                    }

                                    public <T> T write(java.util.function.Supplier<T> body) {
                                        return owner.write(
                                                () -> {
                                                    body.get();
                                                    throw new IllegalStateException(
                                                            "actual rollback after admission");
                                                });
                                    }

                                    public void poison(Throwable failure) {
                                        owner.poison(failure);
                                    }

                                    public void requirePublicationClosed() {
                                        owner.requirePublicationClosed();
                                    }
                                });
                    }
                    replace(runtime.worker, "state", V3ConsumptionWorker.State.RUNNING);
                    var loop =
                            (com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3DiscoveryLoop)
                                    field(runtime.worker, "discovery");
                    try {
                        if (scenario.equals("rollback")) {
                            assertThrows(
                                    com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions
                                            .Failure.class,
                                    loop::runOnePass);
                            discoveryCount(registry, 0);
                            assertEquals(
                                    0,
                                    f.write.queryForObject(
                                            "select count(*) from consumption_slot",
                                            Integer.class));
                        } else if (scenario.equals("no-owner")) {
                            loop.runOnePass();
                            discoveryCount(registry, 0);
                            assertEquals(
                                    0,
                                    f.write.queryForObject(
                                            "select count(*) from consumption_slot",
                                            Integer.class));
                        } else if (scenario.equals("empty")) {
                            assertFalse(loop.runOnePass());
                            discoveryCount(registry, 1);
                            assertNotNull(
                                    registry.find("pocoma.v3.rotation.tours")
                                            .tags("consumption", "Command", "loop", "discovery")
                                            .counter());
                        } else {
                            for (int page = 0; page < 3; page++) {
                                loop.runOnePass();
                                int slots =
                                        f.write.queryForObject(
                                                "select count(*) from consumption_slot",
                                                Integer.class);
                                assertEquals(scenario.equals("capacity") ? 1 : page + 1, slots);
                                int expected = page == 2 && !scenario.equals("capacity") ? 1 : 0;
                                if (scenario.equals("premature")
                                        || scenario.equals("omitted") && page == 2)
                                    assertThrows(
                                            AssertionError.class,
                                            () -> discoveryCount(registry, expected));
                                else discoveryCount(registry, expected);
                            }
                            if (scenario.equals("multipage")
                                    || scenario.equals("observer-failure")) {
                                // A full repeat with all slots already present has no new
                                // admissions.
                                for (int page = 0; page < 3; page++) {
                                    loop.runOnePass();
                                    discoveryCount(registry, page == 2 ? 2 : 1);
                                }
                                assertEquals(
                                        3,
                                        f.write.queryForObject(
                                                "select count(*) from consumption_slot",
                                                Integer.class));
                                assertEquals(
                                        3,
                                        count(
                                                registry,
                                                "pocoma.v3.discovery.references",
                                                "consumption",
                                                "Command",
                                                "kind",
                                                "admitted"));
                            }
                        }
                        f.trace.add(
                                "m03 scenario="
                                        + scenario
                                        + " orderedSource="
                                        + requests
                                        + " registry="
                                        + registry.getMeters().stream()
                                                .filter(
                                                        m ->
                                                                m.getId()
                                                                        .getName()
                                                                        .startsWith("pocoma.v3."))
                                                .map(m -> m.getId() + "=" + m.measure())
                                                .toList()
                                        + " SQL ownership="
                                        + f.write.queryForList(
                                                "select"
                                                    + " consumption_id,partition_id,owner_token,owned_until"
                                                    + " from partition_ownership where owner_token"
                                                    + " is not null")
                                        + " slots="
                                        + f.write.queryForList(
                                                "select consumption_id,consumable_id from"
                                                    + " consumption_slot"));
                        f.evidence("i5_m03-" + scenario);
                    } finally {
                        // The manual fixture launches no schedulers; start the existing drain path
                        // for resource closure.
                        replace(runtime.worker, "state", V3ConsumptionWorker.State.STARTING);
                    }
                }
            }
        }
    }
}
