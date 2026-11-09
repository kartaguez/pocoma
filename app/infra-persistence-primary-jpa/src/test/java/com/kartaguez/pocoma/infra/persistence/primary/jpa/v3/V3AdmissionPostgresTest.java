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
                                                entered.countDown();
                                                await(release);
                                                store.insert(i.command(a.requestId()));
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
    void sensitiveRequestReadsDeniedAndPendingAdmissionsCommit() throws Exception {
        service().admit(principal("subject"), "command", input("subject", "private label"));
        var identity =
                new AdmitIdentityRequestV3(store, owner, protection, Clock.systemUTC(), 4096);
        for (var raw :
                List.of(
                        Map.of("operation", "Registration"),
                        Map.of(
                                "operation",
                                "Unlink",
                                "bindingToken",
                                "private-token",
                                "target",
                                Map.of("issuer", "issuer", "subject", "target")))) {
            var decoded = IdentityAdmissionInput.decode(raw, principal("subject").identity());
            var id = identity.admit(principal("subject"), decoded.operation(), decoded);
            assertEquals(id, identity.admit(principal("subject"), decoded.operation(), decoded));
        }
        assertEquals(1, count("v3_command"));
        assertEquals(2, count("v3_identity_request"));
        assertEquals(3, count("v3_admission_association"));
        assertEquals(0, count("v3_command_result"));
        assertEquals(0, count("v3_identity_request_result"));
        for (String query :
                List.of(
                        "select binding_token from v3_command",
                        "select payload from v3_command",
                        "select requester_subject from v3_command",
                        "select * from v3_command",
                        "select binding_token from v3_identity_request",
                        "select admission_context from v3_identity_request",
                        "select requester_subject from v3_identity_request",
                        "select * from v3_identity_request")) {
            try (var c = source.getConnection()) {
                assertEquals(
                        "42501",
                        assertThrows(
                                        SQLException.class,
                                        () -> c.createStatement().executeQuery(query))
                                .getSQLState());
            }
        }
        // Column grants survive a table REVOKE. Reapplying provisioning must remove them too.
        observer.execute("grant select(binding_token) on v3_command to " + role);
        assertThrows(
                IllegalStateException.class,
                () -> AdmissionSqlAuthority.verify(source, PG.getDatabaseName(), schema, role));
        script("grant-l5-admission.sql");
        AdmissionSqlAuthority.verify(source, PG.getDatabaseName(), schema, role);
        try (var c = source.getConnection()) {
            assertEquals(
                    "42501",
                    assertThrows(
                                    SQLException.class,
                                    () ->
                                            c.createStatement()
                                                    .executeQuery(
                                                            "select binding_token from v3_command"))
                            .getSQLState());
        }
        evidence(
                "column-authority",
                Map.of(
                        "commandPending",
                        1,
                        "identityPending",
                        2,
                        "associations",
                        3,
                        "deniedQueries",
                        8,
                        "deniedSqlState",
                        "42501",
                        "provisioningReapplied",
                        true));
    }

    @Test
    void identityInsertFailureRollsBackAssociation() {
        var failing =
                new AdmitIdentityRequestV3.Store() {
                    public Optional<AdmissionAssociation> find(byte[] scope) {
                        return store.find(scope);
                    }

                    public boolean reserve(AdmissionAssociation a) {
                        return store.reserve(a);
                    }

                    public void insert(
                            com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest r) {
                        store.insert(r);
                        assertEquals(0, count("v3_identity_request"));
                        assertEquals(0, count("v3_admission_association"));
                        throw new IllegalStateException("after Identity insertion, before commit");
                    }
                };
        var service =
                new AdmitIdentityRequestV3(failing, owner, protection, Clock.systemUTC(), 4096);
        assertEquals(
                503,
                assertThrows(
                                AdmissionFailure.class,
                                () ->
                                        service.admit(
                                                principal("subject"),
                                                "identity",
                                                IdentityAdmissionInput.decode(
                                                        Map.of("operation", "Registration"),
                                                        principal("subject").identity())))
                        .status());
        assertEquals(0, count("v3_identity_request"));
        assertEquals(0, count("v3_admission_association"));
    }

    @Test
    void deferredPendingChecksStillRejectEffects() throws Exception {
        var commandId = service().admit(principal("subject"), "command", input("subject", "A"));
        var identityId =
                new AdmitIdentityRequestV3(store, owner, protection, Clock.systemUTC(), 4096)
                        .admit(
                                principal("subject"),
                                "identity",
                                IdentityAdmissionInput.decode(
                                        Map.of("operation", "Registration"),
                                        principal("subject").identity()));
        for (var entry :
                Map.of(
                                "insert into v3_command_result(request_id,outcome,rejection) values"
                                    + " ('"
                                                + commandId
                                                + "','REJECTED','INVALID_BINDING')",
                                        "Pending Command has result",
                                "insert into"
                                    + " v3_identity_request_result(request_id,outcome,rejection)"
                                    + " values ('"
                                                + identityId
                                                + "','REJECTED','INVALID_BINDING')",
                                        "Pending Identity has effects",
                                "insert into v3_pocoma_user(user_id,created_request_id) values"
                                    + " (gen_random_uuid(),'"
                                                + identityId
                                                + "')",
                                        "Pending Identity has effects")
                        .entrySet()) {
            // Trusted corruption injection is uncommitted; the actual deferred check runs as
            // admission.
            try (var c = observer.getDataSource().getConnection()) {
                c.setAutoCommit(false);
                try {
                    c.createStatement().execute(entry.getKey());
                    assertEquals(0, count("v3_command_result"));
                    assertEquals(0, count("v3_identity_request_result"));
                    assertEquals(0, count("v3_pocoma_user"));
                    c.createStatement().execute("set local role " + role);
                    var failure =
                            assertThrows(
                                    SQLException.class,
                                    () ->
                                            c.createStatement()
                                                    .execute("set constraints all immediate"));
                    assertEquals("23514", failure.getSQLState());
                    assertTrue(failure.getMessage().contains(entry.getValue()));
                } finally {
                    c.rollback();
                }
            }
        }
        assertEquals(0, count("v3_command_result"));
        assertEquals(0, count("v3_identity_request_result"));
        assertEquals(0, count("v3_pocoma_user"));
        assertEquals(2, count("v3_admission_association"));
        evidence(
                "deferred-integrity",
                Map.of(
                        "rejectedEffects",
                        3,
                        "sqlState",
                        "23514",
                        "observerSawUncommittedEffects",
                        false,
                        "durableEffects",
                        0));
    }

    @Test
    void narrowedFunctionsPreserveInheritedIntegrityPredicates() throws Exception {
        // Compare deployed bodies to the inherited source, permitting only reviewed SELECT
        // projections.
        var resources = Path.of("src/main/resources/db");
        var command =
                Files.readString(
                        resources.resolve(
                                "v3-consumption-write/migration/V5__consumption_foundation.sql"));
        var identity =
                Files.readString(
                        resources.resolve(
                                "v3-identity/migration/V6__identity_authority_and_requests.sql"));
        for (var entry :
                Map.of("v3_require_command_unit", command, "v3_identity_check_unit", identity)
                        .entrySet()) {
            var inherited = entry.getValue();
            int start = inherited.indexOf("function " + entry.getKey() + "(");
            start = inherited.indexOf("$$", start) + 2;
            int end = inherited.indexOf("$$;", start);
            inherited = inherited.substring(start, end);
            var actual =
                    observer.queryForObject(
                            "select prosrc from pg_proc where oid=?::regproc",
                            String.class,
                            entry.getKey());
            // The 5 projections are the sole executable delta. Conditions/order/locks stay
            // byte-equivalent.
            String projection =
                    "select [^;]+ into [^;]+ from"
                        + " (v3_command|v3_command_result|v3_identity_request|v3_identity_request_result|v3_identity_completion_event)"
                        + " where ([^;]+);";
            inherited = inherited.replaceAll(projection, "select PROJECTION from $1 where $2;");
            actual = actual.replaceAll(projection, "select PROJECTION from $1 where $2;");
            assertEquals(inherited, actual, entry.getKey());
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

    @Test
    void sequentialRequestPublicationAndTriggerInvariants() throws Exception {
        var cmdService = service();
        var idService =
                new AdmitIdentityRequestV3(store, owner, protection, Clock.systemUTC(), 4096);
        var c1 = cmdService.admit(principal("subject"), "c1", input("subject", "cmd-1"));
        var c2 = cmdService.admit(principal("subject"), "c2", input("subject", "cmd-2"));
        // Idempotent replay of c1 and c2 does not increment counter or duplicate position
        assertEquals(c1, cmdService.admit(principal("subject"), "c1", input("subject", "cmd-1")));
        assertEquals(c2, cmdService.admit(principal("subject"), "c2", input("subject", "cmd-2")));

        var regInput =
                IdentityAdmissionInput.decode(
                        Map.of("operation", "Registration"), principal("subject").identity());
        var unlinkInput =
                IdentityAdmissionInput.decode(
                        Map.of(
                                "operation",
                                "Unlink",
                                "bindingToken",
                                "tok-1",
                                "target",
                                Map.of("issuer", "issuer", "subject", "target")),
                        principal("subject").identity());
        var i1 = idService.admit(principal("subject"), regInput.operation(), regInput);
        var i2 = idService.admit(principal("subject"), unlinkInput.operation(), unlinkInput);
        assertEquals(i1, idService.admit(principal("subject"), regInput.operation(), regInput));

        assertEquals(
                2L,
                observer.queryForObject(
                        "select position from v3_request_counter where family='COMMAND'",
                        Long.class));
        assertEquals(
                2L,
                observer.queryForObject(
                        "select position from v3_request_counter where family='IDENTITY_REQUEST'",
                        Long.class));
        assertEquals(
                List.of(c1, c2),
                observer.queryForList(
                        "select request_id from v3_request_position where family='COMMAND' order by"
                                + " position",
                        UUID.class));
        assertEquals(
                List.of(i1, i2),
                observer.queryForList(
                        "select request_id from v3_request_position where family='IDENTITY_REQUEST'"
                                + " order by position",
                        UUID.class));

        // Rollback after insert leaves zero gaps
        var rollbackDs = faultyCommit(false, null, null);
        var rollbackTx = owner(rollbackDs);
        var rollbackStore = new JdbcAdmissionStore(rollbackDs, rollbackTx);
        var rollbackService =
                new AdmitCommandV3(
                        rollbackStore,
                        new AdmissionSqlTransactions(rollbackDs, rollbackTx),
                        protection,
                        Clock.systemUTC(),
                        4096);
        assertEquals(
                503,
                assertThrows(
                                AdmissionFailure.class,
                                () ->
                                        rollbackService.admit(
                                                principal("subject"),
                                                "rolled-back",
                                                input("subject", "rolled-back")))
                        .status());
        var c3 = cmdService.admit(principal("subject"), "c3", input("subject", "cmd-3"));
        assertEquals(
                3L,
                observer.queryForObject(
                        "select position from v3_request_counter where family='COMMAND'",
                        Long.class));
        assertEquals(
                List.of(1L, 2L, 3L),
                observer.queryForList(
                        "select position from v3_request_position where family='COMMAND' order by"
                                + " position",
                        Long.class));
        assertEquals(
                c3,
                observer.queryForObject(
                        "select request_id from v3_request_position where family='COMMAND' and"
                                + " position=3",
                        UUID.class));

        // Out-of-order commit ("transaction tardive"): T1 starts and inserts into v3_command before
        // T2 starts, T2 commits first at position 4, T1 commits second at position 5.
        var t1InsertedCommand = new CountDownLatch(1);
        var t2Committed = new CountDownLatch(1);
        var slowStore =
                new AdmitCommandV3.Store() {
                    public Optional<AdmissionAssociation> find(byte[] scope) {
                        return store.find(scope);
                    }

                    public boolean reserve(AdmissionAssociation a) {
                        return store.reserve(a);
                    }

                    public void insert(
                            com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand c) {
                        var jdbc = new JdbcTemplate(source);
                        jdbc.update(
                                "insert into"
                                    + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                                    + " values (?,?,?,?,?,?,?,?::jsonb)",
                                c.requestId(),
                                c.requester().issuer(),
                                c.requester().subject(),
                                c.bindingToken(),
                                c.potId().value(),
                                c.observedVersion(),
                                c.intent().getClass().getSimpleName(),
                                PotCommandJson.encode(c));
                        t1InsertedCommand.countDown();
                        try {
                            assertTrue(t2Committed.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                        Long pos =
                                jdbc.queryForObject(
                                        "update v3_request_counter set position=position+1 where"
                                                + " family='COMMAND' returning position",
                                        Long.class);
                        jdbc.update(
                                "insert into v3_request_position(family,position,request_id) values"
                                        + " ('COMMAND',?,?)",
                                pos,
                                c.requestId());
                    }
                };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var f1 =
                    pool.submit(
                            () ->
                                    new AdmitCommandV3(
                                                    slowStore,
                                                    new AdmissionSqlTransactions(source, owner),
                                                    protection,
                                                    Clock.systemUTC(),
                                                    4096)
                                            .admit(
                                                    principal("subject"),
                                                    "late-t1",
                                                    input("subject", "late-t1")));
            assertTrue(t1InsertedCommand.await(5, TimeUnit.SECONDS));
            var fastId =
                    cmdService.admit(principal("subject"), "fast-t2", input("subject", "fast-t2"));
            assertEquals(
                    List.of(1L, 2L, 3L, 4L),
                    observer.queryForList(
                            "select position from v3_request_position where family='COMMAND' order"
                                    + " by position",
                            Long.class));
            t2Committed.countDown();
            var lateId = f1.get(5, TimeUnit.SECONDS);
            assertEquals(
                    fastId,
                    observer.queryForObject(
                            "select request_id from v3_request_position where family='COMMAND' and"
                                    + " position=4",
                            UUID.class));
            assertEquals(
                    lateId,
                    observer.queryForObject(
                            "select request_id from v3_request_position where family='COMMAND' and"
                                    + " position=5",
                            UUID.class));
        }

        // Concurrency: 16 threads admitting simultaneously -> dense positions 6..21
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var barrier = new CyclicBarrier(16);
            var futures = new ArrayList<Future<UUID>>();
            for (int k = 0; k < 16; k++) {
                int idx = k;
                futures.add(
                        pool.submit(
                                () -> {
                                    barrier.await(5, TimeUnit.SECONDS);
                                    return cmdService.admit(
                                            principal("subject"),
                                            "conc-" + idx,
                                            input("subject", "conc-" + idx));
                                }));
            }
            for (var f : futures) assertNotNull(f.get(10, TimeUnit.SECONDS));
        }
        assertEquals(
                21L,
                observer.queryForObject(
                        "select position from v3_request_counter where family='COMMAND'",
                        Long.class));
        var positions =
                observer.queryForList(
                        "select position from v3_request_position where family='COMMAND' order by"
                                + " position",
                        Long.class);
        assertEquals(21, positions.size());
        for (int k = 0; k < 21; k++) assertEquals((long) (k + 1), positions.get(k));

        // Negative SQL invariants (immutability, step, deferred unit triggers)
        for (String invalid :
                List.of(
                        "delete from v3_request_position where family='COMMAND' and position=1",
                        "update v3_request_position set position=999 where family='COMMAND' and"
                                + " position=1",
                        "truncate v3_request_position",
                        "delete from v3_request_counter where family='COMMAND'",
                        "insert into v3_request_counter(family,position) values ('COMMAND',0)",
                        "update v3_request_counter set position=position+2 where family='COMMAND'",
                        "update v3_request_counter set position=position where family='COMMAND'",
                        "insert into v3_request_position(family,position,request_id) values"
                                + " ('COMMAND',99,'"
                                + c1
                                + "')")) {
            try (var c = observer.getDataSource().getConnection()) {
                c.setAutoCommit(false);
                try {
                    assertThrows(SQLException.class, () -> c.createStatement().execute(invalid));
                } finally {
                    c.rollback();
                }
            }
        }
        for (String deferredInvalid :
                List.of(
                        "update v3_request_counter set position=position+1 where family='COMMAND'",
                        "with p as (update v3_request_counter set position=position+1 where"
                            + " family='COMMAND' returning position) insert into"
                            + " v3_request_position(family,position,request_id) select"
                            + " 'COMMAND',position,gen_random_uuid() from p",
                        "insert into"
                            + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                            + " values"
                            + " (gen_random_uuid(),'i','s','t',gen_random_uuid(),0,'CreatePot','{\"label\":\"x\"}'::jsonb)",
                        "insert into"
                            + " v3_identity_request(request_id,requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,admission_context,created_at)"
                            + " select"
                            + " gen_random_uuid(),requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,admission_context,created_at"
                            + " from v3_identity_request limit 1")) {
            try (var c = observer.getDataSource().getConnection()) {
                c.setAutoCommit(false);
                try {
                    c.createStatement().execute(deferredInvalid);
                    assertEquals(
                            "23514",
                            assertThrows(
                                            SQLException.class,
                                            () ->
                                                    c.createStatement()
                                                            .execute(
                                                                    "set constraints all"
                                                                            + " immediate"))
                                    .getSQLState());
                } finally {
                    c.rollback();
                }
            }
        }

        // Verify discovery_checkpoint is authorized for COMMAND and IDENTITY_REQUEST
        observer.update(
                "insert into consumption_routing_contract(consumption_id,family,routing_version,partition_count) values"
                        + " ('98f7268d-d340-581c-b83d-010926223e14','COMMAND','sha256-v1',4),"
                        + " ('2999e75c-272d-5afe-a966-c9364ef021fe','IDENTITY_REQUEST','sha256-v1',4)");
        observer.update(
                "insert into discovery_checkpoint(consumption_id,partition_id,position) values"
                        + " ('98f7268d-d340-581c-b83d-010926223e14',0,21),"
                        + " ('2999e75c-272d-5afe-a966-c9364ef021fe',0,2)");
        assertEquals(2, count("discovery_checkpoint"));
    }
}
