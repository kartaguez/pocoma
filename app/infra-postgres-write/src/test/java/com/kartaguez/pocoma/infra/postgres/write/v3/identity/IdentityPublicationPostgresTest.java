package com.kartaguez.pocoma.infra.postgres.write.v3.identity;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.identity.v3.*;
import com.kartaguez.pocoma.engine.write.identity.v3.*;
import com.kartaguez.pocoma.infra.postgres.write.v3.consumption.*;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;

@Testcontainers
class IdentityPublicationPostgresTest extends IdentityEngineFixture {
    @Test
    void l73_concurrentIdentityBatchesPreserveSafePrefixAcrossResolution() throws Exception {
        for (int repetition = 0; repetition < 2; repetition++) {
            for (String resolution : List.of("commit", "rollback", "disconnect")) {
                var a = key("l73-a-" + repetition + resolution);
                var b = key("l73-b-" + repetition + resolution);
                var oneRequest = request(new IdentityRequest.Registration(), a, null, a);
                var twoRequest = request(new IdentityRequest.Registration(), b, null, b);
                admitRequest(oneRequest);
                admitRequest(twoRequest);
                discover(oneRequest);
                discover(twoRequest);
                var firstEngine = engine(participant, tx);
                var secondEngine = engine(participant, tx);
                int p1 = RoutingV1.partition(RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, oneRequest.requestId()), 4);
                int p2 = RoutingV1.partition(RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, twoRequest.requestId()), 4);
                var c1 = firstEngine.acquire(lease(p1), List.of(oneRequest.requestId())).claims().getFirst();
                var c2 = secondEngine.acquire(lease(p2), List.of(twoRequest.requestId())).claims().getFirst();
                long before = admin.queryForObject("select position from v3_event_counter where family='IDENTITY'", Long.class);
                var labels = new ConcurrentHashMap<Thread, String>();
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var once = new java.util.concurrent.atomic.AtomicBoolean();
                var firstPid = new java.util.concurrent.atomic.AtomicInteger();
                var secondPid = new java.util.concurrent.atomic.AtomicInteger();
                hook = boundary -> {
                    String label = labels.get(Thread.currentThread());
                    if ("A".equals(label) && boundary.equals("after_position") && once.compareAndSet(false, true)) {
                        firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                        entered.countDown();
                        await(release);
                        if (resolution.equals("rollback")) throw new IllegalStateException("L7.3 rollback after first Identity position");
                    }
                    if ("B".equals(label) && boundary.equals("before_counter"))
                        secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                };
                var first = threads.submit(() -> { labels.put(Thread.currentThread(), "A"); return firstEngine.execute(c1); });
                await(entered);
                var second = threads.submit(() -> { labels.put(Thread.currentThread(), "B"); return secondEngine.execute(c2); });
                try {
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                    boolean blocked = false;
                    while (System.nanoTime() < deadline) {
                        if (secondPid.get() != 0 && admin.queryForObject("select ?=any(pg_blocking_pids(?))",
                                Boolean.class, firstPid.get(), secondPid.get())) { blocked = true; break; }
                        Thread.onSpinWait();
                    }
                    assertTrue(blocked, "T2 must physically wait for T1's publication counter");
                    var references = new com.kartaguez.pocoma.infra.postgres.source.v3.JdbcEventDiscoveryReferences(
                            source, ConsumableFamily.IDENTITY_EVENT, discoverySettings);
                    var window = tx.read(context -> references.window(before, 32, context));
                    assertEquals(before, window.highInclusive(), "Discovery must not cover the uncommitted half-batch");
                    assertTrue(window.references().isEmpty());
                    assertEquals(before, admin.queryForObject("select position from v3_event_counter where family='IDENTITY'", Long.class));
                    assertEquals(0, admin.queryForObject("select count(*) from v3_identity_request_result where request_id in (?,?)",
                            Integer.class, oneRequest.requestId(), twoRequest.requestId()));
                    evidence.add("L7.3 publication repetition=" + repetition + " resolution=" + resolution
                            + " firstPid=" + firstPid.get() + " secondPid=" + secondPid.get() + " safePrefix=" + before);
                    if (resolution.equals("disconnect"))
                        assertTrue(admin.queryForObject("select pg_terminate_backend(?)", Boolean.class, firstPid.get()));
                } finally {
                    release.countDown();
                }
                var firstResult = first.get(15, TimeUnit.SECONDS);
                assertEquals(ConsumptionProtocol.Status.COMMITTED, second.get(15, TimeUnit.SECONDS).status());
                hook = boundary -> {};
                boolean committed = resolution.equals("commit");
                if (committed) assertEquals(ConsumptionProtocol.Status.COMMITTED, firstResult.status());
                else assertNotEquals(ConsumptionProtocol.Status.COMMITTED, firstResult.status());
                var publications = admin.queryForList("select e.request_id from v3_event_position p join v3_identity_event e"
                        + " using(event_id) where p.family='IDENTITY' and p.position>? order by p.position", UUID.class, before);
                assertEquals(committed ? List.of(oneRequest.requestId(), oneRequest.requestId(), twoRequest.requestId(), twoRequest.requestId())
                        : List.of(twoRequest.requestId(), twoRequest.requestId()), publications);
                var positions = admin.queryForList("select position from v3_event_position where family='IDENTITY' order by position", Long.class);
                for (int n = 0; n < positions.size(); n++) assertEquals(n + 1L, positions.get(n));
                assertEquals(committed ? 1 : 0, admin.queryForObject("select count(*) from v3_identity_request_result where request_id=?",
                        Integer.class, oneRequest.requestId()));
                assertEquals(committed ? "TERMINAL" : "PENDING", admin.queryForObject(
                        "select status from v3_identity_request where request_id=?", String.class, oneRequest.requestId()));
                evidence.add("L7.3 resolved " + resolution + " firstOutcome=" + firstResult.resolution()
                        + " newIdentityEvents=" + publications.size() + " denseThrough=" + positions.size());
            }
        }
    }

    @Test
    void l3_identity_batch() {
        for (int i = 0; i < 3; i++) {
            var e = key("batch-" + i);
            executeAdmitted(request(new IdentityRequest.Registration(), e, null, e));
        }
        var types =
                admin.queryForList(
                        "select e.subject_type from v3_event_position p join v3_identity_event e"
                                + " using(event_id) where family='IDENTITY' order by position",
                        String.class);
        assertEquals(
                List.of(
                        "POCOMA_USER",
                        "EXTERNAL_USER_IDENTITY",
                        "POCOMA_USER",
                        "EXTERNAL_USER_IDENTITY",
                        "POCOMA_USER",
                        "EXTERNAL_USER_IDENTITY"),
                types);
        assertEquals(
                List.of(1L, 2L, 3L, 4L, 5L, 6L),
                admin.queryForList(
                        "select position from v3_event_position where family='IDENTITY' order by"
                                + " position",
                        Long.class));
        assertEquals(
                List.of(1L, 2L, 3L),
                admin.queryForList(
                        "select position from v3_event_position where family='IDENTITY_COMPLETION'"
                                + " order by position",
                        Long.class));
        assertTrue(sqlTrace.stream().noneMatch(s -> s.contains("nextval")));
    }

    @Test
    void l3_dense_rollback() {
        var e = key("dense");
        var req = request(new IdentityRequest.Registration(), e, null, e);
        admitRequest(req);
        installFailure("after_positions");
        var before = businessVector();
        assertEquals(ConsumptionProtocol.Status.ROLLED_BACK, execute(req).status());
        clearFailure();
        assertEquals(before, businessVector());
        execute(req);
        assertEquals(
                List.of(1L, 2L),
                admin.queryForList(
                        "select position from v3_event_position where family='IDENTITY' order by"
                                + " position",
                        Long.class));
    }

    @Test
    void historicalBindingFixtureDoesNotPublishAcrossBlockedForeignKey() throws Exception {
        var e = key("initiator");
        executeAdmitted(request(new IdentityRequest.Registration(), e, null, e));
        var actor = current(e);
        var target = key("target");
        var req = request(new IdentityRequest.Link(target), e, actor.bindingToken(), target);
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
                            "select user_id from v3_pocoma_user where user_id=? for update")) {
                q.setObject(1, actor.userId().value());
                q.executeQuery().close();
            }
            long counter =
                    admin.queryForObject(
                            "select position from v3_event_counter where family='IDENTITY'",
                            Long.class);
            var future = threads.submit(() -> completeHistoricalLink(req));
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Integer waiter = null;
            while (System.nanoTime() < end) {
                var pids =
                        admin.queryForList(
                                "select pid from pg_stat_activity where"
                                        + " ?=any(pg_blocking_pids(pid))",
                                Integer.class,
                                blockerPid);
                if (!pids.isEmpty()) {
                    waiter = pids.getFirst();
                    break;
                }
                Thread.onSpinWait();
            }
            try {
                assertNotNull(waiter, "immediate FK blocking edge");
                assertEquals(
                        counter,
                        admin.queryForObject(
                                "select position from v3_event_counter where family='IDENTITY'",
                                Long.class));
                evidence.add("FK prefix blocker=" + blockerPid + " waiter=" + waiter);
            } finally {
                blocker.rollback();
            }
            future.get(20, TimeUnit.SECONDS);
            assertEquals(counter + 1, admin.queryForObject(
                    "select position from v3_event_counter where family='IDENTITY'", Long.class));
        }
    }

    @Test
    void l3_deferred_commit_checks() {
        for (String fault : List.of("omit", "wrong-family")) {
            var e = key(fault);
            var req = request(new IdentityRequest.Registration(), e, null, e);
            admitRequest(req);
            var before = businessVector();
            admin.execute(
                    "create or replace function i3_bad_position() returns trigger language plpgsql"
                            + " as $$ begin "
                            + (fault.equals("omit")
                                    ? "return null;"
                                    : "new.family:='POT';return new;")
                            + " end $$");
            admin.execute(
                    "create trigger i3_bad_position before insert on v3_event_position for each row"
                            + " execute function i3_bad_position()");
            try {
                assertEquals(ConsumptionProtocol.Status.ROLLED_BACK, execute(req).status());
                assertEquals(before, businessVector());
                assertPendingWithSlot(req.requestId());
            } finally {
                admin.execute("drop trigger i3_bad_position on v3_event_position");
            }
        }
    }

    @Test
    void l3_identity_pair_contention() throws Exception {
        for (String family : List.of("IDENTITY", "IDENTITY_COMPLETION")) {
            var a = key(family + "-a");
            var b = key(family + "-b");
            if (family.equals("IDENTITY_COMPLETION")) {
                executeAdmitted(request(new IdentityRequest.Registration(), a, null, a));
                executeAdmitted(request(new IdentityRequest.Registration(), b, null, b));
            }
            var r1 = request(new IdentityRequest.Registration(), a, null, a);
            var r2 = request(new IdentityRequest.Registration(), b, null, b);
            admitRequest(r1);
            admitRequest(r2);
            var c1 = claim(r1);
            discover(r2);
            var other =
                    engine(
                            new ExecuteIdentityRequest(
                                    store,
                                    observation,
                                    new JdbcIdentityAuthority(source, tx),
                                    tokens::generate,
                                    UUID::randomUUID),
                            tx);
            int partition =
                    RoutingV1.partition(
                            RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, r2.requestId()), 4);
            var c2 = other.acquire(lease(partition), List.of(r2.requestId())).claims().getFirst();
            var labels = new ConcurrentHashMap<Thread, String>();
            var first = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            var firstPid = new java.util.concurrent.atomic.AtomicInteger();
            var secondPid = new java.util.concurrent.atomic.AtomicInteger();
            hook =
                    boundary -> {
                        String label = labels.get(Thread.currentThread());
                        if ("A".equals(label)
                                && boundary.equals("after_position")
                                && once.compareAndSet(false, true)) {
                            firstPid.set(
                                    jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                            first.countDown();
                            await(release);
                        }
                        if ("B".equals(label) && boundary.equals("before_counter"))
                            secondPid.set(
                                    jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                    };
            var one =
                    threads.submit(
                            () -> {
                                labels.put(Thread.currentThread(), "A");
                                return engine.execute(c1);
                            });
            await(first);
            var two =
                    threads.submit(
                            () -> {
                                labels.put(Thread.currentThread(), "B");
                                return other.execute(c2);
                            });
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean waiting = false;
            while (System.nanoTime() < end) {
                if (secondPid.get() != 0)
                    waiting =
                            admin.queryForObject(
                                    "select ?=any(pg_blocking_pids(?))",
                                    Boolean.class,
                                    firstPid.get(),
                                    secondPid.get());
                if (waiting) break;
                Thread.onSpinWait();
            }
            try {
                assertTrue(waiting, "dense family counter wait " + family);
                evidence.add(
                        "counter family="
                                + family
                                + " blocker="
                                + firstPid.get()
                                + " waiter="
                                + secondPid.get());
                for (var key : List.of(a, b))
                    try (var probe = adminSource.getConnection()) {
                        probe.setAutoCommit(false);
                        try (var q =
                                probe.prepareStatement(
                                        "select eui_id from v3_eui_authority where issuer=? and"
                                                + " subject=? for update nowait")) {
                            q.setString(1, key.issuer());
                            q.setString(2, key.subject());
                            // A new anchor is invisible until commit; existing anchors in the
                            // rejection pair must stay locked.
                            if (family.equals("IDENTITY_COMPLETION"))
                                assertEquals(
                                        "55P03",
                                        assertThrows(java.sql.SQLException.class, q::executeQuery)
                                                .getSQLState());
                        } finally {
                            probe.rollback();
                        }
                    }
            } finally {
                release.countDown();
            }
            assertEquals(
                    ConsumptionProtocol.Status.COMMITTED, one.get(15, TimeUnit.SECONDS).status());
            assertEquals(
                    ConsumptionProtocol.Status.COMMITTED, two.get(15, TimeUnit.SECONDS).status());
            hook = boundary -> {};
            var positions =
                    admin.queryForList(
                            "select position from v3_event_position where family=? order by"
                                    + " position",
                            Long.class,
                            family);
            for (int i = 0; i < positions.size(); i++) assertEquals(i + 1L, positions.get(i));
        }
    }

    @Test
    void l3_structural_poison_before_counter() {
        var e = key("structural");
        var request = request(new IdentityRequest.Registration(), e, null, e);
        admitRequest(request);
        var before = businessVector();
        // Contradictory unit through the real participant: a swallowed writer failure still
        // poisons.
        var bad =
                (com.kartaguez.pocoma.engine.write.identity.v3.IdentityRequestStore)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class[] {
                                    com.kartaguez.pocoma.engine.write.identity.v3
                                            .IdentityRequestStore.class
                                },
                                (p, m, args) -> {
                                    if (m.getName().equals("publish")) {
                                        jdbc.update(
                                                "update v3_eui_authority set"
                                                    + " current_version=current_version+1 where"
                                                    + " issuer=? and subject=?",
                                                e.issuer(),
                                                e.subject());
                                        assertThrows(
                                                RuntimeException.class,
                                                () -> invokeRuntime(store, m, args));
                                        return null;
                                    }
                                    return invokeRuntime(store, m, args);
                                });
        var participant =
                new ExecuteIdentityRequest(
                        bad,
                        observation,
                        new JdbcIdentityAuthority(source, tx),
                        tokens::generate,
                        UUID::randomUUID);
        var worker = engine(participant, tx);
        discover(request);
        int partition =
                RoutingV1.partition(
                        RoutingV1.hash(ConsumableFamily.IDENTITY_REQUEST, request.requestId()), 4);
        var handle =
                worker.acquire(lease(partition), List.of(request.requestId())).claims().getFirst();
        assertEquals(ConsumptionProtocol.Status.ROLLED_BACK, worker.execute(handle).status());
        hook = b -> {};
        assertEquals(before, businessVector());
        assertTrue(sqlTrace.stream().noneMatch(x -> x.contains("update v3_event_counter")));
    }

    static Object invokeRuntime(Object target, java.lang.reflect.Method method, Object[] args) {
        try {
            return invoke(target, method, args);
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable error) {
            throw new RuntimeException(error);
        }
    }

    @Test
    void l3_suffix_closed() {
        var e = key("suffix");
        var req = request(new IdentityRequest.Registration(), e, null, e);
        admitRequest(req);
        var before = businessVector();
        hook =
                b -> {
                    if (b.equals("before_counter")) {
                        assertThrows(
                                RuntimeException.class,
                                () -> authority.lockInCanonicalOrder(List.of(key("late"))));
                    }
                };
        assertEquals(ConsumptionProtocol.Status.ROLLED_BACK, execute(req).status());
        hook = b -> {};
        assertEquals(before, businessVector());
    }

    @Test
    void l3_physical_unit() {
        var e = key("unit");
        var req = request(new IdentityRequest.Registration(), e, null, e);
        admitRequest(req);
        sqlTrace.clear();
        execute(req);
        var writes =
                sqlTrace.stream()
                        .filter(
                                s ->
                                        s.contains("insert into v3_pocoma_user")
                                                || s.contains("insert into v3_eui_version")
                                                || s.contains("delete from consumption_slot")
                                                || s.contains("insert into v3_identity_event")
                                                || s.contains("update v3_event_counter")
                                                || s.contains("insert into v3_event_position"))
                        .toList();
        assertEquals(
                1, writes.stream().map(s -> s.substring(0, s.indexOf(' '))).distinct().count());
        assertEquals(
                1,
                admin.queryForObject(
                        "select count(distinct writer_xid) from (select writer_xid from"
                            + " v3_pocoma_user union all select writer_xid from v3_eui_version) x",
                        Integer.class));
        int suffix = -1;
        for (int i = 0; i < sqlTrace.size(); i++)
            if (sqlTrace.get(i).contains("update v3_event_counter")) {
                suffix = i;
                break;
            }
        assertTrue(suffix > 0);
        assertTrue(
                sqlTrace.subList(suffix, sqlTrace.size()).stream()
                        .allMatch(
                                s ->
                                        s.contains("update v3_event_counter")
                                                || s.contains("insert into v3_event_position")));
    }
}
