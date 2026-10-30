package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.engine.consume.registration.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

@Testcontainers
class IdentityRequestExecutionPostgresTest extends IdentityEngineFixture {
    @Test
    void l3_atomic_registration() {
        var req = request(new IdentityRequest.Registration(), key("one"), null, key("one"));
        admitRequest(req);
        assertEquals(0, count("consumption_slot"));
        assertEquals(0, count("v3_eui_authority"));
        assertEquals(ConsumptionEngine.Status.COMMITTED, execute(req).status());
        assertCounts(1, 1, 1, 2, 1);
        assertEquals(
                RecoveryObservation.COMPLETED,
                tx.read(c -> observation.observe(req.requestId(), c)));
        var before = vector();
        assertTrue(tx.<Boolean>read(c -> participant.prepare(req.requestId()).isEmpty()));
        assertEquals(before, vector());
        assertEquals(0, count("consumption_slot"));
    }

    @Test
    void l3_atomic_link_unlink() {
        var e1 = key("a");
        var e2 = key("b");
        var e3 = key("c");
        executeAdmitted(request(new IdentityRequest.Registration(), e1, null, e1));
        var a = current(e1);
        executeAdmitted(request(new IdentityRequest.Registration(), e3, null, e3));
        executeAdmitted(request(new IdentityRequest.Link(e2), e1, a.bindingToken(), e2));
        var b = current(e2);
        executeAdmitted(request(new IdentityRequest.Unlink(e2), e1, a.bindingToken(), e2));
        assertNull(current(e2).userId());
        assertEquals(2, current(e2).version());
        executeAdmitted(request(new IdentityRequest.Link(e2), e1, a.bindingToken(), e2));
        assertNotEquals(b.bindingToken(), current(e2).bindingToken());
        executeAdmitted(
                request(new IdentityRequest.Unlink(e2), e2, current(e2).bindingToken(), e2));
        executeAdmitted(request(new IdentityRequest.Unlink(e1), e1, a.bindingToken(), e1));
        assertTrue(tx.<Boolean>read(c -> authority.user(a.userId()).isPresent()));
        assertNull(current(e1).userId());
        assertCounts(2, 7, 7, 9, 7);
    }

    @Test
    void l3_rejection_terminal() {
        var e1 = key("a");
        var e2 = key("b");
        executeAdmitted(request(new IdentityRequest.Registration(), e1, null, e1));
        var a = current(e1);
        var bad = request(new IdentityRequest.Link(e2), e1, new BindingToken("wrong"), e2);
        executeAdmitted(bad);
        assertCounts(1, 1, 2, 2, 2);
        assertEquals(
                "INVALID_BINDING",
                admin.queryForObject(
                        "select rejection from v3_identity_request_result where request_id=?",
                        String.class,
                        bad.requestId()));
        assertFalse(store.toString().contains(a.bindingToken().value()));
    }

    @Test
    void l3_rollback_each_boundary() {
        for (String op : List.of("REGISTRATION", "LINK", "UNLINK")) {
            var e = key("initiator-" + op);
            executeAdmitted(request(new IdentityRequest.Registration(), e, null, e));
            var a = current(e);
            for (String boundary :
                    List.of(
                            "before_apply",
                            "after_user",
                            "after_state",
                            "after_result",
                            "after_terminal",
                            "after_delete",
                            "after_first_event",
                            "after_completion",
                            "before_close",
                            "after_positions")) {
                if (boundary.equals("after_user") && !op.equals("REGISTRATION")) continue;
                var target =
                        op.equals("REGISTRATION")
                                ? key(op + boundary)
                                : op.equals("LINK") ? key(op + boundary) : e;
                var req =
                        request(
                                op.equals("REGISTRATION")
                                        ? new IdentityRequest.Registration()
                                        : op.equals("LINK")
                                                ? new IdentityRequest.Link(target)
                                                : new IdentityRequest.Unlink(target),
                                op.equals("REGISTRATION") ? target : e,
                                op.equals("REGISTRATION") ? null : a.bindingToken(),
                                target);
                admitRequest(req);
                var claim = claim(req);
                var before = businessVector();
                installFailure(boundary);
                var result = engine.execute(claim);
                clearFailure();
                assertEquals(
                        ConsumptionEngine.Status.ROLLED_BACK,
                        result.status(),
                        boundary + " " + result.cause());
                assertEquals(before, businessVector(), op + "/" + boundary);
                assertPendingWithSlot(req.requestId());
            }
        }
    }

    @Test
    void l3_fencing() {
        for (String change :
                List.of(
                        "claim_token=gen_random_uuid()",
                        "claimed_until=clock_timestamp()-interval '1 second'",
                        "partition")) {
            var e = key(change);
            var req = request(new IdentityRequest.Registration(), e, null, e);
            admitRequest(req);
            var claim = claim(req);
            if (change.equals("partition"))
                admin.update(
                        "update partition_ownership set owner_token=gen_random_uuid() where"
                                + " consumption_id=? and partition_id=?",
                        CONSUMPTION,
                        RoutingV1.partition(
                                RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, req.requestId()),
                                4));
            else
                admin.update(
                        "update consumption_slot set " + change + " where consumable_id=?",
                        req.requestId());
            var before = businessVector();
            var result = engine.execute(claim);
            if (change.equals("partition"))
                assertEquals(ConsumptionEngine.Status.COMMITTED, result.status());
            else {
                assertEquals(ConsumptionEngine.Status.CLAIM_LOST, result.status());
                assertEquals(before, businessVector());
            }
        }
    }

    @Test
    void l3_workers_concurrent_registration() throws Exception {
        var key = key("racing");
        var r1 = request(new IdentityRequest.Registration(), key, null, key);
        var r2 = request(new IdentityRequest.Registration(), key, null, key);
        admitRequest(r1);
        admitRequest(r2);
        var c1 = claim(r1);
        discover(r2);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var one =
                threads.submit(
                        () -> {
                            hook =
                                    (b) -> {
                                        if (b.equals("after_state")) {
                                            locked.countDown();
                                            await(release);
                                        }
                                    };
                            return engine.execute(c1);
                        });
        await(locked);
        hook = b -> {};
        // Separate participant/engine and independently acquired connection, sharing only DB facts.
        var other =
                engine(
                        new ExecuteIdentityRequest(
                                store,
                                observation,
                                new JdbcIdentityAuthority(source, tx),
                                tokens::generate,
                                UUID::randomUUID),
                        tx);
        var p2 =
                RoutingV1.partition(
                        RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, r2.requestId()), 4);
        var c2 = other.acquire(lease(p2), List.of(r2.requestId())).claims().getFirst();
        var two =
                threads.submit(
                        () -> {
                            second.countDown();
                            return other.execute(c2);
                        });
        await(second);
        var firstPid =
                admin.queryForObject(
                        "select pid from pg_stat_activity where datname=current_database() and"
                                + " pid<>pg_backend_pid() and state='idle in transaction' order by"
                                + " xact_start limit 1",
                        Integer.class);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        boolean edge = false;
        while (System.nanoTime() < end) {
            edge =
                    admin.queryForObject(
                            "select exists(select 1 from pg_stat_activity where"
                                    + " ?=any(pg_blocking_pids(pid)))",
                            Boolean.class,
                            firstPid);
            if (edge) break;
            Thread.onSpinWait();
        }
        try {
            assertTrue(edge, "real EUI serialization observed");
            evidence.add("EUI race physical blocker=" + firstPid);
        } finally {
            release.countDown();
        }
        assertEquals(ConsumptionEngine.Status.COMMITTED, one.get(15, TimeUnit.SECONDS).status());
        assertEquals(ConsumptionEngine.Status.COMMITTED, two.get(15, TimeUnit.SECONDS).status());
        assertCounts(1, 1, 2, 2, 2);
        assertEquals(
                1,
                admin.queryForObject(
                        "select count(*) from v3_identity_request_result where outcome='REJECTED'",
                        Integer.class));
    }

    @Test
    void l3_retry_quarantine() {
        var e = key("retry");
        var req = request(new IdentityRequest.Registration(), e, null, e);
        admitRequest(req);
        for (int i = 1; i <= 3; i++) {
            var c = claim(req);
            installFailure("before_apply");
            assertEquals(ConsumptionEngine.Status.ROLLED_BACK, engine.execute(c).status());
            clearFailure();
            assertEquals(
                    i,
                    admin.queryForObject(
                            "select attempt_count from consumption_slot where consumable_id=?",
                            Integer.class,
                            req.requestId()));
        }
        assertEquals(
                "QUARANTINED",
                admin.queryForObject(
                        "select state from consumption_slot where consumable_id=?",
                        String.class,
                        req.requestId()));
        discover(req);
        assertEquals(1, count("consumption_slot"));
        admin.update(
                "update consumption_slot set"
                    + " state='AVAILABLE',attempt_count=0,retry_after=null,claim_token=null,claimed_until=null"
                    + " where consumable_id=?",
                req.requestId());
        assertEquals(ConsumptionEngine.Status.COMMITTED, engine.execute(claim(req)).status());
        assertCounts(1, 1, 1, 2, 1);
    }

    @Test
    void l3_technical_not_rejected_and_redacted() {
        var e = key("token-owner");
        executeAdmitted(request(new IdentityRequest.Registration(), e, null, e));
        var token = current(e).bindingToken();
        var other = key("collision");
        var request = request(new IdentityRequest.Registration(), other, null, other);
        admitRequest(request);
        discover(request);
        var participant =
                new ExecuteIdentityRequest(
                        store,
                        observation,
                        new JdbcIdentityAuthority(source, tx),
                        () -> token,
                        UUID::randomUUID);
        var worker = engine(participant, tx);
        int partition =
                RoutingV1.partition(
                        RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, request.requestId()), 4);
        var handle =
                worker.acquire(lease(partition), List.of(request.requestId())).claims().getFirst();
        var before = businessVector();
        var result = worker.execute(handle);
        assertEquals(ConsumptionEngine.Status.ROLLED_BACK, result.status());
        assertEquals(before, businessVector());
        assertPendingWithSlot(request.requestId());
        assertTrue(allMessages(result.cause()).contains("23505"));
        assertFalse(allMessages(result.cause()).contains(token.value()));
        assertEquals(
                1,
                admin.queryForObject(
                        "select attempt_count from consumption_slot where consumable_id=?",
                        Integer.class,
                        request.requestId()));
    }

    @Test
    void l3_completion_misuse() throws Exception {
        for (String mode : List.of("absent", "double", "wrong-thread", "late")) {
            var e = key("completion-" + mode);
            var request = request(new IdentityRequest.Registration(), e, null, e);
            admitRequest(request);
            discover(request);
            ConsumptionParticipant altered =
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID id) {
                            var p = participant.prepare(id).orElseThrow();
                            return Optional.of(
                                    new Prepared() {
                                        public void requireCompletionReady() {
                                            p.requireCompletionReady();
                                        }

                                        public void finalizeIn(SlotCompletion completion) {
                                            p.finalizeIn(
                                                    () -> {
                                                        switch (mode) {
                                                            case "absent", "late" -> {}
                                                            case "double" -> {
                                                                completion.complete();
                                                                assertThrows(
                                                                        RuntimeException.class,
                                                                        completion::complete);
                                                            }
                                                            case "wrong-thread" -> {
                                                                try {
                                                                    threads.submit(
                                                                                    () ->
                                                                                            assertThrows(
                                                                                                    RuntimeException
                                                                                                            .class,
                                                                                                    completion
                                                                                                            ::complete))
                                                                            .get(
                                                                                    5,
                                                                                    TimeUnit
                                                                                            .SECONDS);
                                                                } catch (Exception failure) {
                                                                    throw new RuntimeException(
                                                                            failure);
                                                                }
                                                            }
                                                        }
                                                    });
                                            if (mode.equals("late")) completion.complete();
                                        }
                                    });
                        }

                        public RecoveryObservation observe(UUID id, RecoveryReadContext ctx) {
                            return participant.observe(id, ctx);
                        }
                    };
            var alteredEngine = engine(altered, tx);
            int partition =
                    RoutingV1.partition(
                            RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, request.requestId()),
                            4);
            var claim =
                    alteredEngine
                            .acquire(lease(partition), List.of(request.requestId()))
                            .claims()
                            .getFirst();
            var before = businessVector();
            assertEquals(
                    ConsumptionEngine.Status.ROLLED_BACK,
                    alteredEngine.execute(claim).status(),
                    mode);
            assertEquals(before, businessVector());
            assertPendingWithSlot(request.requestId());
        }
    }

    @Test
    void l3_unavailable_observation() {
        var e = key("unavailable");
        var request = request(new IdentityRequest.Registration(), e, null, e);
        admitRequest(request);
        var handle = claim(request);
        var before = businessVector();
        admin.execute("revoke select on v3_identity_request from " + schema + "_runtime");
        assertEquals(RecoveryObservation.UNAVAILABLE, engine.observe(handle.claim()));
        admin.execute("grant select on v3_identity_request to " + schema + "_runtime");
        assertEquals(before, businessVector());
        engine.abandon(handle);
    }

    @Test
    void l3_final_lock_clock() throws Exception {
        for (boolean expireBefore : List.of(true, false)) {
            var e = key("clock-" + expireBefore);
            var request = request(new IdentityRequest.Registration(), e, null, e);
            admitRequest(request);
            var handle = claim(request);
            if (expireBefore) {
                try (var blocker = adminSource.getConnection()) {
                    blocker.setAutoCommit(false);
                    int blockerPid;
                    try (var q = blocker.createStatement();
                            var row = q.executeQuery("select pg_backend_pid()")) {
                        row.next();
                        blockerPid = row.getInt(1);
                    }
                    try (var q =
                            blocker.prepareStatement(
                                    "update consumption_slot set"
                                            + " claimed_until=clock_timestamp()-interval '1 second'"
                                            + " where consumable_id=?")) {
                        q.setObject(1, request.requestId());
                        q.executeUpdate();
                    }
                    var future = threads.submit(() -> engine.execute(handle));
                    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    boolean waiting = false;
                    while (System.nanoTime() < end) {
                        waiting =
                                admin.queryForObject(
                                        "select exists(select 1 from pg_stat_activity where"
                                                + " ?=any(pg_blocking_pids(pid)))",
                                        Boolean.class,
                                        blockerPid);
                        if (waiting) break;
                        Thread.onSpinWait();
                    }
                    try {
                        assertTrue(waiting, "final slot wait observed");
                    } finally {
                        blocker.commit();
                    }
                    assertEquals(
                            ConsumptionEngine.Status.CLAIM_LOST,
                            future.get(15, TimeUnit.SECONDS).status());
                    assertPendingWithSlot(request.requestId());
                }
            } else {
                // Final lock already owns this attempt: physical time may pass its original
                // deadline.
                hook =
                        boundary -> {
                            if (boundary.equals("after_state")) {
                                jdbc.update(
                                        "update consumption_slot set"
                                            + " claimed_until=clock_timestamp()-interval '1 second'"
                                            + " where consumable_id=?",
                                        request.requestId());
                            }
                        };
                assertEquals(ConsumptionEngine.Status.COMMITTED, engine.execute(handle).status());
                hook = boundary -> {};
            }
        }
    }

    @Test
    void l3_unknown_commit() throws Exception {
        for (var fault :
                List.of(
                        com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                .CommitAcknowledgementProxy.Fault.AFTER_COMMIT,
                        com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                .CommitAcknowledgementProxy.Fault.BEFORE_COMMIT)) {
            var e = key("wire-" + fault);
            var request = request(new IdentityRequest.Registration(), e, null, e);
            admitRequest(request);
            discover(request);
            try (var proxy =
                    new com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                            .CommitAcknowledgementProxy(PG.getHost(), PG.getMappedPort(5432))) {
                var wire =
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                "jdbc:postgresql://127.0.0.1:"
                                        + proxy.localPort()
                                        + "/"
                                        + PG.getDatabaseName()
                                        + "?currentSchema="
                                        + schema
                                        + "&sslmode=disable&preferQueryMode=simple&socketTimeout=15",
                                PG.getUsername(),
                                PG.getPassword());
                var owner = new SpringAttemptTransactions(wire, manager(wire), 25);
                var delegate =
                        new ExecuteIdentityRequest(
                                new JdbcIdentityRequestStore(wire, owner),
                                new JdbcIdentityRequestObservation(wire),
                                new JdbcIdentityAuthority(wire, owner),
                                tokens::generate,
                                UUID::randomUUID);
                ConsumptionParticipant armed =
                        new ConsumptionParticipant() {
                            public Optional<Prepared> prepare(UUID id) {
                                var prepared = delegate.prepare(id).orElseThrow();
                                return Optional.of(
                                        new Prepared() {
                                            public void finalizeIn(SlotCompletion completion) {
                                                proxy.arm(fault);
                                                prepared.finalizeIn(completion);
                                            }

                                            public void requireCompletionReady() {
                                                prepared.requireCompletionReady();
                                            }
                                        });
                            }

                            public RecoveryObservation observe(
                                    UUID id, RecoveryReadContext context) {
                                return delegate.observe(id, context);
                            }
                        };
                var actual =
                        new ConsumptionEngine(
                                definition,
                                owner,
                                new JdbcConsumptionSlotStore(wire),
                                armed,
                                tx,
                                new JdbcConsumptionSlotStore(source),
                                new JdbcPartitionOwnershipStore(source),
                                lifecycle,
                                new WorkerCapacity(1, Map.of(definition.id(), 1)),
                                t -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                                null,
                                ConsumptionObservation.noop());
                int partition =
                        RoutingV1.partition(
                                RoutingV1.hash(
                                        ConsumableFamily.IDENTITY_REQUEST, request.requestId()),
                                4);
                var handle =
                        actual.acquire(lease(partition), List.of(request.requestId()))
                                .claims()
                                .getFirst();
                var before = businessVector();
                var result = actual.execute(handle);
                assertEquals(AttemptTransactions.Resolution.UNKNOWN, result.resolution());
                assertEquals(1, proxy.interceptedCount());
                if (fault
                        == com.kartaguez.pocoma.infra.persistence.primary.jpa.v3
                                .CommitAcknowledgementProxy.Fault.AFTER_COMMIT) {
                    assertEquals(1, proxy.committedReplyCount());
                    assertEquals(RecoveryObservation.COMPLETED, result.observation());
                    var committed = businessVector();
                    assertTrue(
                            tx.<Boolean>read(
                                    c -> participant.prepare(request.requestId()).isEmpty()));
                    assertEquals(committed, businessVector());
                } else {
                    assertEquals(0, proxy.committedReplyCount());
                    assertEquals(RecoveryObservation.NOT_COMPLETED, result.observation());
                    assertEquals(before, businessVector());
                    assertEquals(
                            0,
                            admin.queryForObject(
                                    "select attempt_count from consumption_slot where"
                                            + " consumable_id=?",
                                    Integer.class,
                                    request.requestId()));
                }
                evidence.add(
                        "wire fault="
                                + fault
                                + " resolution="
                                + result.resolution()
                                + " observation="
                                + result.observation()
                                + " intercepted="
                                + proxy.interceptedCount()
                                + " commitReplies="
                                + proxy.committedReplyCount());
            }
        }
    }

    @Test
    void l3_observation_corruption() {
        var e = key("recover");
        var req = request(new IdentityRequest.Registration(), e, null, e);
        admitRequest(req);
        assertEquals(
                RecoveryObservation.NOT_COMPLETED,
                tx.read(c -> observation.observe(req.requestId(), c)));
        execute(req);
        assertEquals(
                RecoveryObservation.COMPLETED,
                tx.read(c -> observation.observe(req.requestId(), c)));
        admin.execute("alter table v3_event_position disable trigger user");
        admin.update("delete from v3_event_position where family='IDENTITY_COMPLETION'");
        admin.execute("alter table v3_event_position enable trigger user");
        var before = businessVector();
        assertEquals(
                RecoveryObservation.INCONSISTENT,
                tx.read(c -> observation.observe(req.requestId(), c)));
        assertEquals(before, businessVector());
        assertThrows(
                org.springframework.transaction.IllegalTransactionStateException.class,
                () -> observation.observe(req.requestId(), () -> {}));
    }
}

abstract class IdentityEngineFixture extends IdentityPostgresFixture {
    final ConsumptionDefinition definition =
            new ConsumptionDefinition(
                    new ConsumptionId(CONSUMPTION),
                    ConsumableFamily.IDENTITY_REQUEST,
                    ConsumptionDefinition.Authority.WRITE);
    final DiscoveryEngine.Settings discoverySettings =
            new DiscoveryEngine.Settings(
                    128, 32, 32, 128, Duration.ofSeconds(5), Duration.ofSeconds(15));
    final LifecycleSettings lifecycle =
            new LifecycleSettings(
                    32,
                    Duration.ofMinutes(2),
                    Duration.ofMinutes(2),
                    Duration.ofSeconds(10),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(15),
                    new LifecycleSettings.RetryPolicy(3, Duration.ZERO));
    JdbcIdentityRequestStore store;
    JdbcIdentityRequestObservation observation;
    ExecuteIdentityRequest participant;
    ConsumptionEngine engine;
    DiscoveryEngine discovery;
    final Map<Integer, PartitionLease> leases = new HashMap<>();
    volatile Consumer<String> hook = b -> {};
    String failAt;

    @BeforeEach
    void initializeEngine() {
        admin.execute(
                "grant select,update on partition_ownership,discovery_checkpoint to "
                        + schema
                        + "_runtime");
        admin.execute("grant insert on discovery_checkpoint to " + schema + "_runtime");
        admin.update(
                "insert into partition_ownership(consumption_id,partition_id) select"
                        + " ?,generate_series(0,3)",
                CONSUMPTION);
        store = new JdbcIdentityRequestStore(source, tx);
        observation = new JdbcIdentityRequestObservation(source);
        IdentityAuthority tracked =
                (IdentityAuthority)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {IdentityAuthority.class},
                                (p, m, args) -> {
                                    if (m.getName().equals("apply")) boundary("before_apply");
                                    var result = invoke(authority, m, args);
                                    return result;
                                });
        IdentityRequestStore trackedStore =
                (IdentityRequestStore)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {IdentityRequestStore.class},
                                (p, m, args) -> {
                                    var result = invoke(store, m, args);

                                    if (m.getName().equals("publish")) boundary("after_positions");
                                    return result;
                                });
        participant =
                new ExecuteIdentityRequest(
                        trackedStore, observation, tracked, tokens::generate, UUID::randomUUID);
        engine = engine(participant, tx);
        discovery =
                new DiscoveryEngine(
                        definition,
                        tx,
                        new JdbcPartitionOwnershipStore(source),
                        new JdbcDiscoveryAdmissionStore(source, discoverySettings),
                        discoverySettings,
                        new JdbcIdentityRequestDiscoveryReferences(source, discoverySettings));
    }

    final List<String> sqlTrace = new CopyOnWriteArrayList<>();

    @Override
    Object trace(java.sql.PreparedStatement statement, String sql, int pid) {
        var delegate = super.trace(statement, sql, pid);
        String text = sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class[] {java.sql.PreparedStatement.class},
                (p, m, args) -> {
                    boolean execute = m.getName().startsWith("execute");
                    if (execute) {
                        sqlTrace.add(pid + " " + text);
                        if (text.startsWith("update v3_event_counter")) {
                            tx.requirePublicationClosed();
                            boundary("before_counter");
                        }
                    }
                    var result = invoke(delegate, m, args);
                    if (execute && text.startsWith("insert into v3_eui_version"))
                        boundary("after_state");
                    if (execute && text.startsWith("select v3_identity_check_unit"))
                        boundary("before_close");
                    if (execute && text.startsWith("insert into v3_event_position"))
                        boundary("after_position");
                    return result;
                });
    }

    void boundary(String b) {
        hook.accept(b);
        if (b.equals(failAt)) throw new IllegalStateException("injected " + b);
    }

    void installFailure(String boundary) {
        failAt = boundary;
        // Database trigger cuts are physical, not a fake Engine or I2 harness.
        String table =
                switch (boundary) {
                    case "after_user" -> "v3_pocoma_user";
                    case "after_state" -> "v3_eui_version";
                    case "after_result" -> "v3_identity_request_result";
                    case "after_terminal" -> "v3_identity_request";
                    case "after_delete" -> "consumption_slot";
                    case "after_first_event" -> "v3_identity_event";
                    case "after_completion" -> "v3_identity_completion_event";
                    default -> null;
                };
        if (table != null) {
            admin.execute(
                    "create or replace function i3_cut() returns trigger language plpgsql as $$"
                            + " begin raise exception 'I3 injected cut';return new;end $$");
            admin.execute(
                    "create trigger i3_cut after "
                            + (table.equals("consumption_slot")
                                    ? "delete"
                                    : table.equals("v3_identity_request") ? "update" : "insert")
                            + " on "
                            + table
                            + " for each row execute function i3_cut()");
        }
    }

    void clearFailure() {
        failAt = null;
        for (String t :
                List.of(
                        "consumption_slot",
                        "v3_identity_event",
                        "v3_identity_completion_event",
                        "v3_pocoma_user",
                        "v3_eui_version",
                        "v3_identity_request_result",
                        "v3_identity_request"))
            admin.execute("drop trigger if exists i3_cut on " + t);
    }

    static org.springframework.jdbc.datasource.DataSourceTransactionManager manager(
            javax.sql.DataSource source) {
        var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        return manager;
    }

    ConsumptionEngine engine(ConsumptionParticipant p, AttemptTransactions owner) {
        return new ConsumptionEngine(
                definition,
                owner,
                new JdbcConsumptionSlotStore(source),
                p,
                new JdbcPartitionOwnershipStore(source),
                lifecycle,
                new WorkerCapacity(4, Map.of(definition.id(), 4)),
                t -> ConsumptionObservationFailure.classify(t));
    }

    IdentityRequest request(
            IdentityRequest.Operation op,
            ExternalIdentity requester,
            BindingToken token,
            ExternalIdentity target) {
        UUID id = UUID.randomUUID();
        return new IdentityRequest(
                id,
                requester,
                op,
                token,
                Instant.parse("2026-10-30T00:00:00Z"),
                new IdentityRequest.AdmissionContext(
                        "L3 engine test",
                        "1",
                        requester,
                        target,
                        id.toString(),
                        id.toString(),
                        Instant.EPOCH,
                        Map.of("scope", "internal"),
                        IdentityRequest.Origin.CONTROLLED_INTERNAL));
    }

    void admitRequest(IdentityRequest request) {
        tx.write(() -> store.insertPending(request));
    }

    PartitionLease lease(int partition) {
        return leases.computeIfAbsent(partition, p -> engine.acquirePartition(p).orElseThrow());
    }

    void discover(IdentityRequest request) {
        int partition =
                RoutingV1.partition(
                        RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, request.requestId()), 4);
        discovery.discoverRequests(lease(partition), DiscoveryEngine.RequestWalk.fresh());
    }

    ConsumptionEngine.AcquiredClaim claim(IdentityRequest request) {
        discover(request);
        int partition =
                RoutingV1.partition(
                        RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, request.requestId()), 4);
        return engine.acquire(lease(partition), List.of(request.requestId())).claims().getFirst();
    }

    ConsumptionEngine.Attempt execute(IdentityRequest request) {
        var result = engine.execute(claim(request));
        evidence.add(
                "real Engine="
                        + request.requestId()
                        + " status="
                        + result.status()
                        + " resolution="
                        + result.resolution()
                        + " observation="
                        + result.observation());
        return result;
    }

    void executeAdmitted(IdentityRequest request) {
        admitRequest(request);
        var result = execute(request);
        assertEquals(
                ConsumptionEngine.Status.COMMITTED,
                result.status(),
                () -> String.valueOf(result.cause()));
    }

    Map<String, Object> businessVector() {
        var v = new LinkedHashMap<>(vector());
        v.remove("consumption_slot");
        return v;
    }

    @AfterEach
    void collectI3(TestInfo info) throws Exception {
        var path =
                java.nio.file.Path.of(
                        "target/l3-i3-evidence",
                        getClass().getSimpleName()
                                + "-"
                                + info.getTestMethod().orElseThrow().getName()
                                + ".txt");
        java.nio.file.Files.createDirectories(path.getParent());
        var output = new ArrayList<>(evidence);
        output.addAll(sqlTrace);
        output.add("durable=" + businessVector());
        java.nio.file.Files.write(path, output);
    }

    static final class ConsumptionObservationFailure {
        static AttemptFailureClassifier.FailureKind classify(Throwable cause) {
            return AttemptFailureClassifier.FailureKind.TECHNICAL;
        }
    }
}
