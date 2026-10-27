package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.*;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import javax.sql.DataSource;

/** Fresh authorities, actual migrations/scripts, ordinary runtime roles, independent SQL oracle. */
final class ConsumptionLifecyclePostgresFixture implements AutoCloseable {
    static final String IMAGE =
            "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73";
    static final Path ROOT = Path.of("../..").toAbsolutePath().normalize();
    final PostgreSQLContainer pg;
    final boolean write;
    final String database, schema, runtimeRole, adminRole;
    final DataSource source, admin;
    final JdbcTemplate sql, oracle;
    final SpringAttemptTransactions tx;
    final JdbcConsumptionSlotStore slots;
    final JdbcPartitionOwnershipStore ownership;
    final ConsumptionDefinition definition;
    final WorkerCapacity capacity;
    final ConsumptionParticipant participant;
    final List<String> trace = new CopyOnWriteArrayList<>();
    final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    LocalContainerEntityManagerFactoryBean emf;
    long sequence;
    LifecycleSettings settings =
            new LifecycleSettings(
                    32,
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(60),
                    Duration.ofSeconds(10),
                    Duration.ofMillis(500),
                    Duration.ofSeconds(2),
                    new LifecycleSettings.RetryPolicy(3, Duration.ZERO));

    ConsumptionLifecyclePostgresFixture(PostgreSQLContainer pg, boolean write) throws Exception {
        this.pg = pg;
        this.write = write;
        database = write ? pg.getDatabaseName() : "i3_read";
        schema = "i3_" + UUID.randomUUID().toString().replace("-", "");
        runtimeRole = schema + "_w";
        adminRole = schema + "_a";
        String url =
                pg.getJdbcUrl().replace("/" + pg.getDatabaseName() + "?", "/" + database + "?")
                        + "&currentSchema="
                        + schema
                        + "&socketTimeout=15";
        admin = new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword());
        oracle = new JdbcTemplate(admin);
        oracle.execute(
                "create role "
                        + runtimeRole
                        + " login password 'i3-fixture';create role "
                        + adminRole
                        + " login password 'i3-fixture'");
        var locations =
                write
                        ? new String[] {
                            "classpath:db/v3-pot/migration",
                            "classpath:db/v3-pot-execution/migration",
                            "classpath:db/v3-consumption-write/migration"
                        }
                        : new String[] {
                            "filesystem:"
                                    + ROOT.resolve(
                                            "app/infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration")
                        };
        Flyway.configure()
                .dataSource(admin)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .locations(locations)
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                database,
                                "expectedSchema",
                                schema,
                                "expectedAdmin",
                                pg.getUsername(),
                                "expectedRuntime",
                                runtimeRole))
                .load()
                .migrate();
        script(
                "bootstrap-l2-routing.sql",
                pg.getUsername(),
                Map.of("authority", write ? "WRITE" : "READ"));
        script(
                "grant-l2-lifecycle.sql",
                pg.getUsername(),
                Map.of("runtime_role", runtimeRole, "reactivation_role", adminRole));
        source = new DriverManagerDataSource(url, runtimeRole, "i3-fixture");
        sql = new JdbcTemplate(source);
        definition =
                new ConsumptionDefinition(
                        new ConsumptionId(
                                UUID.fromString(
                                        write
                                                ? "98f7268d-d340-581c-b83d-010926223e14"
                                                : "3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a")),
                        write ? ConsumableFamily.COMMAND : ConsumableFamily.POT_EVENT,
                        write
                                ? ConsumptionDefinition.Authority.WRITE
                                : ConsumptionDefinition.Authority.READ);
        capacity = new WorkerCapacity(4, Map.of(definition.id(), 2));
        if (write) {
            oracle.execute(
                    "create table fixture_identity(issuer text,subject text,user_id uuid,token"
                        + " text,primary key(issuer,subject));insert into fixture_identity values"
                        + " ('i3','actor','00000000-0000-0000-0000-000000000001','fixture')");
            oracle.execute("grant select,update(token) on fixture_identity to " + runtimeRole);
            emf = new LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(source);
            emf.setPackagesToScan("com.kartaguez.pocoma.infra.persistence.primary.jpa.entity");
            emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
            emf.afterPropertiesSet();
            var manager = new JpaTransactionManager(emf.getObject());
            manager.setDataSource(source);
            manager.setNestedTransactionAllowed(false);
            tx = new SpringAttemptTransactions(source, manager);
        } else {
            oracle.execute(
                    "create table fixture_effect(id uuid primary key,value text not null);grant"
                            + " select,insert on fixture_effect to "
                            + runtimeRole);
            tx = new SpringAttemptTransactions(source, jdbcManager(source));
        }
        participant = participantFor(source, tx);
        slots = new JdbcConsumptionSlotStore(source);
        ownership = new JdbcPartitionOwnershipStore(source);
        trace.add(
                "before stimulus target="
                        + database
                        + "/"
                        + schema
                        + " role="
                        + runtimeRole
                        + " family="
                        + definition.family()
                        + " settings="
                        + settings
                        + " expected: no effects, count=0; literal keys seed=(0,1..); "
                        + oracle.queryForMap(
                                "select"
                                    + " version(),clock_timestamp(),pg_backend_pid(),pg_current_xact_id()::text"));
    }

    ConsumptionParticipant participantFor(DataSource source, SpringAttemptTransactions tx) {
        var sql = new JdbcTemplate(source);
        if (write) {
            PotActorAuthority authority =
                    new PotActorAuthority() {
                        public Witness observe(PotCommand c) {
                            return new Witness(1, Optional.of(new UserId(new UUID(0, 1))));
                        }

                        public boolean protect(PotCommand c, Witness w) {
                            sql.queryForList(
                                    "select token from fixture_identity where issuer='i3' and"
                                            + " subject='actor' for update");
                            return observe(c).equals(w);
                        }
                    };
            return new ExecutePotCommand(
                    new JdbcPotCommandStore(source, tx),
                    new JdbcPotCommandObservation(source),
                    authority,
                    new JdbcPotHistoryAdapter(source),
                    new JdbcPotRevisionAdapter(source),
                    Clock.systemUTC());
        }
        return new ConsumptionParticipant() {
            public Optional<Prepared> prepare(UUID id) {
                return Optional.of(
                        new Prepared() {
                            public void finalizeIn(SlotCompletion completion) {
                                SpringAttemptTransactions.guarded(
                                        source,
                                        () -> {
                                            sql.update(
                                                    "insert into fixture_effect values"
                                                            + " (?,'expected')",
                                                    id);
                                            return null;
                                        });
                                completion.complete();
                                SpringAttemptTransactions.closePublication(source);
                            }

                            public void requireCompletionReady() {
                                assertEquals(
                                        1,
                                        sql.queryForObject(
                                                "select count(*) from fixture_effect where id=? and"
                                                        + " value='expected'",
                                                Integer.class,
                                                id));
                            }
                        });
            }

            public RecoveryObservation observe(UUID id, RecoveryReadContext context) {
                SpringAttemptTransactions.requireRead(source, context);
                return Boolean.TRUE.equals(
                                sql.queryForObject(
                                        "select exists(select 1 from fixture_effect where id=? and"
                                                + " value='expected')",
                                        Boolean.class,
                                        id))
                        ? RecoveryObservation.COMPLETED
                        : RecoveryObservation.NOT_COMPLETED;
            }
        };
    }

    static DataSourceTransactionManager jdbcManager(DataSource source) {
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        return manager;
    }

    static void initialize(PostgreSQLContainer pg) throws Exception {
        var jdbc =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        jdbc.execute("create database i3_read");
        for (String db : List.of(pg.getDatabaseName(), "i3_read")) {
            var ds =
                    new DriverManagerDataSource(
                            pg.getJdbcUrl()
                                    .replace("/" + pg.getDatabaseName() + "?", "/" + db + "?"),
                            pg.getUsername(),
                            pg.getPassword());
            new JdbcTemplate(ds).execute("create extension pgcrypto with schema public");
        }
    }

    String script(String name, String user, Map<String, String> parameters) throws Exception {
        String target = "/tmp/" + schema + "_" + name;
        pg.copyFileToContainer(
                MountableFile.forHostPath(ROOT.resolve("scripts/v3/" + name)), target);
        var args =
                new ArrayList<>(
                        List.of(
                                "env",
                                "PGPASSWORD="
                                        + (user.equals(pg.getUsername())
                                                ? pg.getPassword()
                                                : "i3-fixture"),
                                "PGOPTIONS=-c search_path=" + schema,
                                "psql",
                                "-X",
                                "-h",
                                "127.0.0.1",
                                "-U",
                                user,
                                "-d",
                                database,
                                "-v",
                                "ON_ERROR_STOP=1",
                                "-v",
                                "expected_database=" + database,
                                "-v",
                                "expected_schema=" + schema,
                                "-v",
                                "expected_admin=" + user));
        parameters.forEach((k, v) -> args.addAll(List.of("-v", k + "=" + v)));
        args.addAll(List.of("-f", target));
        var result = pg.execInContainer(args.toArray(String[]::new));
        trace.add(
                "script="
                        + name
                        + " role="
                        + user
                        + " params="
                        + parameters
                        + " rc="
                        + result.getExitCode()
                        + " "
                        + result.getStdout()
                        + result.getStderr());
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout();
    }

    ConsumptionEngine engine() {
        return engine(participant, tx, slots);
    }

    ConsumptionEngine engine(
            ConsumptionParticipant p, AttemptTransactions owner, ConsumptionSlotStore store) {
        return new ConsumptionEngine(
                definition,
                owner,
                store,
                p,
                ownership,
                settings,
                capacity,
                c -> {
                    if (c == null
                            || c instanceof CancellationException
                            || c instanceof InterruptedException)
                        return AttemptFailureClassifier.FailureKind.NON_COUNTABLE;
                    return c instanceof ExecutePotCommand.Collision
                            ? AttemptFailureClassifier.FailureKind.COLLISION
                            : AttemptFailureClassifier.FailureKind.TECHNICAL;
                });
    }

    <T> T technical(Supplier<T> action) {
        return tx.write(
                () -> {
                    ownership.bound(settings.lockTimeout(), settings.statementTimeout());
                    return action.get();
                });
    }

    UUID seed(int partition) {
        return seedCommand(partition, null, 0, new PotCommand.CreatePot(new Label("I3 expected")));
    }

    UUID seedCommand(int partition, UUID pot, long observed, PotCommand.Intent intent) {
        UUID id;
        do {
            id = new UUID(0, ++sequence);
        } while (oracle.queryForObject(
                        "select ((consumption_hash_v1(?,?)::bigint%4+4)%4)::int",
                        Integer.class, definition.family().name(), id)
                != partition);
        if (write)
            oracle.update(
                    "insert into"
                        + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                        + " values (?,'i3','actor','fixture',?,?,?,?::jsonb)",
                    id,
                    pot == null ? new UUID(1, sequence) : pot,
                    observed,
                    intent.getClass().getSimpleName(),
                    PotCommandJson.encode(intent));
        oracle.update(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                    + " select ?,?,consumption_hash_v1(?,?),?",
                definition.id().value(),
                id,
                definition.family().name(),
                id,
                partition);
        trace.add(
                "seed before acquisition key="
                        + id
                        + " p="
                        + partition
                        + " expected count=0/AVAILABLE, expected effect="
                        + (write ? "CreatePot V1/terminal/result/POT+COMPLETION" : "expected"));
        return id;
    }

    Map<String, Object> row(UUID id) {
        return oracle.queryForMap(
                "select * from consumption_slot where consumption_id=? and consumable_id=?",
                definition.id().value(),
                id);
    }

    void expireLease(int p) {
        oracle.update(
                "update partition_ownership set owned_until=clock_timestamp()-interval '1 second'"
                        + " where consumption_id=? and partition_id=? and owner_token is not null",
                definition.id().value(),
                p);
    }

    void expire(UUID id) {
        oracle.update(
                "update consumption_slot set claimed_until=clock_timestamp()-interval '1 second'"
                        + " where consumption_id=? and consumable_id=?",
                definition.id().value(),
                id);
    }

    Instant now() {
        return oracle.queryForObject("select clock_timestamp()", Timestamp.class).toInstant();
    }

    void noEffects(UUID id) {
        assertEquals(0, effects(id));
        if (write) {
            var pot =
                    oracle.queryForObject(
                            "select pot_id from v3_command where request_id=?", UUID.class, id);
            for (var table :
                    List.of(
                            "v3_pot_version",
                            "v3_pot_header",
                            "v3_pot_details",
                            "v3_shareholder_header",
                            "v3_shareholder_details",
                            "v3_shareholder_weight",
                            "v3_expense_header",
                            "v3_expense_details",
                            "v3_expense_financials"))
                assertEquals(
                        0,
                        oracle.queryForObject(
                                "select count(*) from " + table + " where pot_id=?",
                                Integer.class,
                                pot),
                        table);
            assertEquals(
                    "PENDING",
                    oracle.queryForObject(
                            "select status from v3_command where request_id=?", String.class, id));
            for (var table : List.of("v3_pot_event", "v3_command_completion_event"))
                assertEquals(
                        0,
                        oracle.queryForObject(
                                "select count(*) from "
                                        + table
                                        + " where "
                                        + (table.equals("v3_pot_event")
                                                ? "request_id"
                                                : "subject_id")
                                        + "=?",
                                Integer.class,
                                id),
                        table);
        }
    }

    int effects(UUID id) {
        return oracle.queryForObject(
                write
                        ? "select count(*) from v3_command_result where request_id=?"
                        : "select count(*) from fixture_effect where id=?",
                Integer.class,
                id);
    }

    Connection lock(UUID id) throws Exception {
        var c = admin.getConnection();
        c.setAutoCommit(false);
        try (var s =
                c.prepareStatement(
                        "select consumable_id from consumption_slot where consumption_id=? and"
                                + " consumable_id=? for update")) {
            s.setObject(1, definition.id().value());
            s.setObject(2, id);
            s.executeQuery().close();
        }
        trace.add("held slot lock " + id);
        return c;
    }

    void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) throw new AssertionError("barrier timeout: " + trace);
            Thread.sleep(5);
        }
    }

    void blocked(int pid) throws Exception {
        await(
                () ->
                        Boolean.TRUE.equals(
                                oracle.queryForObject(
                                        "select cardinality(pg_blocking_pids(?))>0",
                                        Boolean.class,
                                        pid)));
        trace.add(
                "blocked="
                        + oracle.queryForMap(
                                "select clock_timestamp(),?::int pid,pg_blocking_pids(?) blockers",
                                pid,
                                pid));
    }

    void snapshot(String event) {
        trace.add(
                event
                        + " "
                        + oracle.queryForList(
                                "select clock_timestamp(),* from consumption_slot order by"
                                        + " consumption_id,consumable_id"));
        trace.add(
                "ownership="
                        + oracle.queryForList(
                                "select * from partition_ownership order by"
                                        + " consumption_id,partition_id"));
        if (write)
            trace.add(
                    "results="
                            + oracle.queryForList("select * from v3_command_result")
                            + " positions="
                            + oracle.queryForList("select * from v3_event_position"));
        else trace.add("effects=" + oracle.queryForList("select * from fixture_effect"));
    }

    public void close() {
        threads.close();
        if (emf != null) emf.destroy();
    }
}
