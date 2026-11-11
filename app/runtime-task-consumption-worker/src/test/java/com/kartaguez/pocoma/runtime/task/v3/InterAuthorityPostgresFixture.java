package com.kartaguez.pocoma.runtime.task.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import javax.sql.DataSource;

/**
 * Two databases, separate runtime logins and CURRENT migrations; real L1 publications, I1 effects.
 */
final class InterAuthorityPostgresFixture implements AutoCloseable {
    final SubobjectPostgresFixture read;
    final PostgreSQLContainer pg;
    final String database, schema, role;
    final DataSource primaryAdmin;
    final SubobjectPostgresFixture.ObservedDataSource primary;
    final JdbcTemplate oracle, sql;
    final SpringAttemptTransactions sourceOwner;
    final JdbcEventDiscoveryReferences references;
    final JdbcDiscoveryAdmissionStore admissions;
    final List<Map<String, Object>> observations = new CopyOnWriteArrayList<>();
    final AtomicInteger sourceActive = new AtomicInteger(), effectsActive = new AtomicInteger();
    final AtomicInteger finalizations = new AtomicInteger();
    final Map<String, PartitionLease> primaryLeases = new HashMap<>();
    final Set<String> physical = ConcurrentHashMap.newKeySet();
    final String scenario;
    Consumer<PreparationReads> preparation = reads -> {};
    Consumer<RecoveryReadContext> effectObservation = context -> {};
    Consumer<SlotCompletion> finalHook = completion -> {};
    Runnable sourceCallback = () -> {};

    InterAuthorityPostgresFixture(PostgreSQLContainer pg, String scenario) throws Exception {
        this.pg = pg;
        this.scenario = scenario;
        read = new SubobjectPostgresFixture(pg, "I2-" + scenario);
        schema = "i2p_" + UUID.randomUUID().toString().replace("-", "");
        database = schema;
        role = schema + "_w";
        var root =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        root.execute("create database " + database);
        root.execute("create role " + role + " login password 'i2-fixture'");
        String url =
                pg.getJdbcUrl().replace("/" + pg.getDatabaseName() + "?", "/" + database + "?")
                        + "&currentSchema="
                        + schema
                        + "&socketTimeout=15";
        primaryAdmin = new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword());
        oracle = new JdbcTemplate(primaryAdmin);
        oracle.execute("create extension if not exists pgcrypto with schema public");
        Flyway.configure()
                .dataSource(primaryAdmin)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations(
                        "classpath:db/v3-pot/migration",
                        "classpath:db/v3-pot-execution/migration",
                        "classpath:db/v3-consumption-write/migration",
                        "classpath:db/v3-identity/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                database,
                                "expectedSchema",
                                schema,
                                "expectedAdmin",
                                pg.getUsername(),
                                "expectedRuntime",
                                role))
                .load()
                .migrate();
        script("bootstrap-l2-routing.sql", Map.of("authority", "WRITE"));
        script(
                "grant-l2-lifecycle.sql",
                Map.of(
                        "runtime_role",
                        role,
                        "reactivation_role",
                        read.reactivationRole,
                        "identity_authority",
                        "real",
                        "identity_read_role",
                        read.runtimeRole));
        primary =
                new SubobjectPostgresFixture.ObservedDataSource(
                        new DriverManagerDataSource(url, role, "i2-fixture"));
        sql = new JdbcTemplate(primary);
        sourceOwner = SubobjectPostgresFixture.owner(primary);
        references =
                new JdbcEventDiscoveryReferences(
                        primary,
                        ConsumableFamily.POT_EVENT,
                        SubobjectPostgresFixture.discoverySettings());
        admissions =
                new JdbcDiscoveryAdmissionStore(
                        read.source, SubobjectPostgresFixture.discoverySettings());
        assertNotEquals(database, read.initialEnvironment.get("db"));
        read.trace.add(
                "I2 PRIMARY="
                        + oracle.queryForMap(
                                "select"
                                    + " version(),current_database(),current_schema(),current_user"));
    }

    void script(String name, Map<String, String> parameters) throws Exception {
        String path = "/tmp/" + schema + "_" + name;
        pg.copyFileToContainer(
                MountableFile.forHostPath(
                        SubobjectPostgresFixture.ROOT.resolve("scripts/v3/" + name)),
                path);
        var args =
                new ArrayList<>(
                        List.of(
                                "env",
                                "PGPASSWORD=" + pg.getPassword(),
                                "PGOPTIONS=-c search_path=" + schema,
                                "psql",
                                "-X",
                                "-h",
                                "127.0.0.1",
                                "-U",
                                pg.getUsername(),
                                "-d",
                                database,
                                "-v",
                                "ON_ERROR_STOP=1",
                                "-v",
                                "expected_database=" + database,
                                "-v",
                                "expected_schema=" + schema,
                                "-v",
                                "expected_admin=" + pg.getUsername()));
        parameters.forEach((k, v) -> args.addAll(List.of("-v", k + "=" + v)));
        args.addAll(List.of("-f", path));
        var result = pg.execInContainer(args.toArray(String[]::new));
        read.trace.add(
                "PRIMARY script="
                        + name
                        + " exit="
                        + result.getExitCode()
                        + " "
                        + result.getStdout()
                        + result.getStderr());
        assertEquals(0, result.getExitCode(), result.getStderr());
    }

    record Published(
            UUID request,
            UUID event,
            UUID completion,
            UUID pot,
            long position,
            List<SubobjectVersion> values) {}

    Published publish() {
        var request = UUID.randomUUID();
        var pot = UUID.randomUUID();
        oracle.update(
                "with cmd as (insert into v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload) values (?,'i2','actor','fixture',?,0,'CreatePot',?::jsonb) returning request_id), pos as (update v3_request_counter set position=position+1 where family='COMMAND' returning position) insert into v3_request_position(family,position,request_id) select 'COMMAND',pos.position,cmd.request_id from cmd,pos",
                request,
                pot,
                PotCommandJson.encode(new PotCommand.CreatePot(new Label("I2 source"))));
        var definition =
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("98f7268d-d340-581c-b83d-010926223e14")),
                        ConsumableFamily.COMMAND,
                        ConsumptionDefinition.Authority.WRITE);
        oracle.update(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                    + " select ?,?,h,((h::bigint%4+4)%4)::int from (select"
                    + " consumption_hash_v1('COMMAND',?) h)x",
                definition.id().value(), request, request);
        PotActorAuthority actor =
                new PotActorAuthority() {
                    public Witness observe(PotCommand c) {
                        return new Witness(1, Optional.of(new UserId(new UUID(0, 71))));
                    }

                    public boolean protect(PotCommand c, Witness w) {
                        return true;
                    }
                };
        var commands =
                new ExecutePotCommand(
                        new JdbcPotCommandStore(primary, sourceOwner),
                        new JdbcPotCommandObservation(primary),
                        actor,
                        new JdbcPotHistoryAdapter(primary),
                        new JdbcPotRevisionAdapter(primary),
                        java.time.Clock.systemUTC());
        var engine =
                new ConsumptionEngine(
                        definition,
                        sourceOwner,
                        new JdbcConsumptionSlotStore(primary),
                        commands,
                        new JdbcPartitionOwnershipStore(primary),
                        read.settings,
                        new WorkerCapacity(4, Map.of(definition.id(), 4)),
                        c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
        int partition = RoutingV1.partition(RoutingV1.hash(definition.family(), request), 4);
        var lease = primaryLease(engine, definition, partition);
        var result = engine.execute(engine.acquire(lease, List.of(request)).claims().getFirst());
        assertEquals(
                ConsumptionEngine.Status.COMMITTED, result.status(), () -> String.valueOf(result));
        var row =
                oracle.queryForMap(
                        "select e.event_id,p.position from v3_pot_event e join v3_event_position p"
                                + " using(event_id) where e.request_id=?",
                        request);
        var event = (UUID) row.get("event_id");
        long position = ((Number) row.get("position")).longValue();
        var completion =
                oracle.queryForObject(
                        "select event_id from v3_command_completion_event where subject_id=?",
                        UUID.class,
                        request);
        var values = List.of(SubobjectPostgresFixture.header(pot, 1, false));
        read.seed(event, values, position);
        read.oracle.update(
                "update discovery_checkpoint set position=? where"
                        + " consumption_id=? and partition_id=?",
                position,
                read.definition.id().value(),
                RoutingV1.partition(RoutingV1.hash(ConsumableFamily.POT_EVENT, event), 4));
        return new Published(request, event, completion, pot, position, values);
    }

    PartitionLease primaryLease(
            ConsumptionEngine engine, ConsumptionDefinition definition, int partition) {
        return primaryLeases.computeIfAbsent(
                definition.id() + ":" + partition,
                ignored -> engine.acquirePartition(partition).orElseThrow());
    }

    Map<String, Object> witness(DataSource source, String stage) {
        var row =
                new JdbcTemplate(source)
                        .queryForMap(
                                "select current_database() as db,current_schema() as"
                                    + " schema,current_user as role,pg_backend_pid() as"
                                    + " pid,pg_current_snapshot()::text as"
                                    + " snapshot,pg_current_xact_id_if_assigned()::text as"
                                    + " xid,current_setting('transaction_isolation') as"
                                    + " isolation,current_setting('transaction_read_only') as ro");
        var value = new LinkedHashMap<>(row);
        value.put("stage", stage);
        observations.add(
                Map.copyOf(
                        value.entrySet().stream()
                                .filter(e -> e.getValue() != null)
                                .collect(
                                        java.util.stream.Collectors.toMap(
                                                Map.Entry::getKey, Map.Entry::getValue))));
        read.trace.add("I2 witness " + stage + " " + row);
        return row;
    }

    <T> T scope(PreparationReads reads, boolean source, Function<RecoveryReadContext, T> action) {
        Function<RecoveryReadContext, T> wrapped =
                context -> {
                    var active = source ? sourceActive : effectsActive;
                    assertEquals(0, source ? effectsActive.get() : sourceActive.get());
                    assertEquals(1, active.incrementAndGet());
                    try {
                        var w =
                                witness(
                                        source ? primary : read.source,
                                        source ? "source" : "effects");
                        assertEquals("repeatable read", w.get("isolation"));
                        assertEquals("on", w.get("ro"));
                        physical.add((String) w.get("db"));
                        return action.apply(context);
                    } finally {
                        active.decrementAndGet();
                    }
                };
        return source ? reads.source(wrapped) : reads.effects(wrapped);
    }

    ConsumptionParticipant participant() {
        return new ConsumptionParticipant() {
            public Optional<Prepared> prepare(UUID id, PreparationReads reads) {
                scope(
                        reads,
                        true,
                        context -> {
                            assertTrue(references.find(id, context).isPresent());
                            sourceCallback.run();
                            return null;
                        });
                preparation.accept(reads);
                var values = read.expected.get(id);
                return Optional.of(
                        new Prepared() {
                            public void finalizeIn(SlotCompletion completion) {
                                assertEquals(0, sourceActive.get());
                                assertEquals(0, effectsActive.get());
                                finalizations.incrementAndGet();
                                var w = witness(read.source, "final");
                                assertEquals("read committed", w.get("isolation"));
                                assertEquals("off", w.get("ro"));
                                read.store.insertOrVerifyAll(values);
                                completion.complete();
                                finalHook.accept(completion);
                                SpringAttemptTransactions.closePublication(read.source);
                            }

                            public void requireCompletionReady() {
                                read.store.requireStored(values);
                            }
                        });
            }

            public Recovery prepareRecovery(UUID id, PreparationReads reads) {
                var values = List.copyOf(read.expected.getOrDefault(id, List.of()));
                return context -> {
                    witness(read.source, "recovery");
                    effectObservation.accept(context);
                    var actual =
                            read.store.readAll(
                                    values.stream().map(SubobjectVersion::key).toList(), context);
                    if (actual.values().stream().anyMatch(v -> !values.contains(v)))
                        return EffectEvidence.DIVERGENT;
                    return actual.size() == values.size()
                            ? EffectEvidence.SATISFIED
                            : EffectEvidence.MISSING;
                };
            }
        };
    }

    ConsumptionEngine engine() {
        return engine(participant(), sourceOwner, read.tx);
    }

    ConsumptionEngine engine(
            ConsumptionParticipant participant,
            AttemptTransactions source,
            AttemptTransactions effects) {
        return new ConsumptionEngine(
                read.definition,
                effects,
                read.slots,
                participant,
                source,
                Optional.of(references),
                r -> true,
                admissions,
                read.tx,
                read.slots,
                read.ownership,
                read.settings,
                read.capacity,
                com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionLifecycleComposition
                        ::classify,
                null,
                ConsumptionObservation.noop());
    }

    ClaimedSlot claim(Published p) {
        return new ClaimedSlot(read.definition.id(), p.event(), UUID.randomUUID());
    }

    void assertPhysical(Published p, boolean committed) {
        assertEquals(committed ? 1 : 0, read.count());
        assertEquals(committed ? 0 : 1, read.slotCount(p.event()));
        assertEquals(
                1,
                oracle.queryForObject(
                        "select count(*) from v3_pot_event where event_id=?",
                        Integer.class,
                        p.event()));
    }

    @Override
    public void close() throws Exception {
        var path =
                Path.of(
                        "target/l4-i2-evidence",
                        System.getProperty("pocoma.verification.id", "development"),
                        scenario + ".txt");
        Files.createDirectories(path.getParent());
        Files.write(path, read.trace);
        read.close();
    }
}
