package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import javax.sql.DataSource;

@Testcontainers
class IdentityAuthorityPostgresTest extends IdentityPostgresFixture {
    @Test
    void l3_history_exact() {
        var e1 = key("e1");
        var e2 = key("e2");
        var e3 = key("e3");
        var u1 = changed(run(admit(registration(e1)))).identity().userId();
        var u2 = changed(run(admit(registration(e3)))).identity().userId();
        var a = current(e1);
        var c = current(e3);
        var b = changed(run(admit(link(e1, a.bindingToken(), e2)))).identity();
        var free2 = changed(run(admit(unlink(e1, a.bindingToken(), e2)))).identity();
        var bound3 = changed(run(admit(link(e3, c.bindingToken(), e2)))).identity();
        var free4 = changed(run(admit(unlink(e3, c.bindingToken(), e2)))).identity();
        var bound5 = changed(run(admit(link(e1, a.bindingToken(), e2)))).identity();
        assertEquals(
                List.of(1L, 2L, 3L, 4L, 5L),
                List.of(
                        b.version(),
                        free2.version(),
                        bound3.version(),
                        free4.version(),
                        bound5.version()));
        assertEquals(u1, b.userId());
        assertEquals(u2, bound3.userId());
        assertEquals(u1, bound5.userId());
        assertNull(free2.userId());
        assertNull(free4.bindingToken());
        assertEquals(b.id(), bound5.id());
        assertTrue(
                Set.of(b.bindingToken(), bound3.bindingToken(), bound5.bindingToken()).size() == 3,
                "All historical occurrence tokens differ");
        for (var state : List.of(b, free2, bound3, free4, bound5))
            assertTrue(
                    tx.<Boolean>read(
                            ctx ->
                                    authority
                                            .at(b.id(), state.version())
                                            .orElseThrow()
                                            .equals(state)),
                    "Exact historical state retained");
        assertTrue(tx.<Boolean>read(ctx -> authority.at(b.id(), 6).isEmpty()));
        assertCounts(2, 7, 7, 9, 7);
        assertTrue(
                run(admit(link(e1, a.bindingToken(), e2)))
                        instanceof IdentityTransitionResult.Rejected);
        assertTrue(
                run(admit(unlink(e2, b.bindingToken(), e1)))
                        instanceof IdentityTransitionResult.Rejected);
        assertCounts(2, 7, 9, 9, 9);
    }

    @Test
    void l3_last_unlink_and_registration_free() {
        var e = key("self");
        var first = changed(run(admit(registration(e)))).identity();
        var free = changed(run(admit(unlink(e, first.bindingToken(), e)))).identity();
        assertNull(free.userId());
        assertTrue(tx.<Boolean>read(ctx -> authority.user(first.userId()).isPresent()));
        var next = changed(run(admit(registration(e)))).identity();
        assertEquals(first.id(), next.id());
        assertEquals(3, next.version());
        assertFalse(first.userId().equals(next.userId()));
        assertFalse(first.bindingToken().equals(next.bindingToken()));
        assertCounts(2, 3, 3, 5, 3);
    }

    @Test
    void l3_anchor_absence() {
        var e = key("unknown");
        assertEquals(0, count("v3_eui_authority"));
        var id =
                tx.write(
                        () -> {
                            var ids = authority.lockInCanonicalOrder(List.of(e));
                            assertTrue(authority.current(e).isEmpty());
                            return ids.get(e);
                        });
        assertEquals(1, count("v3_eui_authority"));
        assertCounts(0, 0, 0, 0, 0);
        assertInstanceOf(
                IllegalArgumentException.class,
                assertThrows(
                                com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.Failure
                                        .class,
                                () -> tx.read(ctx -> authority.at(id, 0)))
                        .getCause());
        var first = changed(run(admit(registration(e)))).identity();
        assertEquals(id, first.id());
        assertEquals(1, first.version());
        assertTrue(first.isBound());
        var other = key("other");
        var otherId =
                tx.write(
                        () -> {
                            var ids = authority.lockInCanonicalOrder(List.of(other));
                            assertTrue(authority.current(other).isEmpty());
                            return ids.get(other);
                        });
        var linked = changed(run(admit(link(e, first.bindingToken(), other)))).identity();
        assertEquals(otherId, linked.id());
        assertEquals(1, linked.version());
        assertCounts(1, 2, 2, 3, 2);
    }

    @Test
    void l3_forced_token_collision() {
        var first = changed(run(admit(registration(key("first"))))).identity();
        var failing = admit(registration(key("collision"))).withFresh(first.bindingToken());
        var before = vector();
        var failure = assertThrows(RuntimeException.class, () -> run(failing));
        assertTrue(allMessages(failure).contains("23505"));
        assertFalse(
                allMessages(failure).contains(first.bindingToken().value()),
                "SQL DETAIL must not escape");
        assertEquals(before, vector());
        assertPendingWithSlot(failing.id());
        // Historical, no longer current tokens remain reserved as well.
        run(admit(unlink(key("first"), first.bindingToken(), key("first"))));
        var historical = admit(registration(key("historical"))).withFresh(first.bindingToken());
        before = vector();
        assertThrows(RuntimeException.class, () -> run(historical));
        assertEquals(before, vector());
    }

    @Test
    void l3_rollback_boundaries() {
        for (String boundary :
                List.of(
                        "after_lock",
                        "after_apply",
                        "after_result",
                        "after_terminal",
                        "after_delete",
                        "after_user_event",
                        "after_events",
                        "after_completion",
                        "after_structural",
                        "before_close",
                        "after_positions",
                        "before_commit")) {
            var work = admit(registration(key(boundary)));
            var before = vector();
            assertThrows(
                    RuntimeException.class,
                    () ->
                            run(
                                    work,
                                    s -> {
                                        if (s.equals(boundary)) {
                                            evidence.add("fault boundary=" + boundary);
                                            throw new IllegalStateException("injected " + boundary);
                                        }
                                    }));
            assertEquals(before, vector(), boundary);
            assertPendingWithSlot(work.id());
        }
        // A SQL fault on the first state INSERT occurs after the User INSERT in apply.
        admin.execute(
                "create function fixture_after_user() returns trigger language plpgsql as $$ begin"
                    + " raise exception 'injected after User' using errcode='23514'; end $$");
        admin.execute(
                "create trigger fixture_after_user before insert on v3_eui_version for each row"
                    + " execute function fixture_after_user()");
        var work = admit(registration(key("after-user")));
        var before = vector();
        assertThrows(RuntimeException.class, () -> run(work));
        assertEquals(before, vector());
    }

    @Test
    void l3_guards_and_caught_failure_poison() {
        assertThrows(
                RuntimeException.class,
                () -> authority.lockInCanonicalOrder(List.of(key("outside"))));
        var work = admit(registration(key("caught")));
        var before = vector();
        assertThrows(
                RuntimeException.class,
                () ->
                        run(
                                work,
                                s -> {
                                    if (s.equals("after_positions"))
                                        assertThrows(
                                                RuntimeException.class,
                                                () ->
                                                        authority.lockInCanonicalOrder(
                                                                List.of(key("late"))));
                                }));
        assertEquals(before, vector());
        assertThrows(
                RuntimeException.class,
                () ->
                        tx.write(
                                () -> {
                                    var state =
                                            new ExternalUserIdentity(
                                                    new ExternalUserIdentityId(UUID.randomUUID()),
                                                    key("unprotected"),
                                                    1,
                                                    new PocomaUserId(UUID.randomUUID()),
                                                    tokens.generate());
                                    try {
                                        authority.apply(
                                                new IdentityTransitionResult.Changed(
                                                        Optional.empty(), state),
                                                work.id());
                                    } catch (RuntimeException expected) {
                                    }
                                    return null;
                                }));
        assertEquals(before, vector());
    }
}

/** I2-only controlled admission/completion harness. No production participant or runtime. */
@Testcontainers
abstract class IdentityPostgresFixture {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    org.testcontainers.utility.DockerImageName.parse(
                                    "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73")
                            .asCompatibleSubstituteFor("postgres"));

    static final UUID CONSUMPTION = UUID.fromString("2999e75c-272d-5afe-a966-c9364ef021fe");
    static final String STRUCTURAL =
            "set constraints v3_identity_anchor_state,v3_identity_version_state immediate";
    DataSource source, adminSource;
    JdbcTemplate jdbc, admin;
    SpringAttemptTransactions tx;
    JdbcIdentityAuthority authority;
    String schema;
    final SecureBindingTokenGenerator tokens = new SecureBindingTokenGenerator();
    final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    final List<String> evidence = new CopyOnWriteArrayList<>();
    final List<LockCall> lockCalls = new CopyOnWriteArrayList<>();

    record LockCall(int pid, String operation, ExternalIdentity key) {}

    record Work(
            UUID id,
            ExternalIdentity requester,
            String operation,
            BindingToken presented,
            ExternalIdentity target,
            BindingToken fresh) {
        Work withFresh(BindingToken token) {
            return new Work(id, requester, operation, presented, target, token);
        }

        @Override
        public String toString() {
            return "Work[" + id + "," + operation + "]";
        }
    }

    @BeforeEach
    void setup() {
        schema = "l3_i2_" + UUID.randomUUID().toString().replace("-", "");
        adminSource =
                new DriverManagerDataSource(
                        PG.getJdbcUrl()
                                + "&currentSchema="
                                + schema
                                + "&socketTimeout=30&options=-c%20lock_timeout=15000%20-c%20statement_timeout=25000",
                        PG.getUsername(),
                        PG.getPassword());
        admin = new JdbcTemplate(adminSource);
        admin.execute("create extension if not exists pgcrypto with schema public");
        admin.execute("create role " + schema + "_runtime nologin");
        Flyway.configure()
                .dataSource(adminSource)
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(true)
                .locations(
                        "classpath:db/v3-pot/migration",
                        "classpath:db/v3-pot-execution/migration",
                        "classpath:db/v3-consumption-write/migration",
                        "classpath:db/v3-identity/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                PG.getDatabaseName(),
                                "expectedSchema",
                                schema,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                schema + "_runtime"))
                .load()
                .migrate();
        admin.execute("grant usage on schema " + schema + " to " + schema + "_runtime");
        admin.execute(
                "grant select,insert,update,delete on"
                    + " v3_identity_request,v3_eui_authority,v3_pocoma_user,v3_eui_version,v3_identity_request_result,v3_identity_event,v3_identity_completion_event,v3_event_counter,v3_event_position,consumption_slot"
                    + " to "
                        + schema
                        + "_runtime");
        admin.execute("grant select on consumption_routing_contract to " + schema + "_runtime");
        admin.update(
                "insert into consumption_routing_contract values"
                    + " (?,'IDENTITY_REQUEST','sha256-v1',4)",
                CONSUMPTION);
        source =
                new AbstractDataSource() {
                    @Override
                    public Connection getConnection() throws SQLException {
                        Connection connection = adminSource.getConnection();
                        try (var s = connection.createStatement()) {
                            s.execute("set role " + schema + "_runtime");
                        }
                        int pid =
                                connection
                                        .unwrap(org.postgresql.PGConnection.class)
                                        .getBackendPID();
                        return (Connection)
                                Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class[] {Connection.class},
                                        (p, m, args) -> {
                                            Object result = invoke(connection, m, args);
                                            if (m.getName().equals("prepareStatement")
                                                    && args[0] instanceof String sql)
                                                return trace((PreparedStatement) result, sql, pid);
                                            return result;
                                        });
                    }

                    @Override
                    public Connection getConnection(String u, String p) throws SQLException {
                        return getConnection();
                    }
                };
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        tx = new SpringAttemptTransactions(source, manager, 25);
        authority = new JdbcIdentityAuthority(source, tx);
        evidence.add("PostgreSQL=" + admin.queryForObject("select version()", String.class));
    }

    static Object invoke(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    Object trace(PreparedStatement statement, String sql, int pid) {
        String normalized = sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        boolean insert =
                normalized.startsWith("insert into v3_eui_authority")
                        && normalized.contains("values (?,?,?)");
        boolean lock =
                normalized.startsWith("select eui_id from v3_eui_authority")
                        && normalized.endsWith("for update");
        if (!insert && !lock) return statement;
        var values = new HashMap<Integer, Object>();
        return Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {PreparedStatement.class},
                (p, m, args) -> {
                    if (m.getName().startsWith("set")
                            && args != null
                            && args.length > 1
                            && args[0] instanceof Integer i) values.put(i, args[1]);
                    if (m.getName().startsWith("execute")) {
                        var key =
                                new ExternalIdentity(
                                        (String) values.get(insert ? 2 : 1),
                                        (String) values.get(insert ? 3 : 2));
                        lockCalls.add(new LockCall(pid, insert ? "INSERT" : "FOR UPDATE", key));
                    }
                    return invoke(statement, m, args);
                });
    }

    @AfterEach
    void collect(TestInfo info) throws Exception {
        threads.shutdownNow();
        assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS));
        var path =
                Path.of(
                        "target/l3-i2-evidence",
                        getClass().getSimpleName()
                                + "-"
                                + info.getTestMethod().orElseThrow().getName()
                                + ".txt");
        Files.createDirectories(path.getParent());
        evidence.add("lockCalls=" + lockCalls);
        evidence.add("durable comparison digests=" + vector());
        for (String table :
                List.of(
                        "v3_eui_authority",
                        "v3_pocoma_user",
                        "v3_identity_request_result",
                        "v3_identity_event",
                        "v3_identity_completion_event",
                        "v3_event_counter",
                        "v3_event_position"))
            evidence.add(
                    table + "=" + admin.queryForList("select * from " + table + " order by 1"));
        evidence.add(
                "states (tokens omitted)="
                        + admin.queryForList(
                                "select eui_id,version,user_id,binding_token is not null"
                                    + " token_present,request_id,writer_xid from v3_eui_version"
                                    + " order by eui_id,version"));
        evidence.add(
                "requests (tokens/context omitted)="
                        + admin.queryForList(
                                "select"
                                    + " request_id,requester_issuer,requester_subject,operation,target_issuer,target_subject,status"
                                    + " from v3_identity_request order by request_id"));
        Files.write(path, evidence);
    }

    static ExternalIdentity key(String subject) {
        return new ExternalIdentity("i2-controlled", subject);
    }

    Work registration(ExternalIdentity e) {
        return new Work(UUID.randomUUID(), e, "REGISTRATION", null, e, tokens.generate());
    }

    Work link(ExternalIdentity e, BindingToken token, ExternalIdentity target) {
        return new Work(UUID.randomUUID(), e, "LINK", token, target, tokens.generate());
    }

    Work unlink(ExternalIdentity e, BindingToken token, ExternalIdentity target) {
        return new Work(UUID.randomUUID(), e, "UNLINK", token, target, null);
    }

    Work admit(Work w) {
        jdbc.update(
                """
insert into v3_identity_request(request_id,requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,admission_context,created_at)
values (?,?,?,?,?,?,?,jsonb_build_object('validator','I2 harness','contractVersion','1',
  'initiator',jsonb_build_object('issuer',?::text,'subject',?::text),
  'target',jsonb_build_object('issuer',?::text,'subject',?::text),
  'intentionFingerprint',?::text,'grantOrCorrelationReference',?::text,'validatedAt','2026-10-30T00:00:00Z',
  'attestations','{}'::jsonb,'origin','CONTROLLED_INTERNAL'),clock_timestamp())
""",
                w.id(),
                w.requester().issuer(),
                w.requester().subject(),
                w.operation(),
                w.presented() == null ? null : w.presented().value(),
                w.target().issuer(),
                w.target().subject(),
                w.requester().issuer(),
                w.requester().subject(),
                w.target().issuer(),
                w.target().subject(),
                w.id().toString(),
                w.id().toString());
        jdbc.update(
                """
insert into consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)
select ?,?,h,((h::bigint%4+4)%4)::int from (select consumption_hash_v1('IDENTITY_REQUEST',?) h) x
""",
                CONSUMPTION, w.id(), w.id());
        return w;
    }

    IdentityTransitionResult run(Work w) {
        return run(w, s -> {});
    }

    IdentityTransitionResult run(Work w, Consumer<String> hook) {
        try {
            var resolved =
                    tx.write(
                            () -> {
                                int pid =
                                        jdbc.queryForObject(
                                                "select pg_backend_pid()", Integer.class);
                                evidence.add(
                                        "begin="
                                                + w.id()
                                                + " pid="
                                                + pid
                                                + " xid="
                                                + jdbc.queryForObject(
                                                        "select pg_current_xact_id()::text",
                                                        String.class));
                                assertEquals(
                                        1,
                                        jdbc.queryForList(
                                                        "select 1 from consumption_slot where"
                                                            + " consumption_id=? and"
                                                            + " consumable_id=? for update",
                                                        CONSUMPTION,
                                                        w.id())
                                                .size());
                                jdbc.queryForList(
                                        "select request_id from v3_identity_request where"
                                            + " request_id=? for update",
                                        w.id());
                                var ids =
                                        authority.lockInCanonicalOrder(
                                                List.of(w.requester(), w.target()));
                                hook.accept("after_lock");
                                var initiator = authority.current(w.requester());
                                var target = authority.current(w.target());
                                var transition =
                                        switch (w.operation()) {
                                            case "REGISTRATION" ->
                                                    IdentityTransitions.registration(
                                                            w.requester(),
                                                            target,
                                                            ids.get(w.target()),
                                                            new PocomaUserId(UUID.randomUUID()),
                                                            w.fresh());
                                            case "LINK" ->
                                                    IdentityTransitions.link(
                                                            w.requester(),
                                                            w.presented(),
                                                            initiator,
                                                            w.target(),
                                                            target,
                                                            ids.get(w.target()),
                                                            w.fresh());
                                            case "UNLINK" ->
                                                    IdentityTransitions.unlink(
                                                            w.requester(),
                                                            w.presented(),
                                                            initiator,
                                                            w.target(),
                                                            target);
                                            default -> throw new AssertionError();
                                        };
                                if (transition instanceof IdentityTransitionResult.Changed changed)
                                    authority.apply(changed, w.id());
                                hook.accept("after_apply");
                                finish(w, transition, hook);
                                evidence.add(
                                        "ready="
                                                + w.id()
                                                + " outcome="
                                                + transition.getClass().getSimpleName());
                                return transition;
                            });
            evidence.add("physical=" + w.id() + " COMMITTED");
            return resolved;
        } catch (com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.Failure failure) {
            evidence.add("physical=" + w.id() + " " + failure.resolution());
            throw failure;
        }
    }

    void finish(Work w, IdentityTransitionResult result, Consumer<String> hook) {
        var changed = result instanceof IdentityTransitionResult.Changed c ? c : null;
        jdbc.update(
                "insert into v3_identity_request_result values (?,?,?,?,?)",
                w.id(),
                changed == null ? "REJECTED" : "CHANGED",
                changed == null ? null : changed.identity().id().value(),
                changed == null ? null : changed.identity().version(),
                result instanceof IdentityTransitionResult.Rejected r ? r.reason().name() : null);
        hook.accept("after_result");
        jdbc.update("update v3_identity_request set status='TERMINAL' where request_id=?", w.id());
        hook.accept("after_terminal");
        assertEquals(
                1,
                jdbc.update(
                        "delete from consumption_slot where consumption_id=? and consumable_id=?",
                        CONSUMPTION,
                        w.id()));
        hook.accept("after_delete");
        var events = new ArrayList<UUID>();
        if (changed != null) {
            changed.createdUser()
                    .ifPresent(
                            u -> {
                                events.add(event(w.id(), "POCOMA_USER", u.id().value(), 1));
                                hook.accept("after_user_event");
                            });
            events.add(
                    event(
                            w.id(),
                            "EXTERNAL_USER_IDENTITY",
                            changed.identity().id().value(),
                            changed.identity().version()));
        }
        hook.accept("after_events");
        UUID completion = UUID.randomUUID();
        jdbc.update(
                "insert into v3_identity_completion_event values (?,?,?,clock_timestamp())",
                completion,
                w.operation(),
                w.id());
        hook.accept("after_completion");
        jdbc.execute(STRUCTURAL);
        hook.accept("after_structural");
        jdbc.queryForObject("select v3_identity_check_unit(?,false)", Object.class, w.id());
        tx.flushBeforePublication();
        hook.accept("before_close");
        SpringAttemptTransactions.closePublication(source);
        if (!events.isEmpty()) position("IDENTITY", events);
        position("IDENTITY_COMPLETION", List.of(completion));
        hook.accept("after_positions");
        hook.accept("before_commit");
    }

    UUID event(UUID request, String type, UUID subject, long version) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "insert into v3_identity_event values (?,?,?,?,?,clock_timestamp())",
                id,
                type,
                subject,
                version,
                request);
        return id;
    }

    void position(String family, List<UUID> events) {
        long end =
                jdbc.queryForObject(
                        "update v3_event_counter set position=position+? where family=? returning"
                            + " position",
                        Long.class,
                        events.size(),
                        family);
        for (int i = 0; i < events.size(); i++)
            jdbc.update(
                    "insert into v3_event_position values (?,?,?)",
                    family,
                    end - events.size() + 1 + i,
                    events.get(i));
    }

    ExternalUserIdentity current(ExternalIdentity e) {
        return tx.write(
                () -> {
                    authority.lockInCanonicalOrder(List.of(e));
                    return authority.current(e).orElseThrow();
                });
    }

    static IdentityTransitionResult.Changed changed(IdentityTransitionResult r) {
        assertInstanceOf(IdentityTransitionResult.Changed.class, r);
        return (IdentityTransitionResult.Changed) r;
    }

    long count(String table) {
        return admin.queryForObject("select count(*) from " + table, Long.class);
    }

    void assertCounts(long users, long states, long results, long events, long completions) {
        assertEquals(users, count("v3_pocoma_user"));
        assertEquals(states, count("v3_eui_version"));
        assertEquals(results, count("v3_identity_request_result"));
        assertEquals(events, count("v3_identity_event"));
        assertEquals(completions, count("v3_identity_completion_event"));
        assertEquals(events + completions, count("v3_event_position"));
    }

    void assertPendingWithSlot(UUID id) {
        assertEquals(
                "PENDING",
                admin.queryForObject(
                        "select status from v3_identity_request where request_id=?",
                        String.class,
                        id));
        assertEquals(
                1,
                admin.queryForObject(
                        "select count(*) from consumption_slot where consumable_id=?",
                        Integer.class,
                        id));
    }

    Map<String, Object> vector() {
        var result = new LinkedHashMap<String, Object>();
        for (String table :
                List.of(
                        "v3_pocoma_user",
                        "v3_eui_authority",
                        "v3_eui_version",
                        "v3_identity_request_result",
                        "v3_identity_event",
                        "v3_identity_completion_event",
                        "v3_event_counter",
                        "v3_event_position",
                        "consumption_slot")) {
            // Private equality snapshot includes tokens only as digests, never raw values in
            // reports.
            result.put(
                    table,
                    admin.queryForList(
                            "select md5(row_to_json(t)::text) as row_digest from "
                                    + table
                                    + " t order by row_digest"));
        }
        result.put(
                "requests",
                admin.queryForList(
                        "select request_id,status from v3_identity_request order by request_id"));
        return result;
    }

    static String allMessages(Throwable t) {
        var s = new StringBuilder();
        for (Throwable x = t; x != null; x = x.getCause()) s.append(x.getMessage()).append('\n');
        return s.toString();
    }

    static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(15, TimeUnit.SECONDS), "barrier reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    void blocked(int waiter, int blocker) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Boolean.TRUE.equals(
                    admin.queryForObject(
                            "select ?=any(pg_blocking_pids(?))", Boolean.class, blocker, waiter))) {
                evidence.add("server blocking waiter=" + waiter + " blocker=" + blocker);
                return;
            }
            Thread.onSpinWait();
        }
        fail("Server blocking edge not observed");
    }
}
