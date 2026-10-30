package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

@Testcontainers
class IdentityConcurrencyPostgresTest extends IdentityPostgresFixture {
    @Test
    void l3_registration_race() throws Exception {
        for (boolean known : List.of(false, true)) {
            var e = key("registration-" + known);
            if (known) {
                var first = changed(run(admit(registration(e)))).identity();
                run(admit(unlink(e, first.bindingToken(), e)));
            }
            long before = count("v3_pocoma_user");
            var results = race(admit(registration(e)), admit(registration(e)));
            assertInstanceOf(IdentityTransitionResult.Changed.class, results.getFirst());
            assertRejected(
                    results.getLast(), IdentityTransitionResult.Rejection.TARGET_ALREADY_BOUND);
            assertEquals(before + 1, count("v3_pocoma_user"));
            assertEquals(known ? 3 : 1, current(e).version());
        }
    }

    @Test
    void l3_link_races() throws Exception {
        var e1 = key("a");
        var e2 = key("b");
        var a = changed(run(admit(registration(e1)))).identity();
        var b = changed(run(admit(registration(e2)))).identity();
        for (boolean known : List.of(false, true))
            for (boolean registrationWins : List.of(false, true)) {
                var target = key("target-" + known + "-" + registrationWins);
                if (known) {
                    var seed = changed(run(admit(registration(target)))).identity();
                    run(admit(unlink(target, seed.bindingToken(), target)));
                }
                var reg = admit(registration(target));
                var link = admit(link(e1, a.bindingToken(), target));
                long before = count("v3_pocoma_user");
                var results = race(registrationWins ? reg : link, registrationWins ? link : reg);
                changed(results.getFirst());
                assertRejected(
                        results.getLast(), IdentityTransitionResult.Rejection.TARGET_ALREADY_BOUND);
                assertEquals(before + (registrationWins ? 1 : 0), count("v3_pocoma_user"));
            }
        var target = key("two-links");
        var results =
                race(
                        admit(link(e1, a.bindingToken(), target)),
                        admit(link(e2, b.bindingToken(), target)));
        assertEquals(a.userId(), changed(results.getFirst()).identity().userId());
        assertRejected(results.getLast(), IdentityTransitionResult.Rejection.TARGET_ALREADY_BOUND);
    }

    @Test
    void l3_cross_unlinks() throws Exception {
        var e1 = key("a");
        var e2 = key("b");
        var a = changed(run(admit(registration(e1)))).identity();
        var b = changed(run(admit(link(e1, a.bindingToken(), e2)))).identity();
        var result =
                race(
                        admit(unlink(e1, a.bindingToken(), e2)),
                        admit(unlink(e2, b.bindingToken(), e1)));
        changed(result.getFirst());
        assertRejected(result.getLast(), IdentityTransitionResult.Rejection.INVALID_BINDING);
        assertTrue(current(e1).isBound());
        assertFalse(current(e2).isBound());
        assertCanonicalCalls();
    }

    @Test
    void l3_link_vs_initiator_unlink() throws Exception {
        for (boolean unlinkWins : List.of(false, true)) {
            var initiator = key("initiator-" + unlinkWins);
            var target = key("target-" + unlinkWins);
            var a = changed(run(admit(registration(initiator)))).identity();
            var link = admit(link(initiator, a.bindingToken(), target));
            var unlink = admit(unlink(initiator, a.bindingToken(), initiator));
            var result = race(unlinkWins ? unlink : link, unlinkWins ? link : unlink);
            changed(result.getFirst());
            if (unlinkWins) {
                assertRejected(
                        result.getLast(), IdentityTransitionResult.Rejection.INVALID_BINDING);
                assertEquals(
                        0,
                        admin.queryForObject(
                                "select count(*) from v3_eui_version v join v3_eui_authority a"
                                    + " using(eui_id) where a.subject=?",
                                Integer.class,
                                target.subject()));
                assertEquals(
                        0,
                        admin.queryForObject(
                                "select current_version from v3_eui_authority where subject=?",
                                Integer.class,
                                target.subject()));
            } else changed(result.getLast());
            assertFalse(current(initiator).isBound());
        }
    }

    @Test
    void l3_anchor_id_race() throws Exception {
        for (boolean rollback : List.of(false, true))
            for (boolean preexisting : List.of(false, true)) {
                var e = key("anchor-" + rollback + "-" + preexisting);
                UUID prior = UUID.randomUUID();
                if (preexisting)
                    jdbc.update(
                            "insert into v3_eui_authority(eui_id,issuer,subject) values (?,?,?)",
                            prior,
                            e.issuer(),
                            e.subject());
                var held = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var firstPid = new AtomicInteger();
                UUID proposed = UUID.randomUUID();
                var first =
                        threads.submit(
                                () -> {
                                    try (var c = source.getConnection()) {
                                        c.setAutoCommit(false);
                                        firstPid.set(
                                                c.unwrap(org.postgresql.PGConnection.class)
                                                        .getBackendPID());
                                        try (var s =
                                                c.prepareStatement(
                                                        "insert into"
                                                            + " v3_eui_authority(eui_id,issuer,subject)"
                                                            + " values (?,?,?) on conflict"
                                                            + " (issuer,subject) do nothing")) {
                                            s.setObject(1, proposed);
                                            s.setString(2, e.issuer());
                                            s.setString(3, e.subject());
                                            s.executeUpdate();
                                        }
                                        try (var s =
                                                c.prepareStatement(
                                                        "select eui_id from v3_eui_authority where"
                                                            + " issuer=? and subject=? for"
                                                            + " update")) {
                                            s.setString(1, e.issuer());
                                            s.setString(2, e.subject());
                                            s.executeQuery().close();
                                        }
                                        held.countDown();
                                        await(release);
                                        if (rollback) c.rollback();
                                        else c.commit();
                                        return null;
                                    }
                                });
                await(held);
                int start = lockCalls.size();
                var second =
                        threads.submit(
                                () ->
                                        tx.write(
                                                () ->
                                                        authority
                                                                .lockInCanonicalOrder(List.of(e))
                                                                .get(e)));
                try {
                    int waiter = waitForPid(start, firstPid.get());
                    blocked(waiter, firstPid.get());
                } finally {
                    release.countDown();
                }
                first.get(15, TimeUnit.SECONDS);
                var actual = second.get(15, TimeUnit.SECONDS);
                assertEquals(
                        preexisting ? prior : rollback ? actual.value() : proposed, actual.value());
                if (rollback && !preexisting) assertFalse(proposed.equals(actual.value()));
                var state = changed(run(admit(registration(e)))).identity();
                assertEquals(actual, state.id());
                assertEquals(1, state.version());
            }
    }

    @Test
    void l3_canonical_lock_paths() throws Exception {
        for (int known = 0; known <= 2; known++) {
            var a = key("path-" + known + "-a");
            var b = key("path-" + known + "-b");
            for (var e :
                    known == 0
                            ? List.<ExternalIdentity>of()
                            : known == 1 ? List.of(a) : List.of(a, b))
                tx.write(() -> authority.lockInCanonicalOrder(List.of(e)));
            var held = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var pid = new AtomicInteger();
            var first =
                    threads.submit(
                            () ->
                                    tx.write(
                                            () -> {
                                                pid.set(
                                                        jdbc.queryForObject(
                                                                "select pg_backend_pid()",
                                                                Integer.class));
                                                authority.lockInCanonicalOrder(List.of(b, a, b));
                                                held.countDown();
                                                await(release);
                                                return null;
                                            }));
            await(held);
            int start = lockCalls.size();
            var second =
                    threads.submit(
                            () -> tx.write(() -> authority.lockInCanonicalOrder(List.of(a, b))));
            try {
                blocked(waitForPid(start, pid.get()), pid.get());
            } finally {
                release.countDown();
            }
            first.get(15, TimeUnit.SECONDS);
            assertEquals(2, second.get(15, TimeUnit.SECONDS).size());
        }
        tx.write(
                () -> {
                    assertEquals(
                            1,
                            authority
                                    .lockInCanonicalOrder(List.of(key("same"), key("same")))
                                    .size());
                    return null;
                });
        assertCanonicalCalls();
    }

    @Test
    void l3_canonical_unicode() {
        var expected =
                List.of(
                        new ExternalIdentity("a", "x"),
                        new ExternalIdentity("a", "xx"),
                        new ExternalIdentity("a", "\u007f"),
                        new ExternalIdentity("a", "\u0080"),
                        new ExternalIdentity("a", "e\u0301"),
                        new ExternalIdentity("a", "é"),
                        new ExternalIdentity("a", "\ue000"),
                        new ExternalIdentity("a", "\ud800\udc00"),
                        new ExternalIdentity("b", "x"));
        // Independent expected sequence: UTF8 byte order puts ASCII 'e' before DEL and U+0080.
        var order =
                List.of(
                        expected.get(4),
                        expected.get(0),
                        expected.get(1),
                        expected.get(2),
                        expected.get(3),
                        expected.get(5),
                        expected.get(6),
                        expected.get(7),
                        expected.get(8));
        for (int i = 0; i < order.size(); i++)
            for (int j = 0; j < order.size(); j++) {
                assertEquals(
                        Integer.signum(i - j),
                        Integer.signum(
                                ExternalUserIdentity.compareKeys(order.get(i), order.get(j))));
                var input = new ArrayList<>(order);
                Collections.rotate(input, i);
                Collections.reverse(input);
                input.add(order.get(j));
                tx.write(
                        () -> {
                            assertEquals(
                                    order,
                                    new ArrayList<>(
                                            authority.lockInCanonicalOrder(input).keySet()));
                            return null;
                        });
            }
        var sql =
                admin.query(
                        "select issuer,subject from v3_eui_authority order by issuer collate"
                            + " \"C\",subject collate \"C\"",
                        (rs, n) -> new ExternalIdentity(rs.getString(1), rs.getString(2)));
        assertEquals(order, sql);
        assertCanonicalCalls();
    }

    @Test
    void l3_disjoint_progress() throws Exception {
        var a = admit(registration(key("a")));
        var b = admit(registration(key("b")));
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first =
                threads.submit(
                        () ->
                                run(
                                        a,
                                        s -> {
                                            if (s.equals("after_lock")) {
                                                held.countDown();
                                                await(release);
                                            }
                                        }));
        await(held);
        try {
            changed(threads.submit(() -> run(b)).get(10, TimeUnit.SECONDS));
            assertEquals(1, count("v3_pocoma_user"));
        } finally {
            release.countDown();
        }
        changed(first.get(15, TimeUnit.SECONDS));
        assertCounts(2, 2, 2, 4, 2);
    }

    List<IdentityTransitionResult> race(Work first, Work second) throws Exception {
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pid = new AtomicInteger();
        var a =
                threads.submit(
                        () ->
                                run(
                                        first,
                                        s -> {
                                            if (s.equals("after_lock")) {
                                                pid.set(
                                                        jdbc.queryForObject(
                                                                "select pg_backend_pid()",
                                                                Integer.class));
                                                held.countDown();
                                                await(release);
                                            }
                                        }));
        await(held);
        int start = lockCalls.size();
        var b = threads.submit(() -> run(second));
        try {
            blocked(waitForPid(start, pid.get()), pid.get());
        } finally {
            release.countDown();
        }
        return List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS));
    }

    int waitForPid(int start, int except) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var snapshot = List.copyOf(lockCalls);
            for (int i = Math.min(start, snapshot.size()); i < snapshot.size(); i++)
                if (snapshot.get(i).pid() != except) return snapshot.get(i).pid();
            Thread.onSpinWait();
        }
        throw new AssertionError("Contender never entered EUI acquisition");
    }

    static void assertRejected(
            IdentityTransitionResult r, IdentityTransitionResult.Rejection expected) {
        assertInstanceOf(IdentityTransitionResult.Rejected.class, r);
        assertEquals(expected, ((IdentityTransitionResult.Rejected) r).reason());
    }

    void assertCanonicalCalls() {
        var groups = new LinkedHashMap<Integer, List<LockCall>>();
        for (var call : lockCalls)
            groups.computeIfAbsent(call.pid(), p -> new ArrayList<>()).add(call);
        for (var calls : groups.values()) {
            assertEquals(0, calls.size() % 2);
            ExternalIdentity previous = null;
            for (int i = 0; i < calls.size(); i += 2) {
                assertEquals("INSERT", calls.get(i).operation());
                assertEquals("FOR UPDATE", calls.get(i + 1).operation());
                assertEquals(calls.get(i).key(), calls.get(i + 1).key());
                if (previous != null)
                    assertTrue(ExternalUserIdentity.compareKeys(previous, calls.get(i).key()) < 0);
                previous = calls.get(i).key();
            }
        }
    }
}
