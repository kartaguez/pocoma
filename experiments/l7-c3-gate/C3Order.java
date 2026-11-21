package com.kartaguez.pocoma.runtime.task;

import static com.kartaguez.pocoma.compose.polling.materialize.ReadParticipants.Name.COMMAND_RESULT;
import static com.kartaguez.pocoma.runtime.task.SubobjectPostgresFixture.await;
import static com.kartaguez.pocoma.runtime.task.SubobjectPostgresFixture.get;
import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol.Status;
import com.kartaguez.pocoma.domain.consumption.RoutingV1;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

final class C3Order {
    private final PostgreSQLContainer pg;
    private final Path output;

    C3Order(PostgreSQLContainer pg, Path output) {
        this.pg = pg;
        this.output = output;
    }

    void run() throws Exception {
        try (var evidence = new PrintWriter(Files.newBufferedWriter(output.resolve("order.txt")), true)) {
            interleave(false, evidence);
            interleave(true, evidence);
            completeWithLiveClaimAfterRevocation(evidence);
            recoverAfterServerCrash(evidence);
            evidence.println("PASS commit ordering, backend termination, partition revocation and resumed prefix");
        }
    }

    private void interleave(boolean terminate, PrintWriter evidence) throws Exception {
        try (var f = new ReadParticipantsFixture(pg, "c3-page-" + terminate)) {
            C3Complement.enablePilot(f);
            var first = f.f.publish();
            var second = f.f.publish();
            f.enroll();
            var db = f.f.read;
            var engine = f.engines.get(COMMAND_RESULT);
            f.execute(COMMAND_RESULT, f.pot.completion());
            var prefix = page(db.oracle, 0, 1);
            assertEquals(List.of(1L), prefix.positions());
            assertEquals(1L, prefix.head());
            evidence.println("terminate=" + terminate + " prefix=" + prefix);

            var holdsFirstEvent = new CountDownLatch(1);
            var holdsSecondEvent = new CountDownLatch(1);
            var releaseFirst = new CountDownLatch(1);
            var releaseSecond = new CountDownLatch(1);
            var inserts = new AtomicInteger();
            var claimOne = f.acquire(COMMAND_RESULT, engine, first.completion());
            var claimTwo = f.acquire(COMMAND_RESULT, engine, second.completion());

            db.source.after = sql -> {
                if (!sql.contains("insert into read_availability_event")) return;
                if (inserts.incrementAndGet() == 1) {
                    holdsFirstEvent.countDown();
                    await(releaseFirst);
                } else {
                    holdsSecondEvent.countDown();
                    await(releaseSecond);
                }
            };
            var a = db.threads.submit(() -> engine.execute(claimOne));
            try {
                await(holdsFirstEvent);
                var b = db.threads.submit(() -> engine.execute(claimTwo));
                int blocker = waitForCounter(db.runtimeRole, evidence);
                assertEquals(new Page(1L, List.of(), List.of()), page(db.oracle, 1, 1));
                assertEquals(2, db.oracle.queryForObject("""
                        select count(*) from consumption_slot where consumable_id in (?,?)
                        """, Integer.class, first.completion(), second.completion()));
                evidence.println("while first event is uncommitted: head=1; after cursor 1=[]; both slots visible");
                if (terminate) {
                    assertTrue(C3Complement.admin(pg).queryForObject(
                            "select pg_terminate_backend(?, 5000)", Boolean.class, blocker));
                }
                releaseFirst.countDown();
                var firstAttempt = get(a);
                if (terminate) {
                    assertNotEquals(Status.COMMITTED, firstAttempt.status(), firstAttempt::toString);
                    assertEquals(1, f.slots(COMMAND_RESULT, first.completion()));
                    assertEquals(0, db.oracle.queryForObject(
                            "select count(*) from read_command_result where request_id=?",
                            Integer.class, first.request()));
                } else {
                    assertEquals(Status.COMMITTED, firstAttempt.status(), firstAttempt::toString);
                }
                await(holdsSecondEvent);
                var next = page(db.oracle, 1, 1);
                assertEquals(terminate ? List.of() : List.of(2L), next.positions());
                assertEquals(terminate ? 1L : 2L, next.head());
                if (!terminate) assertEquals(List.of(first.request()), next.keys());
                evidence.println("after first resolution, second event uncommitted: " + next
                        + " first=" + firstAttempt.resolution());
                releaseSecond.countDown();
                assertEquals(Status.COMMITTED, get(b).status());
            } finally {
                releaseFirst.countDown();
                releaseSecond.countDown();
                db.source.after = sql -> {};
            }
            var winner = page(db.oracle, terminate ? 1 : 2, 1);
            assertEquals(List.of(terminate ? 2L : 3L), winner.positions());
            assertEquals(List.of(second.request()), winner.keys());
            evidence.println("second commit: " + winner);
            if (terminate) revokeAndResume(f, first.completion(), evidence);
            var finished = page(db.oracle, 0, 10);
            assertEquals(new Page(3L, List.of(1L, 2L, 3L), terminate
                    ? List.of(f.pot.request(), second.request(), first.request())
                    : List.of(f.pot.request(), first.request(), second.request())), finished);
            assertEquals(3, db.oracle.queryForObject(
                    "select count(distinct event_id) from read_availability_event", Integer.class));
            evidence.println("final prefix=" + finished);
        }
    }

    private void revokeAndResume(ReadParticipantsFixture f, UUID event, PrintWriter evidence) {
        var oldEngine = f.engines.get(COMMAND_RESULT);
        var d = f.participants.get(COMMAND_RESULT).definition();
        int partition = RoutingV1.partition(RoutingV1.hash(d.family(), event), d.partitionCount());
        var db = f.f.read;
        // Reclaim the rolled-back work, then revoke only its partition while this claim is fresh.
        String expire = """
                update consumption_slot set claimed_until=now()-interval '1 second'
                where consumption_id=? and consumable_id=? and claim_token is not null
                """;
        db.oracle.update(expire, d.id().value(), event);
        var oldLease = f.lease(COMMAND_RESULT, oldEngine, partition);
        var stale = f.acquire(COMMAND_RESULT, oldEngine, event);
        assertEquals(1, db.oracle.update("""
                update partition_ownership set owner_token=null, owned_until=null
                where consumption_id=? and partition_id=?
                """, d.id().value(), partition));
        C3Complement.enablePilotEngineOnly(f);
        var newEngine = f.engines.get(COMMAND_RESULT);
        var newLease = newEngine.acquirePartition(partition).orElseThrow();
        assertNotEquals(oldLease.ownerToken(), newLease.ownerToken());
        assertTrue(newEngine.acquire(newLease, List.of(event)).claims().isEmpty(),
                "New partition owner must not steal a live slot claim");
        assertEquals(1, db.oracle.update(expire, d.id().value(), event));
        var takeover = newEngine.acquire(newLease, List.of(event)).claims().getFirst();
        var rejected = oldEngine.execute(stale);
        assertEquals(Status.CLAIM_LOST, rejected.status(), rejected::toString);
        assertEquals(2L, page(db.oracle, 2, 1).head());
        assertNotEquals(stale.claim().claimToken(), takeover.claim().claimToken());
        assertEquals(Status.COMMITTED, newEngine.execute(takeover).status());
        assertEquals(0, f.slots(COMMAND_RESULT, event));
        evidence.println("lease revoked with live claim; new owner could not steal the slot; after claim expiry"
                + " and takeover, old worker=" + rejected.status() + "; new worker committed remaining event");
    }

    private void completeWithLiveClaimAfterRevocation(PrintWriter evidence) throws Exception {
        try (var f = new ReadParticipantsFixture(pg, "c3-page-live-claim")) {
            C3Complement.enablePilot(f);
            f.enroll();
            var oldEngine = f.engines.get(COMMAND_RESULT);
            var event = f.pot.completion();
            var definition = f.participants.get(COMMAND_RESULT).definition();
            int partition = RoutingV1.partition(RoutingV1.hash(definition.family(), event),
                    definition.partitionCount());
            var db = f.f.read;
            var claim = f.acquire(COMMAND_RESULT, oldEngine, event);
            assertEquals(1, db.oracle.update("""
                    update partition_ownership set owner_token=null, owned_until=null
                    where consumption_id=? and partition_id=?
                    """, definition.id().value(), partition));
            C3Complement.enablePilotEngineOnly(f);
            var newEngine = f.engines.get(COMMAND_RESULT);
            var newLease = newEngine.acquirePartition(partition).orElseThrow();
            assertTrue(newEngine.acquire(newLease, List.of(event)).claims().isEmpty());
            assertEquals(Status.COMMITTED, oldEngine.execute(claim).status());
            assertTrue(newEngine.acquire(newLease, List.of(event)).claims().isEmpty());
            assertEquals(new Page(1L, List.of(1L), List.of(f.pot.request())), page(db.oracle, 0, 1));
            evidence.println("revoked partition owner with valid individual claim committed position 1 once");
        }
    }

    private void recoverAfterServerCrash(PrintWriter evidence) throws Exception {
        try (var f = new ReadParticipantsFixture(pg, "c3-crash-recovery")) {
            C3Complement.enablePilot(f);
            var interrupted = f.f.publish();
            var successor = f.f.publish();
            f.enroll();
            f.execute(COMMAND_RESULT, f.pot.completion());
            var db = f.f.read;
            var committed = db.oracle.queryForMap("select * from read_availability_event");
            var held = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            db.source.after = sql -> {
                if (sql.contains("insert into read_availability_event")) {
                    held.countDown();
                    await(release);
                }
            };
            var engine = f.engines.get(COMMAND_RESULT);
            var claim = f.acquire(COMMAND_RESULT, engine, interrupted.completion());
            var attempt = db.threads.submit(() -> engine.execute(claim));
            try {
                await(held);
                int pid = C3Complement.admin(pg).queryForObject("""
                        select pid from pg_stat_activity where usename=? and state='idle in transaction'
                          and query like '%insert into read_availability_event%'
                        """, Integer.class, db.runtimeRole);
                // This PID exists only inside this newly created, throwaway PostgreSQL container.
                // A SIGKILL (unlike pg_terminate_backend) forces the postmaster to recover from WAL.
                var kill = pg.execInContainer("kill", "-KILL", Integer.toString(pid));
                assertEquals(0, kill.getExitCode(), kill.getStderr());
                awaitCrashRecovery();
                assertEquals(committed, db.oracle.queryForMap("select * from read_availability_event"));
                assertEquals(new Page(1L, List.of(), List.of()), page(db.oracle, 1, 1));
                assertEquals(1, f.slots(COMMAND_RESULT, interrupted.completion()));
                evidence.println("postmaster recovered after SIGKILL of writer PID " + pid
                        + "; previous event UUID/position preserved and uncommitted position 2 invisible");
                pg.getLogs().lines().filter(line -> line.contains("terminated by signal 9")
                                || line.contains("automatic recovery in progress") || line.contains("redo done"))
                        .forEach(evidence::println);
            } finally {
                db.source.after = sql -> {};
                release.countDown();
            }
            assertNotEquals(Status.COMMITTED, get(attempt).status());
            f.execute(COMMAND_RESULT, successor.completion());
            assertEquals(new Page(2L, List.of(2L), List.of(successor.request())), page(db.oracle, 1, 1));
            var d = f.participants.get(COMMAND_RESULT).definition();
            db.oracle.update("""
                    update consumption_slot set claimed_until=now()-interval '1 second'
                    where consumption_id=? and consumable_id=? and claim_token is not null
                    """, d.id().value(), interrupted.completion());
            f.execute(COMMAND_RESULT, interrupted.completion());
            assertEquals(new Page(3L, List.of(3L), List.of(interrupted.request())), page(db.oracle, 2, 1));
            assertEquals(committed, db.oracle.queryForMap("select * from read_availability_event where position=1"));
            evidence.println("after crash: successor committed position 2, interrupted source replay committed position 3");
        }
    }

    private void awaitCrashRecovery() throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (pg.getLogs().contains("automatic recovery in progress")) {
                try {
                    assertEquals(1, C3Complement.admin(pg).queryForObject("select 1", Integer.class));
                    return;
                } catch (org.springframework.dao.DataAccessException stillStarting) {
                    // SQL readiness is independent of the server's recovery log.
                }
            }
            Thread.sleep(25);
        }
        throw new AssertionError("PostgreSQL crash recovery was not observed");
    }

    private int waitForCounter(String role, PrintWriter evidence) throws Exception {
        var admin = C3Complement.admin(pg);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            var waits = admin.queryForList("""
                    select pid, wait_event, (pg_blocking_pids(pid))[1] blocker
                    from pg_stat_activity where usename=? and wait_event_type='Lock'
                        and query like '%update read_availability_counter%'
                    """, role);
            if (!waits.isEmpty()) {
                evidence.println("PostgreSQL counter wait=" + waits.getFirst());
                return ((Number) waits.getFirst().get("blocker")).intValue();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("No PostgreSQL wait on the pilot counter");
    }

    private record Page(long head, List<Long> positions, List<UUID> keys) {}

    private static Page page(JdbcTemplate reader, long cursor, int limit) {
        return reader.query("""
                select c.position as head, p.position, p.projection_key
                from read_availability_counter c left join lateral (
                    select position, projection_key from read_availability_event
                    where family=c.family and position > ? and position <= c.position
                    order by position limit ?
                ) p on true where c.family='COMMAND_RESULT_AVAILABLE' order by p.position
                """, rs -> {
                    assertTrue(rs.next());
                    long head = rs.getLong(1);
                    var positions = new java.util.ArrayList<Long>();
                    var keys = new java.util.ArrayList<UUID>();
                    do {
                        Long position = rs.getObject(2, Long.class);
                        if (position != null) {
                            positions.add(position);
                            keys.add(rs.getObject(3, UUID.class));
                        }
                    } while (rs.next());
                    return new Page(head, positions, keys);
                }, cursor, limit);
    }
}
