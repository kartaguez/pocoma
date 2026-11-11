package com.kartaguez.pocoma.runtime.command.v3;
import com.kartaguez.pocoma.infra.postgres.core.v3.V3BootstrapGuard;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.contract.consumption.spi.AttemptFailureClassifier;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.contract.consumption.spi.PartitionLease;
import com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;
import com.kartaguez.pocoma.runtime.consumption.v3.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import javax.sql.DataSource;

/** Real Identity producers, Command factory/classifier, Discovery and acquired L2 claims. */
@Testcontainers
class V3IdentityPotCommandPostgresTest {
    @Container static final PostgreSQLContainer PG = V3IdentityRequestCompositionPostgresTest.PG;

    @BeforeAll
    static void initialize() throws Exception {
        V3IdentityRequestCompositionPostgresTest.initialize(PG);
    }

    static final class Harness implements AutoCloseable {
        final V3IdentityRequestCompositionPostgresTest.Fixture f;
        final org.springframework.context.ConfigurableApplicationContext context;
        final LocalContainerEntityManagerFactoryBean factory;
        final DataSource source;
        final JdbcTemplate jdbc;
        final SpringAttemptTransactions owner;
        final JdbcIdentityPotActorAuthority real;
        final ConsumptionEngine engine;
        final DiscoveryEngine discovery;
        final Map<Integer, PartitionLease> leases = new HashMap<>();
        final List<String> sql = new CopyOnWriteArrayList<>();
        final List<String> trace = new CopyOnWriteArrayList<>();
        final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        volatile Runnable observed = () -> {};
        volatile Runnable protectedEui = () -> {};
        volatile BiConsumer<String, Boolean> sqlHook = (s, after) -> {};
        final AtomicInteger businessPid = new AtomicInteger();

        Harness() throws Exception {
            this(null);
        }

        Harness(String businessUrl) throws Exception {
            f = new V3IdentityRequestCompositionPostgresTest.Fixture(PG);
            context = f.start(); // Real IdentityRequest worker; Command exercised by the
            // actual factory below.
            var delegate =
                    new DriverManagerDataSource(
                            businessUrl == null
                                    ? f.writeUrl
                                    : businessUrl
                                            + "/"
                                            + PG.getDatabaseName()
                                            + "?currentSchema="
                                            + f.schema
                                            + "&sslmode=disable&preferQueryMode=simple",
                            f.writeRole,
                            "i5-fixture");
            source =
                    new AbstractDataSource() {
                        public Connection getConnection() throws SQLException {
                            var connection = delegate.getConnection();
                            int pid =
                                    connection
                                            .unwrap(org.postgresql.PGConnection.class)
                                            .getBackendPID();
                            return (Connection)
                                    Proxy.newProxyInstance(
                                            getClass().getClassLoader(),
                                            new Class[] {Connection.class},
                                            (p, method, args) -> {
                                                var result = invoke(connection, method, args);
                                                if (method.getName().equals("prepareStatement"))
                                                    return statement(
                                                            (Statement) result,
                                                            (String) args[0],
                                                            pid,
                                                            PreparedStatement.class);
                                                if (method.getName().equals("createStatement"))
                                                    return statement(
                                                            (Statement) result,
                                                            null,
                                                            pid,
                                                            Statement.class);
                                                return result;
                                            });
                        }

                        public Connection getConnection(String user, String password)
                                throws SQLException {
                            return getConnection();
                        }
                    };
            jdbc = new JdbcTemplate(source);
            factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan("com.kartaguez.pocoma.infra.persistence.primary.jpa.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
            factory.afterPropertiesSet();
            var manager = new JpaTransactionManager(factory.getObject());
            manager.setDataSource(source);
            manager.setNestedTransactionAllowed(false);
            owner = new SpringAttemptTransactions(source, manager, 15);
            real = new JdbcIdentityPotActorAuthority(source, owner);
            var decorated =
                    new PotActorAuthority() {
                        public Witness observe(PotCommand command) {
                            var witness = real.observe(command);
                            trace.add(
                                    "observe request="
                                            + command.requestId()
                                            + " witness="
                                            + witness);
                            observed.run();
                            return witness;
                        }

                        public boolean protect(PotCommand command, Witness witness) {
                            businessPid.set(
                                    jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                            var stable = real.protect(command, witness);
                            trace.add(
                                    "protect request="
                                            + command.requestId()
                                            + " stable="
                                            + stable
                                            + " physical="
                                            + jdbc.queryForMap(
                                                    "select pg_backend_pid()"
                                                        + " pid,pg_current_xact_id()::text xid"));
                            protectedEui.run();
                            return stable;
                        }
                    };
            var settings = LifecycleSettings.pilot();
            var guard = context.getBean(V3BootstrapGuard.class);
            var definition = V3ConsumptionWorkerSettings.definition("Command");
            engine =
                    V3PotCommandComposition.assemble(
                            source,
                            owner,
                            decorated,
                            Clock.systemUTC(),
                            guard,
                            settings,
                            new WorkerCapacity(4, Map.of(definition.id(), 4)),
                            8,
                            V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
            var control = context.getBean("writeControl", com.zaxxer.hikari.HikariDataSource.class);
            var technicalManager = new DataSourceTransactionManager(control);
            technicalManager.setNestedTransactionAllowed(false);
            discovery =
                    V3ConsumptionDiscoveryComposition.requests(
                            guard,
                            definition,
                            control,
                            new SpringAttemptTransactions(control, technicalManager, 5),
                            new DiscoverySettings(
                                    128,
                                    32,
                                    32,
                                    64,
                                    settings.lockTimeout(),
                                    settings.statementTimeout()),
                            V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
        }

        Object statement(Statement statement, String prepared, int pid, Class<?> type) {
            return Proxy.newProxyInstance(
                    getClass().getClassLoader(),
                    new Class[] {type},
                    (p, method, args) -> {
                        if (!method.getName().startsWith("execute"))
                            return invoke(statement, method, args);
                        String text = prepared == null ? (String) args[0] : prepared;
                        text = text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
                        sql.add(text);
                        trace.add(
                                "pid=" + pid + " SQL "
                                        + text); // No binds or returned token values.
                        sqlHook.accept(text, false);
                        var result = invoke(statement, method, args);
                        sqlHook.accept(text, true);
                        return result;
                    });
        }

        static Object invoke(Object object, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(object, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }

        String register(String subject) throws Exception {
            f.verify(f.admit("REGISTRATION", subject, subject, null));
            return f.ownToken(subject).value();
        }

        PotCommand command(
                String subject, String token, PotId pot, long version, PotCommand.Intent intent) {
            return f.admitCommand(subject, token, pot, version, intent);
        }

        ConsumptionEngine.AcquiredClaim acquire(PotCommand command) {
            int partition =
                    RoutingV1.partition(
                            RoutingV1.hash(ConsumableFamily.COMMAND, command.requestId()), 4);
            var lease =
                    leases.computeIfAbsent(
                            partition, p -> engine.acquirePartition(p).orElseThrow());
            discovery.discoverRequests(lease);
            var acquired = engine.acquire(lease, List.of(command.requestId()));
            assertEquals(1, acquired.claims().size());
            var claim = acquired.claims().getFirst();
            trace.add("acquired request=" + command.requestId() + " partition=" + partition);
            return claim;
        }

        ConsumptionProtocol.Attempt execute(PotCommand command) {
            return engine.execute(acquire(command));
        }

        void completed(PotCommand command, String outcome, String rejection) throws Exception {
            assertEquals(ConsumptionProtocol.Status.COMMITTED, execute(command).status());
            f.verifyCommand(command, outcome, rejection);
        }

        Map<String, Object> business() {
            var result = new LinkedHashMap<String, Object>();
            for (String table :
                    List.of(
                            "v3_eui_authority",
                            "v3_eui_version",
                            "v3_pocoma_user",
                            "v3_pot_identity",
                            "v3_pot_version",
                            "v3_pot_header",
                            "v3_pot_details",
                            "v3_command_result",
                            "v3_pot_event",
                            "v3_pot_event_component",
                            "v3_command_completion_event",
                            "v3_event_position",
                            "v3_event_counter")) {
                result.put(
                        table,
                        f.write.queryForObject(
                                "select coalesce(md5(string_agg(row_to_json(t)::text,',' "
                                        + "order by row_to_json(t)::text)),'empty') from "
                                        + table
                                        + " t",
                                String.class));
            }
            return result;
        }

        void pending(PotCommand command) {
            assertEquals(
                    "PENDING",
                    f.write.queryForObject(
                            "select status from v3_command where request_id=?",
                            String.class,
                            command.requestId()));
            assertEquals(
                    0,
                    f.write.queryForObject(
                            "select count(*) from v3_command_result where request_id=?",
                            Integer.class,
                            command.requestId()));
            assertEquals(
                    0,
                    f.write.queryForObject(
                            "select count(*) from v3_command_completion_event where subject_id=?",
                            Integer.class,
                            command.requestId()));
        }

        void retryAfter(PotCommand command) throws Exception {
            var slot =
                    f.write.queryForMap(
                            "select attempt_count,claim_token,retry_after from consumption_slot"
                                    + " where consumable_id=?",
                            command.requestId());
            assertEquals(1, ((Number) slot.get("attempt_count")).intValue());
            assertNull(slot.get("claim_token"));
            assertNotNull(slot.get("retry_after"));
            var partition =
                    RoutingV1.partition(
                            RoutingV1.hash(ConsumableFamily.COMMAND, command.requestId()), 4);
            assertTrue(
                    engine.acquire(leases.get(partition), List.of(command.requestId()))
                            .claims()
                            .isEmpty());
            var nextRenewal = new AtomicLong(System.nanoTime());
            V3IdentityRequestCompositionPostgresTest.await(
                    () -> {
                        if (System.nanoTime() >= nextRenewal.get()) {
                            leases.put(
                                    partition,
                                    engine.renewPartitions(List.of(leases.get(partition)))
                                            .getFirst());
                            nextRenewal.set(System.nanoTime() + Duration.ofSeconds(5).toNanos());
                        }
                        return Boolean.TRUE.equals(
                                f.write.queryForObject(
                                        "select retry_after<=clock_timestamp() from"
                                                + " consumption_slot where consumable_id=?",
                                        Boolean.class,
                                        command.requestId()));
                    },
                    Duration.ofSeconds(35));
            trace.add(
                    "retryAfter reached DB deadline; new claim required request="
                            + command.requestId());
        }

        void evidence(String name) throws Exception {
            trace.add("durable digests=" + business());
            trace.add(
                    "commands="
                            + f.write.queryForList(
                                    "select"
                                        + " c.request_id,c.status,r.outcome,r.rejection,r.resulting_pot_version"
                                        + " from v3_command c left join v3_command_result r"
                                        + " using(request_id) order by c.request_id"));
            trace.add(
                    "slots="
                            + f.write.queryForList(
                                    "select"
                                        + " consumption_id,consumable_id,attempt_count,state,claim_token"
                                        + " is not null claimed,retry_after from consumption_slot"
                                        + " order by consumable_id"));
            var path = Path.of("target/l3-i4-evidence/" + name + ".txt");
            Files.createDirectories(path.getParent());
            Files.write(path, trace);
        }

        public void close() throws Exception {
            threads.shutdownNow();
            assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
            factory.destroy();
            f.close();
        }
    }

    static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "deterministic barrier");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void l3_negative_stable_and_a1_sql() throws Exception {
        try (var h = new Harness()) {
            String token = h.register("a");
            var pot = new PotId(UUID.randomUUID());
            h.completed(
                    h.command("a", token, pot, 0, new PotCommand.CreatePot(new Label("seed"))),
                    "CHANGED",
                    null);
            var freeToken = h.register("free");
            h.f.verify(
                    h.f.admit(
                            "UNLINK",
                            "free",
                            "free",
                            new com.kartaguez.pocoma.domain.identity.v3.BindingToken(
                                    freeToken)));
            for (String kind : List.of("absent", "zero", "free", "invalid")) {
                String subject = kind.equals("invalid") ? "a" : kind;
                if (kind.equals("zero"))
                    h.owner.write(
                            () ->
                                    h.real.protect(
                                            new PotCommand(
                                                    UUID.randomUUID(),
                                                    new PotCommand.Eui(
                                                            "controlled-runtime", "zero"),
                                                    "invalid",
                                                    new PotId(UUID.randomUUID()),
                                                    0,
                                                    new PotCommand.CreatePot(new Label("zero"))),
                                            new PotActorAuthority.Witness(0, Optional.empty())));
                var command =
                        h.command(
                                subject,
                                "invalid",
                                pot,
                                1,
                                new PotCommand.UpdatePotDetails(new Label("forbidden")));
                h.sql.clear();
                h.protectedEui =
                        () -> {
                            assertEquals(
                                    0,
                                    h.f.write.queryForObject(
                                            "select count(*) from pg_locks where pid=? and"
                                                    + " locktype='advisory'",
                                            Integer.class,
                                            h.businessPid.get()));
                            try (var c = h.f.write.getDataSource().getConnection()) {
                                c.setAutoCommit(false);
                                try (var statement = c.createStatement()) {
                                    statement.execute(
                                            "select pot_id from v3_pot_identity for update nowait");
                                    statement.execute(
                                            "select pot_id from v3_pot_version for update nowait");
                                } finally {
                                    c.rollback();
                                }
                            } catch (SQLException e) {
                                throw new AssertionError(e);
                            }
                        };
                Runnable noPotLock = h.protectedEui;
                h.sqlHook =
                        (statement, after) -> {
                            if (after
                                    && (statement.startsWith("select coalesce((select writer_xid")
                                            || statement.startsWith(
                                                    "insert into v3_event_position")))
                                noPotLock.run();
                        };
                h.completed(command, "REJECTED", "INVALID_BINDING");
                h.protectedEui = () -> {};
                h.sqlHook = (statement, after) -> {};
                var potSql =
                        h.sql.stream()
                                .filter(
                                        s ->
                                                (s.contains("from v3_pot_")
                                                        || s.contains("into v3_pot_")
                                                        || s.contains("update v3_pot_")
                                                        || s.contains("pg_advisory")))
                                .toList();
                assertEquals(1, potSql.size(), potSql.toString());
                assertEquals(
                        "select coalesce((select writer_xid=pg_current_xact_id() from"
                                + " v3_pot_version where pot_id=? order by version desc limit"
                                + " 1),false)",
                        potSql.getFirst());
                assertTrue(
                        h.sql.indexOf(potSql.getFirst())
                                > h.sql.indexOf(
                                        "set constraints"
                                            + " v3_identity_anchor_state,v3_identity_version_state"
                                            + " immediate"));
                assertEquals(
                        1,
                        h.f.write.queryForObject(
                                "select count(*) from v3_pot_version", Integer.class));
                assertEquals(
                        1,
                        h.f.write.queryForObject(
                                "select count(*) from v3_pot_event", Integer.class));
                assertTrue(
                        h.owner.<Boolean>read(
                                c ->
                                        new JdbcPotCommandStore(h.source, h.owner)
                                                .load(command.requestId())
                                                .terminal()
                                                .isPresent()));
                h.trace.add(
                        "A1 variant="
                                + kind
                                + " business Pot SQL=0; technical SELECT=1; NOWAIT passed; no"
                                + " advisory lock; no retry");
            }
            h.evidence("l3_negative_stable_and_a1_sql");
        }
    }

    @Test
    void l3_a1_illicit_version_rejected() throws Exception {
        try (var h = new Harness()) {
            var pot = new PotId(UUID.randomUUID());
            var command =
                    h.command(
                            "unknown",
                            "invalid",
                            pot,
                            0,
                            new PotCommand.CreatePot(new Label("invalid")));
            var before = h.business();
            h.protectedEui =
                    () -> {
                        h.jdbc.update(
                                "insert into v3_pot_identity(pot_id) values (?)", pot.value());
                        h.jdbc.update(
                                "insert into v3_pot_version(pot_id,version,recorded_at) values"
                                        + " (?,1,clock_timestamp())",
                                pot.value());
                    };
            var attempt = h.execute(command);
            assertEquals(ConsumptionProtocol.Status.ROLLED_BACK, attempt.status());
            assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, attempt.resolution());
            assertTrue(
                    messages(attempt.cause())
                            .contains("No-effect completion has a candidate in this attempt"));
            assertEquals(before, h.business());
            h.pending(command);
            h.evidence("l3_a1_illicit_version_rejected");
        }
    }

    static String messages(Throwable cause) {
        var text = new StringBuilder();
        for (var t = cause; t != null; t = t.getCause()) text.append(t.getMessage()).append('\n');
        return text.toString();
    }

    @Test
    void l3_observation_change_and_l2_retry() throws Exception {
        for (boolean positive : List.of(false, true)) {
            try (var h = new Harness()) {
                String token = h.register("a");
                var command =
                        h.command(
                                "a",
                                positive ? token : "invalid",
                                new PotId(UUID.randomUUID()),
                                0,
                                new PotCommand.CreatePot(new Label("never")));
                var prepared = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                h.observed =
                        () -> {
                            prepared.countDown();
                            await(release);
                        };
                var handle = h.acquire(command);
                var first = h.threads.submit(() -> h.engine.execute(handle));
                await(prepared);
                try {
                    h.f.verify(
                            h.f.admit(
                                    "UNLINK",
                                    "a",
                                    "a",
                                    new com.kartaguez.pocoma.domain.identity.v3.BindingToken(
                                            token)));
                } finally {
                    release.countDown();
                }
                var attempt = first.get(20, TimeUnit.SECONDS);
                assertEquals(ConsumptionProtocol.Status.ROLLED_BACK, attempt.status());
                assertEquals(AttemptFailureClassifier.FailureKind.COLLISION, attempt.failureKind());
                h.pending(command);
                assertEquals(
                        0,
                        h.f.write.queryForObject(
                                "select count(*) from v3_pot_version", Integer.class));
                h.observed = () -> {};
                h.retryAfter(command);
                h.completed(command, "REJECTED", "INVALID_BINDING");
                h.trace.add(
                        "variant initial positive="
                                + positive
                                + "; original intention/token retained; new acquisition terminal"
                                + " stable negative");
                h.evidence("l3_observation_change_" + positive);
            }
        }
    }

    @Test
    void l3_command_lock_until_resolution() throws Exception {
        for (String outcome : List.of("CHANGED", "UNCHANGED", "REJECTED")) {
            for (boolean rollback : List.of(false, true)) {
                try (var h = new Harness()) {
                    String token = h.register("a");
                    var pot = new PotId(UUID.randomUUID());
                    h.completed(
                            h.command(
                                    "a",
                                    token,
                                    pot,
                                    0,
                                    new PotCommand.CreatePot(new Label("seed"))),
                            "CHANGED",
                            null);
                    String subject = outcome.equals("REJECTED") ? "other" : "a";
                    String acting = outcome.equals("REJECTED") ? h.register(subject) : token;
                    var command =
                            h.command(
                                    subject,
                                    acting,
                                    pot,
                                    1,
                                    new PotCommand.UpdatePotDetails(
                                            new Label(
                                                    outcome.equals("UNCHANGED")
                                                            ? "seed"
                                                            : "change")));
                    var held = new CountDownLatch(1);
                    var release = new CountDownLatch(1);
                    h.sqlHook =
                            (s, after) -> {
                                if (after && s.startsWith("insert into v3_event_position")) {
                                    held.countDown();
                                    await(release);
                                    if (rollback)
                                        throw new IllegalStateException(
                                                "physical Command rollback");
                                }
                            };
                    var handle = h.acquire(command);
                    var attempt = h.threads.submit(() -> h.engine.execute(handle));
                    await(held); // completion and closed publication have already happened,
                    // physical commit has not.
                    try {
                        h.f.admit(
                                "UNLINK",
                                subject,
                                subject,
                                new com.kartaguez.pocoma.domain.identity.v3.BindingToken(
                                        acting));
                        V3IdentityRequestCompositionPostgresTest.await(
                                () ->
                                        Boolean.TRUE.equals(
                                                h.f.write.queryForObject(
                                                        "select exists(select 1 from"
                                                                + " pg_stat_activity where"
                                                                + " ?=any(pg_blocking_pids(pid)))",
                                                        Boolean.class,
                                                        h.businessPid.get())));
                        h.trace.add(
                                "server blocker="
                                        + h.businessPid.get()
                                        + " after completion/closed suffix outcome="
                                        + outcome);
                    } finally {
                        release.countDown();
                    }
                    var resolved = attempt.get(20, TimeUnit.SECONDS);
                    assertEquals(
                            rollback
                                    ? ConsumptionProtocol.Status.ROLLED_BACK
                                    : ConsumptionProtocol.Status.COMMITTED,
                            resolved.status());
                    if (rollback) {
                        assertEquals(
                                AttemptTransactions.Resolution.ROLLED_BACK, resolved.resolution());
                        h.pending(command);
                        assertEquals(
                                1,
                                h.f.write.queryForObject(
                                        "select count(*) from v3_pot_version", Integer.class));
                    } else
                        h.f.verifyCommand(
                                command, outcome, outcome.equals("REJECTED") ? "FORBIDDEN" : null);
                    h.sqlHook = (s, after) -> {};
                    V3IdentityRequestCompositionPostgresTest.await(
                            () -> h.f.user(subject) == null, Duration.ofSeconds(90));
                    h.evidence(
                            "l3_command_lock_until_resolution_"
                                    + outcome
                                    + "_rollback_"
                                    + rollback);
                }
            }
        }
    }

    @Test
    void l3_rollback_boundaries() throws Exception {
        try (var h = new Harness()) {
            String token = h.register("a");
            for (String boundary :
                    List.of(
                            "after_protect",
                            "after_revision",
                            "after_result",
                            "after_terminal",
                            "after_complete",
                            "after_prefix",
                            "after_positions")) {
                var command =
                        h.command(
                                "a",
                                token,
                                new PotId(UUID.randomUUID()),
                                0,
                                new PotCommand.CreatePot(new Label(boundary)));
                var before = h.business();
                h.protectedEui =
                        () -> {
                            if (boundary.equals("after_protect"))
                                throw new IllegalStateException(boundary);
                        };
                h.sqlHook =
                        (s, after) -> {
                            boolean match =
                                    switch (boundary) {
                                        case "after_revision" ->
                                                s.startsWith("insert into v3_pot_details");
                                        case "after_result" ->
                                                s.startsWith("insert into v3_command_result");
                                        case "after_terminal" ->
                                                s.startsWith("update v3_command set status");
                                        case "after_complete" ->
                                                s.startsWith("delete from consumption_slot");
                                        case "after_prefix" ->
                                                s.startsWith(
                                                        "insert into v3_command_completion_event");
                                        case "after_positions" ->
                                                s.startsWith("insert into v3_event_position");
                                        default -> false;
                                    };
                            if (after && match)
                                throw new IllegalStateException("injected " + boundary);
                        };
                var attempt = h.execute(command);
                assertEquals(ConsumptionProtocol.Status.ROLLED_BACK, attempt.status(), boundary);
                assertEquals(AttemptTransactions.Resolution.ROLLED_BACK, attempt.resolution());
                h.protectedEui = () -> {};
                h.sqlHook = (s, after) -> {};
                assertEquals(before, h.business(), boundary);
                h.pending(command);
                h.trace.add("rollback boundary=" + boundary + " durable snapshot identical");
            }
            h.evidence("l3_rollback_boundaries");
        }
    }

    @Test
    void l3_registration_and_same_user_relink_collide_in_engine() throws Exception {
        for (String initial : List.of("absent", "zero", "free", "same-user-relink")) {
            try (var h = new Harness()) {
                String token = "invalid";
                if (initial.equals("zero")) {
                    var c =
                            new PotCommand(
                                    UUID.randomUUID(),
                                    new PotCommand.Eui("controlled-runtime", "target"),
                                    token,
                                    new PotId(UUID.randomUUID()),
                                    0,
                                    new PotCommand.CreatePot(new Label("zero")));
                    h.owner.write(
                            () ->
                                    h.real.protect(
                                            c, new PotActorAuthority.Witness(0, Optional.empty())));
                }
                if (initial.equals("free")) {
                    String old = h.register("target");
                    h.f.verify(
                            h.f.admit(
                                    "UNLINK",
                                    "target",
                                    "target",
                                    new com.kartaguez.pocoma.domain.identity.v3.BindingToken(
                                            old)));
                }
                String initiator = initial.equals("same-user-relink") ? h.register("a") : null;
                UUID user = null;
                if (initiator != null) {
                    h.f.verify(
                            h.f.admit(
                                    "LINK",
                                    "a",
                                    "target",
                                    new com.kartaguez.pocoma.domain.identity.v3.BindingToken(
                                            initiator)));
                    token = h.f.ownToken("target").value();
                    user = h.f.user("target");
                }
                var command =
                        h.command(
                                "target",
                                token,
                                new PotId(UUID.randomUUID()),
                                0,
                                new PotCommand.CreatePot(new Label(initial)));
                var prepared = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                h.observed =
                        () -> {
                            prepared.countDown();
                            await(release);
                        };
                var handle = h.acquire(command);
                var result = h.threads.submit(() -> h.engine.execute(handle));
                await(prepared);
                try {
                    if (initiator == null) h.register("target");
                    else {
                        var authority =
                                new com.kartaguez.pocoma.domain.identity.v3.BindingToken(
                                        initiator);
                        h.f.verify(h.f.admit("UNLINK", "a", "target", authority));
                        h.f.verify(h.f.admit("LINK", "a", "target", authority));
                        assertEquals(user, h.f.user("target"));
                        assertTrue(!token.equals(h.f.ownToken("target").value()));
                    }
                } finally {
                    release.countDown();
                }
                var attempt = result.get(20, TimeUnit.SECONDS);
                assertEquals(ConsumptionProtocol.Status.ROLLED_BACK, attempt.status());
                assertEquals(AttemptFailureClassifier.FailureKind.COLLISION, attempt.failureKind());
                h.pending(command);
                assertEquals(
                        0,
                        h.f.write.queryForObject(
                                "select count(*) from v3_pot_version", Integer.class));
                assertEquals(
                        0,
                        h.f.write.queryForObject(
                                "select count(*) from v3_pot_event", Integer.class));
                h.observed = () -> {};
                h.retryAfter(command);
                h.completed(command, "REJECTED", "INVALID_BINDING");
                h.trace.add(
                        "engine variant="
                                + initial
                                + "; collision even absent/absent or same User; original token"
                                + " retained");
                h.evidence("l3_registration_relink_collision_" + initial);
            }
        }
    }

    @Test
    void l3_atomic_command_physical_unit_and_closed_suffix() throws Exception {
        try (var h = new Harness()) {
            String token = h.register("a");
            var command =
                    h.command(
                            "a",
                            token,
                            new PotId(UUID.randomUUID()),
                            0,
                            new PotCommand.CreatePot(new Label("physical")));
            var protectedXid = new AtomicReference<String>();
            h.protectedEui =
                    () ->
                            protectedXid.set(
                                    h.jdbc.queryForObject(
                                            "select pg_current_xact_id()::text", String.class));
            h.sql.clear();
            h.completed(command, "CHANGED", null);
            var xids =
                    h.f.write.queryForList(
                            "select writer_xid::text from v3_pot_version where pot_id=? union"
                                + " select xmin::text from v3_pot_header where pot_id=? union"
                                + " select xmin::text from v3_pot_details where pot_id=? union"
                                + " select xmin::text from v3_command_result where request_id=?"
                                + " union select xmin::text from v3_pot_event where request_id=?"
                                + " union select xmin::text from v3_command_completion_event where"
                                + " subject_id=?",
                            String.class,
                            command.potId().value(),
                            command.potId().value(),
                            command.potId().value(),
                            command.requestId(),
                            command.requestId(),
                            command.requestId());
            assertEquals(List.of(protectedXid.get()), xids);
            var suffix =
                    h.sql.subList(
                            h.sql.indexOf(
                                    h.sql.stream()
                                            .filter(s -> s.startsWith("update v3_event_counter"))
                                            .findFirst()
                                            .orElseThrow()),
                            h.sql.size());
            assertTrue(
                    suffix.stream()
                            .allMatch(
                                    s ->
                                            s.startsWith("update v3_event_counter")
                                                    || s.startsWith(
                                                            "insert into v3_event_position")),
                    suffix.toString());
            assertEquals(
                    List.of("COMMAND_COMPLETION", "POT"),
                    h.f.write.queryForList(
                            "select family from v3_event_position where family in"
                                    + " ('POT','COMMAND_COMPLETION') order by family",
                            String.class));
            h.trace.add(
                    "EUI/Pot/result/events one xid="
                            + protectedXid.get()
                            + "; suffix only counter UPDATE and position INSERT; exact one"
                            + " event/completion");
            h.evidence("l3_atomic_command_physical_unit_and_closed_suffix");
        }
    }

    @Test
    void l3_real_authority_preserves_claim_fencing() throws Exception {
        try (var h = new Harness()) {
            String token = h.register("a");
            for (String variant : List.of("takeover", "expired", "partition-handover")) {
                var command =
                        h.command(
                                "a",
                                token,
                                new PotId(UUID.randomUUID()),
                                0,
                                new PotCommand.CreatePot(new Label(variant)));
                var handle = h.acquire(command);
                var before = h.business();
                if (variant.equals("takeover"))
                    h.f.write.update(
                            "update consumption_slot set claim_token=gen_random_uuid() where"
                                + " consumable_id=?",
                            command.requestId());
                else if (variant.equals("expired"))
                    h.f.write.update(
                            "update consumption_slot set claimed_until=clock_timestamp()-interval"
                                + " '1 second' where consumable_id=?",
                            command.requestId());
                else {
                    int partition =
                            RoutingV1.partition(
                                    RoutingV1.hash(ConsumableFamily.COMMAND, command.requestId()),
                                    4);
                    h.f.write.update(
                            "update partition_ownership set owner_token=gen_random_uuid() where"
                                + " consumption_id=? and partition_id=?",
                            handle.claim().consumptionId().value(),
                            partition);
                    h.leases.remove(partition);
                }
                var attempt = h.engine.execute(handle);
                if (variant.equals("partition-handover")) {
                    assertEquals(ConsumptionProtocol.Status.COMMITTED, attempt.status());
                    h.f.verifyCommand(command, "CHANGED", null);
                } else {
                    assertEquals(ConsumptionProtocol.Status.CLAIM_LOST, attempt.status());
                    assertEquals(
                            AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                            attempt.failureKind());
                    assertNull(attempt.cession());
                    h.pending(command);
                    assertEquals(before, h.business());
                    assertEquals(
                            0,
                            h.f.write.queryForObject(
                                    "select attempt_count from consumption_slot where"
                                        + " consumable_id=?",
                                    Integer.class,
                                    command.requestId()));
                }
                h.trace.add(
                        "real Authority fencing variant="
                                + variant
                                + " request="
                                + command.requestId()
                                + " status="
                                + attempt.status());
            }
            h.evidence("l3_real_authority_preserves_claim_fencing");
        }
    }

    @Test
    void l3_unknown_commit() throws Exception {
        // Reuse the existing runtime PostgreSQL wire proxy, without copying its protocol.
        var type =
                Class.forName(
                        "com.kartaguez.pocoma.runtime.command.consumption.eventprotocol.CommitAcknowledgementProxy");
        var constructor = type.getDeclaredConstructor(String.class, int.class);
        constructor.setAccessible(true);
        var port = type.getDeclaredMethod("localPort");
        port.setAccessible(true);
        var armed = type.getDeclaredField("armed");
        armed.setAccessible(true);
        var committed = type.getDeclaredField("committedReplies");
        committed.setAccessible(true);
        var fault = Class.forName(type.getName() + "$Fault");
        for (String variant : List.of("BEFORE_COMMIT", "AFTER_COMMIT")) {
            try (var proxy =
                            (AutoCloseable)
                                    constructor.newInstance(PG.getHost(), PG.getMappedPort(5432));
                    var h = new Harness("jdbc:postgresql://127.0.0.1:" + port.invoke(proxy))) {
                String token = h.register("a");
                var command =
                        h.command(
                                "a",
                                token,
                                new PotId(UUID.randomUUID()),
                                0,
                                new PotCommand.CreatePot(new Label("wire")));
                h.sqlHook =
                        (s, after) -> {
                            if (after && s.startsWith("insert into v3_event_position")) {
                                var value =
                                        Arrays.stream(fault.getEnumConstants())
                                                .filter(e -> e.toString().equals(variant))
                                                .findFirst()
                                                .orElseThrow();
                                ((AtomicReference<Object>) get(armed, proxy)).set(value);
                            }
                        };
                var handle = h.acquire(command);
                var attempt = h.engine.execute(handle);
                h.sqlHook = (s, after) -> {};
                assertEquals(AttemptTransactions.Resolution.UNKNOWN, attempt.resolution());
                assertThrows(IllegalArgumentException.class, () -> h.engine.execute(handle));
                assertEquals(
                        variant.equals("AFTER_COMMIT")
                                ? RecoveryObservation.COMPLETED
                                : RecoveryObservation.NOT_COMPLETED,
                        attempt.observation());
                assertEquals(1, h.engine.settlePending());
                assertEquals(
                        AttemptFailureClassifier.FailureKind.NON_COUNTABLE, attempt.failureKind());
                if (variant.equals("AFTER_COMMIT")) {
                    assertEquals(1, ((AtomicInteger) get(committed, proxy)).get());
                    h.f.verifyCommand(command, "CHANGED", null);
                    assertEquals(
                            RecoveryObservation.COMPLETED,
                            h.owner.read(
                                    c ->
                                            new JdbcPotCommandObservation(h.source)
                                                    .observe(command.requestId(), c)));
                } else {
                    h.pending(command);
                    assertEquals(
                            0,
                            h.f.write.queryForObject(
                                    "select count(*) from v3_pot_version", Integer.class));
                    assertEquals(
                            RecoveryObservation.NOT_COMPLETED,
                            h.owner.read(
                                    c ->
                                            new JdbcPotCommandObservation(h.source)
                                                    .observe(command.requestId(), c)));
                    assertEquals(
                            0,
                            h.f.write.queryForObject(
                                    "select attempt_count from consumption_slot where"
                                            + " consumable_id=?",
                                    Integer.class,
                                    command.requestId()));
                    var partition =
                            RoutingV1.partition(
                                    RoutingV1.hash(ConsumableFamily.COMMAND, command.requestId()),
                                    4);
                    assertTrue(
                            h.engine
                                    .acquire(h.leases.get(partition), List.of(command.requestId()))
                                    .claims()
                                    .isEmpty());
                    var nextRenewal = new AtomicLong(System.nanoTime());
                    V3IdentityRequestCompositionPostgresTest.await(
                            () -> {
                                if (System.nanoTime() >= nextRenewal.get()) {
                                    h.leases.put(
                                            partition,
                                            h.engine
                                                    .renewPartitions(
                                                            List.of(h.leases.get(partition)))
                                                    .getFirst());
                                    nextRenewal.set(
                                            System.nanoTime() + Duration.ofSeconds(5).toNanos());
                                }
                                return Boolean.TRUE.equals(
                                        h.f.write.queryForObject(
                                                "select claimed_until<=clock_timestamp() from"
                                                        + " consumption_slot where consumable_id=?",
                                                Boolean.class,
                                                command.requestId()));
                            },
                            Duration.ofSeconds(65));
                    var fresh = h.acquire(command);
                    assertNotEquals(handle.claim().claimToken(), fresh.claim().claimToken());
                    assertEquals(
                            ConsumptionProtocol.Status.COMMITTED, h.engine.execute(fresh).status());
                    h.f.verifyCommand(command, "CHANGED", null);
                }
                assertEquals(
                        1,
                        h.f.write.queryForObject(
                                "select count(*) from v3_pot_version", Integer.class));
                assertEquals(
                        1,
                        h.f.write.queryForObject(
                                "select count(*) from v3_pot_event", Integer.class));
                h.trace.add(
                        "wire="
                                + variant
                                + " physical independent reread; no invented retry budget");
                h.evidence("l3_unknown_commit_" + variant);
            }
        }
    }

    static Object get(Field field, Object target) {
        try {
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
