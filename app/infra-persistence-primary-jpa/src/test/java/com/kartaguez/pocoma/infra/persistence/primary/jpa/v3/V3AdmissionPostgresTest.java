package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.contracts.command.v3.*;
import com.kartaguez.pocoma.engine.admit.command.v3.*;
import com.kartaguez.pocoma.engine.admit.registration.v3.*;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.*;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import javax.sql.DataSource;

/**
 * Real role/physical SQL oracles. HTTP A=1 is deliberately bypassed for concurrent transactions.
 */
@Testcontainers
class V3AdmissionPostgresTest {
    static final String IMAGE =
            "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73";

    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                            DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
                    .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

    String schema, role, worker, url;
    JdbcTemplate observer;
    DataSource source;
    SpringAttemptTransactions owner;
    JdbcAdmissionStore store;
    final AdmissionProtection protection =
            new AdmissionProtection("stable", new byte[32], "old", Map.of("old", new byte[32]));
    final String pot = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void setup() throws Exception {
        schema = "i2_" + UUID.randomUUID().toString().replace("-", "");
        role = schema + "_admission";
        worker = schema + "_worker";
        url =
                PG.getJdbcUrl()
                        + "&currentSchema="
                        + schema
                        + ",public&ApplicationName=i2-admission-test&options=-c%20lock_timeout=5000%20-c%20statement_timeout=10000";
        var admin = new DriverManagerDataSource(url, PG.getUsername(), PG.getPassword());
        observer = new JdbcTemplate(admin);
        observer.execute("create extension if not exists pgcrypto with schema public");
        observer.execute("create extension if not exists pg_stat_statements with schema public");
        observer.execute(
                "revoke all on public.pg_stat_statements,public.pg_stat_statements_info from"
                        + " public");
        observer.execute("create role " + worker + " nologin");
        observer.execute("create role " + role + " login password 'ephemeral-i2'");
        Flyway.configure()
                .dataSource(admin)
                .schemas(schema)
                .defaultSchema(schema)
                .locations(
                        "classpath:db/v3-pot/migration",
                        "classpath:db/v3-pot-execution/migration",
                        "classpath:db/v3-consumption-write/migration",
                        "classpath:db/v3-identity/migration",
                        "classpath:db/v3-admission/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                PG.getDatabaseName(),
                                "expectedSchema",
                                schema,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                worker))
                .load()
                .migrate();
        script("grant-l5-admission.sql");
        source = new DriverManagerDataSource(url, role, "ephemeral-i2");
        owner = owner(source);
        store = new JdbcAdmissionStore(source, owner);
        AdmissionSqlAuthority.verify(source, PG.getDatabaseName(), schema, role);
    }

    void script(String name) throws Exception {
        String destination = "/tmp/" + schema + name;
        PG.copyFileToContainer(
                MountableFile.forHostPath(Path.of("../../scripts/v3/" + name).toAbsolutePath()),
                destination);
        var r =
                PG.execInContainer(
                        "env",
                        "PGOPTIONS=-c search_path=" + schema,
                        "psql",
                        "-X",
                        "-U",
                        PG.getUsername(),
                        "-d",
                        PG.getDatabaseName(),
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-v",
                        "expected_database=" + PG.getDatabaseName(),
                        "-v",
                        "expected_schema=" + schema,
                        "-v",
                        "expected_admin=" + PG.getUsername(),
                        "-v",
                        "admission_role=" + role,
                        "-f",
                        destination);
        assertEquals(0, r.getExitCode(), r.getStderr());
    }

    static SpringAttemptTransactions owner(DataSource s) {
        var m = new DataSourceTransactionManager(s);
        m.setNestedTransactionAllowed(false);
        return new SpringAttemptTransactions(s, m, 15);
    }

    AuthenticatedExternalPrincipal principal(String subject) {
        var now = Instant.now();
        return new AuthenticatedExternalPrincipal(
                "issuer",
                subject,
                now.minusSeconds(1),
                now.minusSeconds(1),
                now.plusSeconds(300),
                Set.of());
    }

    CommandAdmissionInput input(String subject, String label) {
        return CommandAdmissionInput.decode(
                Map.of(
                        "operation",
                        "CreatePot",
                        "potId",
                        pot,
                        "observedVersion",
                        0,
                        "bindingToken",
                        "opaque",
                        "payload",
                        Map.of("label", label)),
                new PotCommand.Eui("issuer", subject),
                1024);
    }

    AdmitCommandV3 service() {
        return new AdmitCommandV3(
                store,
                new AdmissionSqlTransactions(source, owner),
                protection,
                Clock.systemUTC(),
                4096);
    }

    long count(String table) {
        return observer.queryForObject("select count(*) from " + table, Long.class);
    }

    void pair(long expected) {
        assertEquals(expected, count("v3_command"));
        assertEquals(expected, count("v3_admission_association"));
        assertEquals(
                0L,
                observer.queryForObject(
                        "select count(*) from v3_admission_association a left join v3_command c on"
                            + " a.command_id=c.request_id left join v3_identity_request i on"
                            + " a.identity_request_id=i.request_id where c.request_id is null and"
                            + " i.request_id is null",
                        Long.class));
    }

    AdmissionAssociation association(String key, CommandAdmissionInput input) {
        return new AdmissionAssociation(
                UUID.randomUUID(),
                "COMMAND",
                input.operation(),
                1,
                "stable",
                "old",
                protection.scope(
                        "issuer", "subject", Optional.empty(), "COMMAND", input.operation(), key),
                protection.intent("old", 1, input.canonical(1)),
                Instant.now());
    }

    void evidence(String name, Object values) throws Exception {
        var parent =
                Path.of(System.getProperty("pocoma.verification.reports", "target/reports"))
                        .getParent()
                        .resolve("i2-sql");
        Files.createDirectories(parent);
        Files.writeString(
                parent.resolve(name + ".json"),
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writerWithDefaultPrettyPrinter()
                        .writeValueAsString(values));
    }

    Map<String, Object> sqlMeasures() {
        var values =
                new LinkedHashMap<String, Object>(
                        observer.queryForMap(
                                "select coalesce(sum(calls),0)"
                                    + " calls,coalesce(sum(total_exec_time),0)"
                                    + " execution_ms,coalesce(sum(wal_bytes),0)"
                                    + " wal_bytes,coalesce(sum(shared_blks_hit),0) shared_hits from"
                                    + " public.pg_stat_statements where userid=(select oid from"
                                    + " pg_roles where rolname=?)",
                                role));
        values.putAll(
                observer.queryForMap(
                        "select pg_relation_size('v3_admission_association')"
                            + " heap_bytes,pg_indexes_size('v3_admission_association')"
                            + " index_bytes,coalesce((select avg(pg_column_size(a)) from"
                            + " v3_admission_association a),0)"
                            + " average_tuple_bytes,pg_current_wal_lsn()::text cluster_wal_lsn"));
        return values;
    }

    @Test
    void firstAndReplay() throws Exception {
        var phases = new ArrayList<Object>();
        var writeNs = new long[1];
        var observedOwner = new AdmissionSqlTransactions(source, owner);
        AttemptTransactions timed =
                new AttemptTransactions() {
                    public <T> T read(
                            java.util.function.Function<
                                            com.kartaguez.pocoma.port.transaction.v3
                                                    .RecoveryReadContext,
                                            T>
                                    action,
                            Guard guard) {
                        return observedOwner.read(action, guard);
                    }

                    public <T> T write(java.util.function.Supplier<T> action, Guard guard) {
                        long began = System.nanoTime();
                        try {
                            return observedOwner.write(action, guard);
                        } finally {
                            writeNs[0] = System.nanoTime() - began;
                        }
                    }

                    public void poison(Throwable failure) {
                        observedOwner.poison(failure);
                    }

                    public void requirePublicationClosed() {
                        observedOwner.requirePublicationClosed();
                    }
                };
        var service = new AdmitCommandV3(store, timed, protection, Clock.systemUTC(), 4096);
        UUID id = null;
        for (String phase : List.of("first", "replay", "conflict")) {
            var before = sqlMeasures();
            long start = System.nanoTime();
            writeNs[0] = 0;
            if (phase.equals("first"))
                id = service.admit(principal("subject"), "key", input("subject", "A"));
            else if (phase.equals("replay"))
                assertEquals(id, service.admit(principal("subject"), "key", input("subject", "A")));
            else
                assertEquals(
                        409,
                        assertThrows(
                                        AdmissionFailure.class,
                                        () ->
                                                service.admit(
                                                        principal("subject"),
                                                        "key",
                                                        input("subject", "B")))
                                .status());
            long elapsed = System.nanoTime() - start;
            var after = sqlMeasures();
            phases.add(
                    Map.of(
                            "phase",
                            phase,
                            "before",
                            before,
                            "after",
                            after,
                            "monotonicNs",
                            elapsed,
                            "ownerWriteNsIncludingAcquireAndPhysicalTermination",
                            writeNs[0]));
            pair(1);
        }
        assertEquals(0, count("consumption_slot"));
        evidence(
                "firstAndReplay",
                Map.of(
                        "requestId",
                        id.toString(),
                        "phases",
                        phases,
                        "observerRole",
                        PG.getUsername(),
                        "admissionRole",
                        role,
                        "limitations",
                        "finite samples, no SLO/percentile; owner span includes connection"
                                + " acquisition; cluster WAL is not exclusively attributed;"
                                + " pg_stat_statements WAL is role-attributed"));
    }

    @Test
    void differentIntentConflicts() {
        service().admit(principal("subject"), "key", input("subject", "A"));
        assertEquals(
                409,
                assertThrows(
                                AdmissionFailure.class,
                                () ->
                                        service()
                                                .admit(
                                                        principal("subject"),
                                                        "key",
                                                        input("subject", "B")))
                        .status());
        pair(1);
    }

    @Test
    void scopeIsolation() {
        var a = service().admit(principal("subject"), "key", input("subject", "A"));
        var b = service().admit(principal("other"), "key", input("other", "A"));
        assertNotEquals(a, b);
        pair(2);
    }

    void race(boolean different, boolean rollback, boolean distinct) throws Exception {
        var i = input("subject", "A");
        var a = association("key", i);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var secondReady = new CountDownLatch(1);
        var pids = new ConcurrentHashMap<String, Integer>();
        var wrapped =
                new AdmitCommandV3.Store() {
                    public Optional<AdmissionAssociation> find(byte[] scope) {
                        return store.find(scope);
                    }

                    public boolean reserve(AdmissionAssociation next) {
                        pids.put(
                                "B",
                                new JdbcTemplate(source)
                                        .queryForObject("select pg_backend_pid()", Integer.class));
                        secondReady.countDown();
                        return store.reserve(next);
                    }

                    public void insert(PotCommand c) {
                        store.insert(c);
                    }
                };
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    threads.submit(
                            () ->
                                    owner.write(
                                            () -> {
                                                pids.put(
                                                        "A",
                                                        new JdbcTemplate(source)
                                                                .queryForObject(
                                                                        "select pg_backend_pid()",
                                                                        Integer.class));
                                                assertTrue(store.reserve(a));
                                                store.insert(i.command(a.requestId()));
                                                entered.countDown();
                                                await(release);
                                                if (rollback)
                                                    throw new IllegalStateException(
                                                            "injected rollback");
                                                return a.requestId();
                                            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(0, count("v3_admission_association"));
            var second =
                    threads.submit(
                            () ->
                                    new AdmitCommandV3(
                                                    wrapped,
                                                    new AdmissionSqlTransactions(source, owner),
                                                    protection,
                                                    Clock.systemUTC(),
                                                    4096)
                                            .admit(
                                                    principal("subject"),
                                                    distinct ? "distinct" : "key",
                                                    input("subject", different ? "B" : "A")));
            assertTrue(secondReady.await(5, TimeUnit.SECONDS));
            assertNotEquals(pids.get("A"), pids.get("B"));
            if (distinct) {
                assertNotNull(second.get(5, TimeUnit.SECONDS));
                assertEquals(1, count("v3_admission_association"));
            } else {
                long end = System.nanoTime() + Duration.ofSeconds(4).toNanos();
                boolean blocked = false;
                while (System.nanoTime() < end) {
                    blocked =
                            Boolean.TRUE.equals(
                                    observer.queryForObject(
                                            "select exists(select 1 from pg_stat_activity where"
                                                    + " pid=? and wait_event_type='Lock')",
                                            Boolean.class,
                                            pids.get("B")));
                    if (blocked) break;
                    Thread.sleep(10);
                }
                assertTrue(blocked, "independent observer saw actual unique-index lock wait");
                assertFalse(second.isDone());
            }
            release.countDown();
            if (rollback)
                assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            else assertEquals(a.requestId(), first.get(5, TimeUnit.SECONDS));
            if (different && !rollback && !distinct) {
                var failure =
                        assertThrows(
                                ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS));
                assertEquals(409, ((AdmissionFailure) failure.getCause()).status());
            } else if (!distinct) {
                var winner = second.get(5, TimeUnit.SECONDS);
                if (rollback) assertNotEquals(a.requestId(), winner);
                else assertEquals(a.requestId(), winner);
            }
            pair(distinct ? 2 : 1);
            evidence(
                    "race-" + different + "-" + rollback + "-" + distinct,
                    Map.of(
                            "physicalPids",
                            pids,
                            "rollbackFirst",
                            rollback,
                            "differentIntent",
                            different,
                            "distinctKeys",
                            distinct,
                            "orphanCount",
                            0));
        } finally {
            release.countDown();
        }
    }

    static void await(CountDownLatch latch) {
        try {
            if (!latch.await(8, TimeUnit.SECONDS))
                throw new IllegalStateException("barrier deadline");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void concurrentSameKeySameIntent() throws Exception {
        race(false, false, false);
    }

    @Test
    void concurrentSameKeyDifferentIntent() throws Exception {
        race(true, false, false);
    }

    @Test
    void distinctKeysProgressIndependently() throws Exception {
        race(false, false, true);
    }

    @Test
    void rollbackAndOwnerBoundaries() throws Exception {
        var a = association("key", input("subject", "A"));
        assertThrows(
                RuntimeException.class,
                () ->
                        owner.write(
                                () -> {
                                    assertTrue(store.reserve(a));
                                    throw new IllegalStateException("between inserts");
                                }));
        pair(0);
        assertThrows(RuntimeException.class, () -> store.reserve(a));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JdbcAdmissionStore(
                                new DriverManagerDataSource(url, role, "ephemeral-i2"), owner));
        race(false, true, false);
    }

    DataSource faultyCommit(boolean commit, CountDownLatch entered, CountDownLatch release) {
        var once = new AtomicBoolean(true);
        return new AbstractDataSource() {
            public Connection getConnection() throws SQLException {
                var actual = source.getConnection();
                return (Connection)
                        Proxy.newProxyInstance(
                                Connection.class.getClassLoader(),
                                new Class<?>[] {Connection.class},
                                (proxy, method, args) -> {
                                    if (method.getName().equals("commit")
                                            && !actual.isReadOnly()
                                            && once.compareAndSet(true, false)) {
                                        if (entered != null) {
                                            entered.countDown();
                                            await(release);
                                        }
                                        if (commit) actual.commit();
                                        else actual.rollback();
                                        throw new SQLException(
                                                "injected acknowledgement loss", "08006");
                                    }
                                    try {
                                        return method.invoke(actual, args);
                                    } catch (InvocationTargetException failure) {
                                        throw failure.getCause();
                                    }
                                });
            }

            public Connection getConnection(String u, String p) throws SQLException {
                return getConnection();
            }
        };
    }

    @Test
    void lostCommitAcknowledgement() {
        var ds = faultyCommit(true, null, null);
        var tx = owner(ds);
        var s = new JdbcAdmissionStore(ds, tx);
        var service =
                new AdmitCommandV3(
                        s,
                        new AdmissionSqlTransactions(ds, tx),
                        protection,
                        Clock.systemUTC(),
                        4096);
        assertEquals(
                503,
                assertThrows(
                                AdmissionFailure.class,
                                () ->
                                        service.admit(
                                                principal("subject"), "key", input("subject", "A")))
                        .status());
        pair(1);
        var id =
                observer.queryForObject(
                        "select request_id from v3_admission_association", UUID.class);
        assertEquals(id, service().admit(principal("subject"), "key", input("subject", "A")));
        pair(1);
    }

    @Test
    void unknownCommitDoesNotCreateReplacement() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var ds = faultyCommit(true, entered, release);
        var tx = owner(ds);
        var service =
                new AdmitCommandV3(
                        new JdbcAdmissionStore(ds, tx),
                        new AdmissionSqlTransactions(ds, tx),
                        protection,
                        Clock.systemUTC(),
                        4096);
        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first =
                    threads.submit(
                            () ->
                                    service.admit(
                                            principal("subject"), "key", input("subject", "A")));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            pair(0);
            var retry =
                    threads.submit(
                            () ->
                                    service()
                                            .admit(
                                                    principal("subject"),
                                                    "key",
                                                    input("subject", "A")));
            Thread.sleep(100);
            assertFalse(retry.isDone());
            pair(0);
            release.countDown();
            assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
            assertNotNull(retry.get(5, TimeUnit.SECONDS));
            pair(1);
        } finally {
            release.countDown();
        }
        // Independent physically resolved rollback permits a normal first admission, never a repair
        // row.
        var rollback = faultyCommit(false, null, null);
        var rtx = owner(rollback);
        var rs =
                new AdmitCommandV3(
                        new JdbcAdmissionStore(rollback, rtx),
                        new AdmissionSqlTransactions(rollback, rtx),
                        protection,
                        Clock.systemUTC(),
                        4096);
        assertThrows(
                AdmissionFailure.class,
                () -> rs.admit(principal("subject"), "rolled", input("subject", "A")));
        pair(1);
        service().admit(principal("subject"), "rolled", input("subject", "A"));
        pair(2);
    }

    @Test
    void unavailablePhysicalObserverFailsClosed() {
        var ended = new AtomicBoolean();
        var faulty = faultyCommit(true, null, null);
        DataSource unavailable =
                new AbstractDataSource() {
                    public Connection getConnection() throws SQLException {
                        if (ended.get()) throw new SQLException("observer unavailable", "08006");
                        var c = faulty.getConnection();
                        return (Connection)
                                Proxy.newProxyInstance(
                                        Connection.class.getClassLoader(),
                                        new Class<?>[] {Connection.class},
                                        (p, m, a) -> {
                                            try {
                                                return m.invoke(c, a);
                                            } catch (InvocationTargetException f) {
                                                if (m.getName().equals("commit") && !c.isReadOnly())
                                                    ended.set(true);
                                                throw f.getCause();
                                            }
                                        });
                    }

                    public Connection getConnection(String user, String password)
                            throws SQLException {
                        return getConnection();
                    }
                };
        var tx = owner(unavailable);
        var service =
                new AdmitCommandV3(
                        new JdbcAdmissionStore(unavailable, tx),
                        new AdmissionSqlTransactions(unavailable, tx),
                        protection,
                        Clock.systemUTC(),
                        4096);
        var failure =
                assertThrows(
                        AdmissionFailure.class,
                        () ->
                                service.admit(
                                        principal("subject"), "unknown", input("subject", "A")));
        assertEquals(503, failure.status());
        assertFalse(failure.physicalTerminationObserved());
        pair(1);
        assertEquals(
                202,
                service().admit(principal("subject"), "unknown", input("subject", "A")) != null
                        ? 202
                        : 0);
        pair(1);
    }

    @Test
    void invalidLeavesNoDurable() {
        assertEquals(
                400,
                assertThrows(
                                AdmissionFailure.class,
                                () ->
                                        new AdmitCommandV3(
                                                        store,
                                                        owner,
                                                        protection,
                                                        Clock.systemUTC(),
                                                        1)
                                                .admit(
                                                        principal("subject"),
                                                        "key",
                                                        input("subject", "too long")))
                        .status());
        pair(0);
        for (String t :
                List.of(
                        "consumption_slot",
                        "v3_command_result",
                        "v3_pot_event",
                        "v3_identity_request",
                        "v3_identity_event")) assertEquals(0, count(t));
    }

    @Test
    void admissionNeverReadsBusinessTables() {
        var restricted = new JdbcTemplate(source);
        for (String sql :
                List.of(
                        "select * from v3_pot_identity",
                        "select binding_token from v3_eui_version",
                        "select * from v3_eui_authority"))
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> restricted.queryForList(sql));
        assertNotNull(
                service()
                        .admit(
                                principal("subject"),
                                "key",
                                input("subject", "unknown pot is admitted")));
        pair(1);
        var identity =
                new AdmitIdentityRequestV3(store, owner, protection, Clock.systemUTC(), 4096);
        identity.admit(
                principal("new"),
                "identity",
                IdentityAdmissionInput.decode(
                        Map.of("operation", "Registration"), principal("new").identity()));
        assertEquals(1, count("v3_identity_request"));
        assertEquals(0, count("v3_eui_authority"));
    }

    @Test
    void associationForeignKeysAndImmutability() {
        var a = association("key", input("subject", "A"));
        assertThrows(RuntimeException.class, () -> owner.write(() -> store.reserve(a)));
        pair(0);
        service().admit(principal("subject"), "key", input("subject", "A"));
        for (String sql :
                List.of(
                        "update v3_admission_association set operation='bad'",
                        "delete from v3_admission_association",
                        "truncate v3_admission_association",
                        "update v3_command set status='TERMINAL'"))
            assertThrows(
                    org.springframework.dao.DataAccessException.class,
                    () -> new JdbcTemplate(source).execute(sql));
        pair(1);
    }

    @Test
    void admissionRoleAndIndirectPrivileges() throws Exception {
        AdmissionSqlAuthority.verify(source, PG.getDatabaseName(), schema, role);
        observer.execute("grant maintain on v3_command to " + role);
        assertThrows(
                IllegalStateException.class,
                () -> AdmissionSqlAuthority.verify(source, PG.getDatabaseName(), schema, role));
        observer.execute("revoke maintain on v3_command from " + role);
        observer.execute("grant select(binding_token) on v3_eui_version to " + role);
        assertThrows(
                IllegalStateException.class,
                () -> AdmissionSqlAuthority.verify(source, PG.getDatabaseName(), schema, role));
        observer.execute("revoke select(binding_token) on v3_eui_version from " + role);
        observer.execute("create view indirect_identity as select * from v3_eui_version");
        observer.execute("grant select on indirect_identity to public");
        assertThrows(
                IllegalStateException.class,
                () -> AdmissionSqlAuthority.verify(source, PG.getDatabaseName(), schema, role));
        observer.execute("revoke select on indirect_identity from public");
        observer.execute(
                "create function unsafe_identity() returns text language sql security definer as"
                        + " 'select binding_token from v3_eui_version limit 1'");
        assertThrows(
                IllegalStateException.class,
                () -> AdmissionSqlAuthority.verify(source, PG.getDatabaseName(), schema, role));
        observer.execute("revoke execute on function unsafe_identity() from public");
        AdmissionSqlAuthority.verify(source, PG.getDatabaseName(), schema, role);
        for (String denied : List.of(worker, schema + "_read")) {
            if (denied.endsWith("_read")) observer.execute("create role " + denied + " nologin");
            observer.execute("grant usage on schema " + schema + " to " + denied);
            try (var c = observer.getDataSource().getConnection()) {
                c.createStatement().execute("set role " + denied);
                assertEquals(
                        "42501",
                        assertThrows(
                                        SQLException.class,
                                        () ->
                                                c.createStatement()
                                                        .execute(
                                                                "insert into v3_command(request_id)"
                                                                        + " values"
                                                                        + " (gen_random_uuid())"))
                                .getSQLState());
                assertEquals(
                        "42501",
                        assertThrows(
                                        SQLException.class,
                                        () ->
                                                c.createStatement()
                                                        .execute(
                                                                "insert into"
                                                                    + " v3_admission_association(request_id)"
                                                                    + " values"
                                                                    + " (gen_random_uuid())"))
                                .getSQLState());
            }
        }
    }

    @Test
    void restoreAndOldKeyCompatibility() {
        var i = input("subject", "historical long label");
        var a = association("restored", i);
        owner.write(
                () -> {
                    assertTrue(store.reserve(a));
                    store.insert(i.command(a.requestId()));
                    return null;
                });
        byte[] next = new byte[32];
        next[0] = 1;
        var rotated =
                new AdmissionProtection(
                        "stable", new byte[32], "new", Map.of("old", new byte[32], "new", next));
        assertEquals(
                a.requestId(),
                new AdmitCommandV3(
                                store,
                                new AdmissionSqlTransactions(source, owner),
                                rotated,
                                Clock.systemUTC(),
                                1)
                        .admit(principal("subject"), "restored", i));
        var missing = new AdmissionProtection("stable", new byte[32], "new", Map.of("new", next));
        assertEquals(
                503,
                assertThrows(
                                AdmissionFailure.class,
                                () ->
                                        new AdmitCommandV3(
                                                        store,
                                                        owner,
                                                        missing,
                                                        Clock.systemUTC(),
                                                        4096)
                                                .admit(principal("subject"), "restored", i))
                        .status());
        pair(1);
    }
}
