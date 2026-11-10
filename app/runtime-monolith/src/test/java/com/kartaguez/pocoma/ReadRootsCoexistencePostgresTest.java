package com.kartaguez.pocoma;

import static com.kartaguez.pocoma.ReadMonolithI8Fixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.engine.consumption.v3.WorkerCapacity;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.*;
import java.util.*;

@Testcontainers
class ReadRootsCoexistencePostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withCommand("postgres", "-c", "log_line_prefix=%m [%p] xid=%x state=%e ");

    @BeforeAll
    static void initializeServer() throws Exception {
        initialize(PG);
    }

    @Test
    void projectionAndMonolithSharePartitionsSafely() throws Exception {
        try (var f = new ReadMonolithI8Fixture(PG)) {
            f.root = PocomaRuntimeMonolithApplication.class;
            f.select(List.of(), false);
            var idle = f.start();
            assertTrue(
                    idle.getBeansOfType(V3ConsumptionWorker.class).isEmpty(),
                    "monolith empty selection has no Worker");
            assertTrue(idle.getBeansOfType(WorkerCapacity.class).isEmpty());
            assertNotNull(f.services(idle));
            idle.close();
            f.contexts.remove(idle);
            f.select(V3ConsumptionWorkerSettings.READ_NAMES, true);
            String before = f.coordination();
            f.read.execute("grant update on read_current_binding to " + f.readRole);
            assertThrows(
                    RuntimeException.class, f::start, "monolith rejects excess READ privileges");
            assertEquals(before, f.coordination());
            f.read.execute("revoke update on read_current_binding from " + f.readRole);
            f.read.execute(
                    "grant update(eui_version,user_id,binding_token) on read_current_binding to "
                            + f.readRole);
            var monolith = f.start(Map.of("pocoma.v3.worker.worker-name", "i8-monolith"));
            await(
                    () ->
                            f.worker(monolith)
                                            .localLeases()
                                            .getOrDefault(ConsumptionDefinition.Authority.READ, 0L)
                                    == 28);
            f.select(V3ConsumptionWorkerSettings.READ_NAMES, false);
            f.root = PocomaTaskConsumptionWorkerApplication.class;
            var projection = f.start(Map.of("pocoma.v3.worker.worker-name", "i8-projection"));
            assertEquals(4, monolith.getBeansOfType(HikariDataSource.class).size());
            assertEquals(4, projection.getBeansOfType(HikariDataSource.class).size());
            assertEquals(1, monolith.getBeansOfType(WorkerCapacity.class).size());
            assertEquals(1, projection.getBeansOfType(WorkerCapacity.class).size());
            await(
                    () ->
                            f.worker(monolith)
                                                    .localLeases()
                                                    .getOrDefault(
                                                            ConsumptionDefinition.Authority.READ,
                                                            0L)
                                            + f.worker(projection)
                                                    .localLeases()
                                                    .getOrDefault(
                                                            ConsumptionDefinition.Authority.READ,
                                                            0L)
                                    == 28);
            var pot = ReadWorkerResourcesPostgresTest.baseline(f, monolith, true);
            assertEquals(
                    28,
                    f.read.queryForObject(
                            "select count(*) from partition_ownership where owner_token is not null",
                            Integer.class),
                    "one effective durable owner per READ pair");
            assertEquals(
                    64,
                    f.write.queryForObject(
                            "select count(*) from pg_stat_activity where application_name like"
                                + " 'i8-monolith-%' or application_name like 'i8-projection-%'",
                            Integer.class),
                    "two roots allocate 64 actual server connections");
            var update =
                    f.admitCommand(
                            "owner",
                            f.ownToken("owner").value(),
                            pot,
                            1,
                            new PotCommand.UpdatePotDetails(new Label("coexistence")));
            f.verifyCommand(update, "CHANGED", null);
            await(
                    () ->
                            f.read.queryForObject(
                                            "select count(*) from read_pot_version where pot_id=?"
                                                + " and pot_version=2",
                                            Integer.class,
                                            pot.value())
                                    == 1);
            assertEquals(
                    1,
                    f.read.queryForObject(
                            "select count(*) from read_current_pot where pot_id=? and"
                                + " pot_version=2",
                            Integer.class,
                            pot.value()),
                    "one immutable effect under overlap");
            await(
                    () ->
                            f.worker(monolith)
                                            .localLeases()
                                            .getOrDefault(ConsumptionDefinition.Authority.READ, 0L)
                                    == 28);
            assertEquals(
                    28,
                    f.worker(monolith)
                            .localLeases()
                            .getOrDefault(ConsumptionDefinition.Authority.READ, 0L),
                    "first started root owns the shared READ pairs");
            f.drain(monolith);
            long remaining =
                    f.read.queryForObject(
                            "select greatest(0,ceil(extract(epoch"
                                + " from(max(owned_until)-clock_timestamp()))*1000))::bigint from"
                                + " partition_ownership where owner_token is not null",
                            Long.class);
            var start = Instant.now();
            await(
                    () ->
                            f.worker(projection)
                                            .localLeases()
                                            .getOrDefault(ConsumptionDefinition.Authority.READ, 0L)
                                    == 28,
                    Duration.ofMillis(remaining + 20000));
            long takeover = Duration.between(start, Instant.now()).toMillis();
            assertTrue(
                    takeover >= Math.max(0, remaining - 5000),
                    "overlapping root waits for durable lease expiration");
            // Former WRITE owner has also expired; controlled source producer publishes fresh work.
            var next =
                    f.admitCommand(
                            "owner",
                            f.ownToken("owner").value(),
                            pot,
                            2,
                            new PotCommand.UpdatePotDetails(new Label("takeover")));
            f.produce(next);
            await(
                    () ->
                            f.read.queryForObject(
                                            "select count(*) from read_pot_version where pot_id=?"
                                                + " and pot_version=3",
                                            Integer.class,
                                            pot.value())
                                    == 1);
            assertEquals(
                    3,
                    f.read.queryForObject(
                            "select count(*) from read_pot_version where pot_id=?",
                            Integer.class,
                            pot.value()),
                    "history retained through ownership change");
            f.trace.add(
                    "T11 overlap: effective READ pairs=28, server connections=64, lease=240000ms,"
                        + " PG remaining="
                            + remaining
                            + " takeover_ms="
                            + takeover);
            f.physical("T11-overlap-takeover");
            f.drain(projection);
            f.evidence("T11-overlap");
        }
        // Separate fresh authority avoids altering leases/checkpoints to accelerate the disjoint
        // variant.
        try (var f = new ReadMonolithI8Fixture(PG)) {
            f.root = PocomaRuntimeMonolithApplication.class;
            f.select(V3ConsumptionWorkerSettings.READ_NAMES, true);
            String monolithPartitions =
                    String.join(
                                    ";",
                                    V3ConsumptionWorkerSettings.READ_NAMES.stream()
                                            .map(n -> n + "=0|1")
                                            .toList())
                            + ";Command=0|1|2|3;IdentityRequest=0|1|2|3";
            var monolith =
                    f.start(
                            Map.of(
                                    "pocoma.v3.worker.worker-name",
                                    "i8-monolith",
                                    "pocoma.v3.worker.partitions",
                                    monolithPartitions));
            f.root = PocomaTaskConsumptionWorkerApplication.class;
            f.select(V3ConsumptionWorkerSettings.READ_NAMES, false);
            String projectionPartitions =
                    String.join(
                            ";",
                            V3ConsumptionWorkerSettings.READ_NAMES.stream()
                                    .map(n -> n + "=2|3")
                                    .toList());
            var projection =
                    f.start(
                            Map.of(
                                    "pocoma.v3.worker.worker-name",
                                    "i8-projection",
                                    "pocoma.v3.worker.partitions",
                                    projectionPartitions));
            await(
                    () ->
                            f.worker(monolith)
                                                    .localLeases()
                                                    .getOrDefault(
                                                            ConsumptionDefinition.Authority.READ,
                                                            0L)
                                            == 14
                                    && f.worker(projection)
                                                    .localLeases()
                                                    .getOrDefault(
                                                            ConsumptionDefinition.Authority.READ,
                                                            0L)
                                            == 14);
            var pot = ReadWorkerResourcesPostgresTest.baseline(f, monolith, true);
            assertEquals(
                    1,
                    f.read.queryForObject(
                            "select count(*) from read_pot_version where pot_id=?",
                            Integer.class,
                            pot.value()));
            f.trace.add(
                    "T11 disjoint: monolith READ pairs 0|1=14, projection READ pairs 2|3=14, one"
                        + " unchanged canonical destination");
            f.physical("T11-disjoint");
            f.drain(projection);
            f.drain(monolith);
            f.evidence("T11-disjoint");
        }
    }
}
