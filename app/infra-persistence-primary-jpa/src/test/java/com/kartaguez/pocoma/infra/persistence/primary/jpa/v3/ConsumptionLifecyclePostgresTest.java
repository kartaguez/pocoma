package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionSlotStore.*;
import com.kartaguez.pocoma.engine.write.pot.v3.ExecutePotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.v3.*;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions.*;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * Real lifecycle operations on both authorities; every test saves a separate external SQL vector.
 */
@Testcontainers
class ConsumptionLifecyclePostgresTest {
    @Container
    static final PostgreSQLContainer PG =
            new PostgreSQLContainer(
                    DockerImageName.parse(ConsumptionLifecyclePostgresFixture.IMAGE)
                            .asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void initialize() throws Exception {
        ConsumptionLifecyclePostgresFixture.initialize(PG);
    }

    String method;

    @BeforeEach
    void name(TestInfo info) {
        method = info.getTestMethod().orElseThrow().getName();
    }

    interface Scenario {
        void run(ConsumptionLifecyclePostgresFixture f) throws Exception;
    }

    void both(Scenario scenario) throws Exception {
        for (boolean write : List.of(true, false))
            try (var f = new ConsumptionLifecyclePostgresFixture(PG, write)) {
                f.trace.add(
                        "scenario="
                                + method
                                + " authority="
                                + (write ? "WRITE" : "READ")
                                + " started="
                                + f.now());
                try {
                    scenario.run(f);
                    f.trace.add("VARIANT PASS " + method + " " + (write ? "WRITE" : "READ"));
                } catch (Throwable failure) {
                    f.trace.add("VARIANT FAIL " + failure);
                    throw failure;
                } finally {
                    f.snapshot("independent final snapshot");
                    var path =
                            Path.of(
                                    "target/v3-i3-evidence/"
                                            + method
                                            + "-"
                                            + (write ? "WRITE" : "READ")
                                            + ".txt");
                    Files.createDirectories(path.getParent());
                    Files.write(path, f.trace);
                }
            }
    }

    ConsumptionEngine.AcquiredClaim acquire(
            ConsumptionLifecyclePostgresFixture f,
            ConsumptionEngine e,
            PartitionLease lease,
            UUID id) {
        var result = e.acquire(lease, List.of(id));
        assertEquals(1, result.size());
        assertEquals(id, result.getFirst().claim().consumableId());
        return result.getFirst();
    }

    void completed(
            ConsumptionLifecyclePostgresFixture f,
            ConsumptionEngine e,
            ConsumptionEngine.AcquiredClaim claim) {
        assertEquals(ConsumptionEngine.Status.COMMITTED, e.execute(claim).status());
        assertEquals(1, f.effects(claim.claim().consumableId()));
        assertEquals(
                0,
                f.oracle.queryForObject(
                        "select count(*) from consumption_slot where consumption_id=? and"
                                + " consumable_id=?",
                        Integer.class,
                        f.definition.id().value(),
                        claim.claim().consumableId()));
    }

    ConsumptionParticipant wrap(
            ConsumptionLifecyclePostgresFixture f,
            Consumer<ConsumptionParticipant.Prepared> before,
            Consumer<ConsumptionParticipant.Prepared> after) {
        return new ConsumptionParticipant() {
            public Optional<Prepared> prepare(UUID id) {
                return f.participant
                        .prepare(id)
                        .map(
                                p ->
                                        new Prepared() {
                                            public void finalizeIn(SlotCompletion c) {
                                                before.accept(p);
                                                p.finalizeIn(c);
                                                after.accept(p);
                                            }

                                            public void requireCompletionReady() {
                                                p.requireCompletionReady();
                                            }
                                        });
            }

            public RecoveryObservation observe(UUID id, RecoveryReadContext context) {
                return f.participant.observe(id, context);
            }
        };
    }

    ConsumptionParticipant preparationFailure(
            ConsumptionLifecyclePostgresFixture f, RuntimeException fault) {
        return new ConsumptionParticipant() {
            public Optional<Prepared> prepare(UUID id) {
                throw fault;
            }

            public RecoveryObservation observe(UUID id, RecoveryReadContext c) {
                return f.participant.observe(id, c);
            }
        };
    }

    static void latch(CountDownLatch l) {
        try {
            assertTrue(l.await(8, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    ConsumptionSlotStore intercept(
            ConsumptionLifecyclePostgresFixture f, BiConsumer<String, Object[]> before) {
        return (ConsumptionSlotStore)
                Proxy.newProxyInstance(
                        getClass().getClassLoader(),
                        new Class<?>[] {ConsumptionSlotStore.class},
                        (p, m, args) -> {
                            before.accept(m.getName(), args);
                            try {
                                return m.invoke(f.slots, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                        });
    }

    @Test
    void i3_c03_a() throws Exception {
        both(
                f -> {
                    var a = f.engine();
                    var lease = a.acquirePartition(0).orElseThrow();
                    var s = f.seed(0);
                    var s2 = f.seed(0);
                    var claim = acquire(f, a, lease, s);
                    f.expireLease(0);
                    var b = f.engine();
                    var newLease = b.acquirePartition(0).orElseThrow();
                    assertNotEquals(lease.ownerToken(), newLease.ownerToken());
                    assertTrue(b.acquire(newLease, List.of(s)).isEmpty());
                    var other = acquire(f, b, newLease, s2);
                    try (var partition = f.admin.getConnection()) {
                        partition.setAutoCommit(false);
                        try (var q = partition.createStatement()) {
                            q.executeQuery(
                                            "select * from partition_ownership where partition_id=0"
                                                    + " for update")
                                    .close();
                        }
                        completed(f, a, claim);
                        partition.rollback();
                    }
                    completed(f, b, other);
                });
    }

    @Test
    void i3_c03_b() throws Exception {
        both(
                f -> {
                    var a = f.engine();
                    var old = a.acquirePartition(0).orElseThrow();
                    var s = f.seed(0);
                    f.expireLease(0);
                    var b = f.engine();
                    b.acquirePartition(0).orElseThrow();
                    var before = f.row(s);
                    assertTrue(a.acquire(old, List.of(s)).isEmpty());
                    assertTrue(a.renewPartitions(List.of(old)).isEmpty());
                    assertEquals(before, f.row(s));
                    assertEquals(0, f.capacity.snapshot().worker());
                });
    }

    @Test
    void i3_c03_c() throws Exception {
        both(
                f -> {
                    var a = f.engine();
                    var lease = a.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var h = acquire(f, a, lease, id);
                    f.expireLease(0);
                    var b = f.engine();
                    var owner = b.acquirePartition(0).orElseThrow();
                    try (var lock = f.admin.getConnection()) {
                        lock.setAutoCommit(false);
                        lock.createStatement()
                                .executeQuery(
                                        "select * from partition_ownership where partition_id=0 for"
                                                + " update")
                                .close();
                        assertEquals(1, a.renewClaims(List.of(h.claim())).size());
                        assertTrue(b.renewPartitions(List.of(owner)).isEmpty());
                        lock.rollback();
                    }
                    assertEquals(1, b.renewPartitions(List.of(owner)).size());
                    completed(f, a, h);
                });
    }

    @Test
    void i3_c03_d() throws Exception {
        both(
                f -> {
                    var a = f.engine();
                    var lease = a.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var h = acquire(f, a, lease, id);
                    f.expireLease(0);
                    var b = f.engine();
                    var next = b.acquirePartition(0).orElseThrow();
                    var before = f.row(id);
                    assertTrue(b.acquire(next, List.of(id)).isEmpty());
                    assertEquals(before, f.row(id));
                    completed(f, a, h);
                });
    }

    @Test
    void i3_c03_e() throws Exception {
        both(
                f -> {
                    var a = f.engine();
                    var lease = a.acquirePartition(0).orElseThrow();
                    for (boolean renewalFirst : List.of(true, false)) {
                        var id = f.seed(0);
                        var old = acquire(f, a, lease, id);
                        f.expire(id);
                        var held = new CountDownLatch(1);
                        var finish = new CountDownLatch(1);
                        var instrumented =
                                (ConsumptionSlotStore)
                                        Proxy.newProxyInstance(
                                                getClass().getClassLoader(),
                                                new Class<?>[] {ConsumptionSlotStore.class},
                                                (proxy, method, args) -> {
                                                    Object value;
                                                    try {
                                                        value = method.invoke(f.slots, args);
                                                    } catch (InvocationTargetException error) {
                                                        throw error.getCause();
                                                    }
                                                    if (method.getName()
                                                            .equals(
                                                                    renewalFirst
                                                                            ? "renew"
                                                                            : "acquire")) {
                                                        held.countDown();
                                                        latch(finish);
                                                    }
                                                    return value;
                                                });
                        var worker = f.engine(f.participant, f.tx, instrumented);
                        ConsumptionEngine.AcquiredClaim next;
                        if (renewalFirst) {
                            var renewal =
                                    f.threads.submit(
                                            () -> worker.renewClaims(List.of(old.claim())));
                            latch(held);
                            try {
                                assertTrue(a.acquire(lease, List.of(id)).isEmpty());
                            } finally {
                                finish.countDown();
                            }
                            assertTrue(renewal.get(8, TimeUnit.SECONDS).isEmpty());
                            next = acquire(f, a, lease, id);
                        } else {
                            var acquisition =
                                    f.threads.submit(() -> worker.acquire(lease, List.of(id)));
                            latch(held);
                            try {
                                assertTrue(a.renewClaims(List.of(old.claim())).isEmpty());
                            } finally {
                                finish.countDown();
                            }
                            var result = acquisition.get(8, TimeUnit.SECONDS);
                            assertEquals(1, result.size());
                            next = result.getFirst();
                        }
                        assertNotEquals(old.claim().claimToken(), next.claim().claimToken());
                        assertEquals(ConsumptionEngine.Status.CLAIM_LOST, a.execute(old).status());
                        completed(f, renewalFirst ? a : worker, next);
                        f.trace.add(
                                "concurrent expired renewal/acquisition lock order renewalFirst="
                                        + renewalFirst);
                    }
                });
    }

    @Test
    void i3_c03_f() throws Exception {
        both(
                f -> {
                    var locked = new CountDownLatch(1);
                    var finish = new CountDownLatch(1);
                    var a =
                            f.engine(
                                    wrap(
                                            f,
                                            p -> {
                                                locked.countDown();
                                                latch(finish);
                                            },
                                            p -> {}),
                                    f.tx,
                                    f.slots);
                    var lease = a.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var h = acquire(f, a, lease, id);
                    f.oracle.update(
                            "update consumption_slot set claimed_until=clock_timestamp()+interval"
                                    + " '200 milliseconds' where consumable_id=?",
                            id);
                    var deadline = ((Timestamp) f.row(id).get("claimed_until")).toInstant();
                    var task = f.threads.submit(() -> a.execute(h));
                    latch(locked);
                    try {
                        f.await(() -> f.now().isAfter(deadline));
                        var b = f.engine();
                        assertTrue(b.acquire(lease, List.of(id)).isEmpty());
                        f.noEffects(id);
                        f.trace.add(
                                "DB after deadline="
                                        + f.now()
                                        + " while final lock retained; B skipped");
                    } finally {
                        finish.countDown();
                    }
                    assertEquals(
                            ConsumptionEngine.Status.COMMITTED,
                            task.get(8, TimeUnit.SECONDS).status());
                    assertEquals(1, f.effects(id));
                    assertTrue(f.engine().acquire(lease, List.of(id)).isEmpty());
                });
    }

    @Test
    void i3_c03_g() throws Exception {
        both(
                f -> {
                    for (boolean cession : List.of(true)) {
                        var e = f.engine();
                        var lease =
                                e.acquirePartition(0)
                                        .orElseGet(
                                                () ->
                                                        new PartitionLease(
                                                                f.definition.id(),
                                                                0,
                                                                (UUID)
                                                                        f.oracle
                                                                                .queryForMap(
                                                                                        "select"
                                                                                            + " owner_token"
                                                                                            + " from"
                                                                                            + " partition_ownership"
                                                                                            + " where"
                                                                                            + " consumption_id=?"
                                                                                            + " and partition_id=0",
                                                                                        f.definition
                                                                                                .id()
                                                                                                .value())
                                                                                .get("owner_token"),
                                                                f.now().plusSeconds(30)));
                        var id = f.seed(0);
                        var h = acquire(f, e, lease, id);
                        f.oracle.update(
                                "update consumption_slot set"
                                    + " claimed_until=clock_timestamp()+interval '300 milliseconds'"
                                    + " where consumable_id=?",
                                id);
                        var deadline = ((Timestamp) f.row(id).get("claimed_until")).toInstant();
                        var pid = new AtomicInteger();
                        try (var lock = f.lock(id)) {
                            var future =
                                    f.threads.submit(
                                            () -> {
                                                if (cession)
                                                    return f.technical(
                                                            () -> {
                                                                pid.set(
                                                                        f.sql.queryForObject(
                                                                                "select"
                                                                                    + " pg_backend_pid()",
                                                                                Integer.class));
                                                                return f.slots.cede(
                                                                        h.claim(),
                                                                        f.settings.retry());
                                                            });
                                                var guarded =
                                                        intercept(
                                                                f,
                                                                (name, args) -> {
                                                                    if (name.equals("protectFinal"))
                                                                        pid.set(
                                                                                f.sql
                                                                                        .queryForObject(
                                                                                                "select"
                                                                                                    + " pg_backend_pid()",
                                                                                                Integer
                                                                                                        .class));
                                                                });
                                                return f.engine(f.participant, f.tx, guarded)
                                                        .execute(h);
                                            });
                            // The final Engine must own its handle; use the original Engine for
                            // finalization below.
                            if (!cession) {
                                lock.rollback();
                                assertThrows(
                                        ExecutionException.class,
                                        () -> future.get(8, TimeUnit.SECONDS));
                                e.abandon(h);
                                continue;
                            }
                            f.await(() -> pid.get() != 0);
                            f.blocked(pid.get());
                            f.await(() -> f.now().isAfter(deadline));
                            lock.rollback();
                            assertEquals(Optional.empty(), future.get(8, TimeUnit.SECONDS));
                            assertEquals(0, f.row(id).get("attempt_count"));
                            e.abandon(h);
                        }
                    }
                    // Actual finalizer waits through its deadline, under the same Engine/handle.
                    var pid = new AtomicInteger();
                    var slots =
                            intercept(
                                    f,
                                    (name, args) -> {
                                        if (name.equals("protectFinal"))
                                            pid.set(
                                                    f.sql.queryForObject(
                                                            "select pg_backend_pid()",
                                                            Integer.class));
                                    });
                    var e = f.engine(f.participant, f.tx, slots);
                    f.expireLease(0);
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var h = acquire(f, e, lease, id);
                    f.oracle.update(
                            "update consumption_slot set claimed_until=clock_timestamp()+interval"
                                    + " '300 milliseconds' where consumable_id=?",
                            id);
                    var deadline = ((Timestamp) f.row(id).get("claimed_until")).toInstant();
                    try (var lock = f.lock(id)) {
                        var task = f.threads.submit(() -> e.execute(h));
                        f.await(() -> pid.get() != 0);
                        f.blocked(pid.get());
                        f.await(() -> f.now().isAfter(deadline));
                        lock.rollback();
                        assertEquals(
                                ConsumptionEngine.Status.CLAIM_LOST,
                                task.get(8, TimeUnit.SECONDS).status());
                    }
                    f.noEffects(id);
                    assertEquals(0, f.row(id).get("attempt_count"));
                });
    }

    @Test
    void i3_c03_h() throws Exception {
        both(
                f -> {
                    var a = f.engine();
                    var lease = a.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var old = acquire(f, a, lease, id);
                    f.expire(id);
                    var b = f.engine();
                    var next = acquire(f, b, lease, id);
                    var before = f.row(id);
                    assertTrue(a.renewClaims(List.of(old.claim())).isEmpty());
                    assertTrue(
                            f.technical(() -> f.slots.cede(old.claim(), f.settings.retry()))
                                    .isEmpty());
                    assertEquals(ConsumptionEngine.Status.CLAIM_LOST, a.execute(old).status());
                    assertEquals(before, f.row(id));
                    completed(f, b, next);
                });
    }

    @Test
    void i3_c03_i() throws Exception {
        both(
                f -> {
                    var a = f.engine();
                    var lease = a.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var h = acquire(f, a, lease, id);
                    f.expire(id);
                    a.abandon(h);
                    f.expireLease(0);
                    var b = f.engine();
                    var lb = b.acquirePartition(0).orElseThrow();
                    var hb = acquire(f, b, lb, id);
                    f.expireLease(0);
                    var c = f.engine();
                    c.acquirePartition(0).orElseThrow();
                    assertEquals(1, b.renewClaims(List.of(hb.claim())).size());
                    assertTrue(b.acquire(lb, List.of(f.seed(0))).isEmpty());
                    completed(f, b, hb);
                });
    }

    @Test
    void i3_c03_j() throws Exception {
        both(f -> wire(f, "final"));
    }

    @Test
    void i3_c03_k() throws Exception {
        both(
                f -> {
                    var a = f.engine();
                    var b = f.engine();
                    for (int p = 0; p < 4; p++) {
                        var la = a.acquirePartition(p).orElseThrow();
                        var id = f.seed(p);
                        var old = acquire(f, a, la, id);
                        f.expireLease(p);
                        var lb = b.acquirePartition(p).orElseThrow();
                        assertTrue(a.acquire(la, List.of(id)).isEmpty());
                        assertTrue(b.acquire(lb, List.of(id)).isEmpty());
                        assertEquals(1, a.renewClaims(List.of(old.claim())).size());
                        completed(f, a, old);
                        var next = acquire(f, b, lb, f.seed(p));
                        completed(f, b, next);
                    }
                });
    }

    @Test
    void i3_c03_l() throws Exception {
        both(
                f -> {
                    // Fixed finite campaign, two interleaving orders, independent expected one/zero
                    // effects.
                    var a = f.engine();
                    var b = f.engine();
                    var lease = a.acquirePartition(0).orElseThrow();
                    for (int i = 0; i < 8; i++) {
                        var id = f.seed(0);
                        var old = acquire(f, a, lease, id);
                        if (i % 2 == 0) {
                            f.expire(id);
                            var next = acquire(f, b, lease, id);
                            assertEquals(
                                    ConsumptionEngine.Status.CLAIM_LOST, a.execute(old).status());
                            completed(f, b, next);
                        } else {
                            assertTrue(b.acquire(lease, List.of(id)).isEmpty());
                            completed(f, a, old);
                        }
                        f.trace.add(
                                "seed=0 campaign step=" + i + " durable effects=" + f.effects(id));
                    }
                    assertEquals(0, f.capacity.snapshot().worker());
                });
    }

    @Test
    void i3_lease_races() throws Exception {
        both(
                f -> {
                    var start = new CountDownLatch(1);
                    var futures = new ArrayList<Future<Optional<PartitionLease>>>();
                    for (int i = 0; i < 8; i++)
                        futures.add(
                                f.threads.submit(
                                        () -> {
                                            latch(start);
                                            return f.engine().acquirePartition(0);
                                        }));
                    start.countDown();
                    var winners = new ArrayList<PartitionLease>();
                    for (var task : futures) task.get(8, TimeUnit.SECONDS).ifPresent(winners::add);
                    assertEquals(1, winners.size());
                    var old = winners.getFirst();
                    f.expireLease(0);
                    assertTrue(f.engine().renewPartitions(List.of(old)).isEmpty());
                    var next = f.engine().acquirePartition(0).orElseThrow();
                    assertNotEquals(old.ownerToken(), next.ownerToken());
                    assertTrue(f.engine().renewPartitions(List.of(old)).isEmpty());
                });
    }

    @Test
    void i3_route_admin_serialization() throws Exception {
        both(
                f -> {
                    var e = f.engine();
                    var pid = new AtomicInteger();
                    try (var lock = f.admin.getConnection()) {
                        lock.setAutoCommit(false);
                        lock.createStatement()
                                .execute(
                                        "lock table consumption_routing_contract in access"
                                                + " exclusive mode");
                        var task =
                                f.threads.submit(
                                        () ->
                                                f.technical(
                                                        () -> {
                                                            pid.set(
                                                                    f.sql.queryForObject(
                                                                            "select"
                                                                                + " pg_backend_pid()",
                                                                            Integer.class));
                                                            return f.ownership.acquire(
                                                                    f.definition,
                                                                    0,
                                                                    UUID.randomUUID(),
                                                                    Duration.ofSeconds(30));
                                                        }));
                        f.await(() -> pid.get() != 0);
                        f.blocked(pid.get());
                        lock.rollback();
                        assertTrue(task.get(8, TimeUnit.SECONDS).isPresent());
                    }
                    var routeHeld = new CountDownLatch(1);
                    var finish = new CountDownLatch(1);
                    var token =
                            (UUID)
                                    f.oracle
                                            .queryForMap(
                                                    "select owner_token from partition_ownership"
                                                            + " where consumption_id=? and"
                                                            + " partition_id=0",
                                                    f.definition.id().value())
                                            .get("owner_token");
                    var runtime =
                            f.threads.submit(
                                    () ->
                                            f.technical(
                                                    () -> {
                                                        f.ownership.protectAcquisition(
                                                                f.definition,
                                                                new PartitionLease(
                                                                        f.definition.id(),
                                                                        0,
                                                                        token,
                                                                        f.now().plusSeconds(30)));
                                                        routeHeld.countDown();
                                                        latch(finish);
                                                        return null;
                                                    }));
                    latch(routeHeld);

                    var adminPid = new AtomicInteger();
                    var administration =
                            f.threads.submit(
                                    () -> {
                                        try (var c = f.admin.getConnection()) {
                                            c.setAutoCommit(false);
                                            try (var q = c.createStatement()) {
                                                try (var rs =
                                                        q.executeQuery("select pg_backend_pid()")) {
                                                    rs.next();
                                                    adminPid.set(rs.getInt(1));
                                                }
                                                q.execute(
                                                        "lock table consumption_routing_contract in"
                                                                + " access exclusive mode");
                                            }
                                            c.rollback();
                                            return true;
                                        }
                                    });
                    try {
                        f.await(() -> adminPid.get() != 0);
                        f.blocked(adminPid.get());
                    } finally {
                        finish.countDown();
                    }
                    runtime.get(8, TimeUnit.SECONDS);
                    assertTrue(administration.get(8, TimeUnit.SECONDS));
                    var mismatch =
                            new ConsumptionDefinition(
                                    f.definition.id(),
                                    f.write
                                            ? ConsumableFamily.IDENTITY_REQUEST
                                            : ConsumableFamily.INVITATION_EVENT,
                                    f.definition.authority());
                    assertThrows(
                            Failure.class,
                            () ->
                                    f.technical(
                                            () ->
                                                    f.ownership.acquire(
                                                            mismatch,
                                                            0,
                                                            UUID.randomUUID(),
                                                            Duration.ofSeconds(30))));
                    assertThrows(
                            Exception.class,
                            () ->
                                    f.sql.update(
                                            "update consumption_routing_contract set"
                                                    + " partition_count=4"));
                });
    }

    @Test
    void i3_acquisition_partial() throws Exception {
        both(
                f -> {
                    var e = f.engine();
                    var lease = e.acquirePartition(0).orElseThrow();
                    var ids = new ArrayList<UUID>();
                    for (int i = 0; i < 7; i++) ids.add(f.seed(0));
                    f.oracle.update(
                            "update consumption_slot set"
                                    + " claim_token=?,claimed_until=clock_timestamp()+interval '60"
                                    + " seconds' where consumable_id=?",
                            new UUID(9, 1),
                            ids.get(1));
                    f.oracle.update(
                            "update consumption_slot set"
                                    + " claim_token=?,claimed_until=clock_timestamp()-interval '1"
                                    + " second' where consumable_id=?",
                            new UUID(9, 2),
                            ids.get(2));
                    f.oracle.update(
                            "update consumption_slot set retry_after=clock_timestamp()+interval '60"
                                    + " seconds' where consumable_id=?",
                            ids.get(3));
                    f.oracle.update(
                            "update consumption_slot set state='QUARANTINED',attempt_count=3 where"
                                    + " consumable_id=?",
                            ids.get(4));
                    try (var lock = f.lock(ids.get(5))) {
                        // M_C=2 reserves first two examined keys only, never advances over the
                        // unsupplied remainder.
                        var result = e.acquire(lease, List.of(ids.get(0), ids.get(1), ids.get(2)));
                        assertEquals(1, result.size());
                        e.abandon(result.getFirst());
                        assertEquals(0, f.capacity.snapshot().worker());
                        assertNull(f.row(ids.get(2)).get("retry_after"));
                        assertTrue(e.acquire(lease, List.of(ids.get(3), ids.get(4))).isEmpty());
                        assertTrue(e.acquire(lease, List.of(ids.get(5))).isEmpty());
                        lock.rollback();
                    }
                    var takeover = acquire(f, e, lease, ids.get(2));
                    assertNotEquals(new UUID(9, 2), takeover.claim().claimToken());
                    completed(f, e, takeover);
                    // Concurrent acquisition under the same lease: no key is won twice.
                    var start = new CountDownLatch(1);
                    var a = f.engine();
                    var b = f.engine();
                    var x =
                            f.threads.submit(
                                    () -> {
                                        latch(start);
                                        return a.acquire(lease, List.of(ids.get(5), ids.get(6)));
                                    });
                    var y =
                            f.threads.submit(
                                    () -> {
                                        latch(start);
                                        return b.acquire(lease, List.of(ids.get(5), ids.get(6)));
                                    });
                    start.countDown();
                    var xs = x.get(8, TimeUnit.SECONDS);
                    var ys = y.get(8, TimeUnit.SECONDS);
                    var keys = new HashSet<UUID>();
                    for (var h : xs) {
                        assertTrue(keys.add(h.claim().consumableId()));
                        a.abandon(h);
                    }
                    for (var h : ys) {
                        assertTrue(keys.add(h.claim().consumableId()));
                        b.abandon(h);
                    }
                    assertEquals(2, keys.size());
                });
    }

    @Test
    void i3_renew_s1_s2() throws Exception {
        both(
                f -> {
                    var e = f.engine();
                    var lease = e.acquirePartition(0).orElseThrow();
                    var s1 = f.seed(0);
                    var s2 = f.seed(0);
                    var hs = e.acquire(lease, List.of(s1, s2));
                    assertEquals(2, hs.size());
                    var before = (Timestamp) f.row(s2).get("claimed_until");
                    var mixed =
                            new ArrayList<>(
                                    hs.stream()
                                            .map(ConsumptionEngine.AcquiredClaim::claim)
                                            .toList());
                    var expired = f.seed(0);
                    var lost = f.seed(0);
                    var q = f.seed(0);
                    quarantine(f, q);
                    f.oracle.update(
                            "update consumption_slot set"
                                    + " claim_token=?,claimed_until=clock_timestamp()-interval '1"
                                    + " second' where consumable_id=?",
                            new UUID(8, 1),
                            expired);
                    f.oracle.update(
                            "update consumption_slot set"
                                    + " claim_token=?,claimed_until=clock_timestamp()+interval '60"
                                    + " seconds' where consumable_id=?",
                            new UUID(8, 2),
                            lost);
                    mixed.add(new ClaimedSlot(f.definition.id(), expired, new UUID(8, 1)));
                    mixed.add(new ClaimedSlot(f.definition.id(), lost, new UUID(8, 3)));
                    mixed.add(new ClaimedSlot(f.definition.id(), q, new UUID(8, 4)));
                    mixed.add(new ClaimedSlot(f.definition.id(), new UUID(8, 999), new UUID(8, 5)));
                    try (var lock = f.lock(s1)) {
                        var renewed = e.renewClaims(mixed);
                        assertEquals(
                                List.of(s2),
                                renewed.stream().map(g -> g.claim().consumableId()).toList());
                        assertTrue(((Timestamp) f.row(s2).get("claimed_until")).after(before));
                        f.trace.add(
                                "S2 renewal externally committed at "
                                        + f.now()
                                        + " while S1 still locked");
                        assertFalse(lock.isClosed());
                        lock.rollback();
                    }
                    var old = hs.getFirst();
                    f.expire(old.claim().consumableId());
                    assertTrue(e.renewClaims(List.of(old.claim())).isEmpty());
                    e.abandon(old);
                    e.abandon(hs.get(1));
                    var lostToken = new ClaimedSlot(f.definition.id(), s2, new UUID(0, 999));
                    assertTrue(e.renewClaims(List.of(lostToken)).isEmpty());
                    var deadline = f.row(s2).get("claimed_until");
                    assertThrows(
                            Failure.class,
                            () ->
                                    f.tx.write(
                                            () -> {
                                                f.slots.renew(
                                                        List.of(hs.get(1).claim()),
                                                        Duration.ofSeconds(120));
                                                throw new IllegalStateException("rollback renewal");
                                            }));
                    assertEquals(deadline, f.row(s2).get("claimed_until"));
                });
    }

    @Test
    void i3_renew_relation_timeout() throws Exception {
        both(
                f -> {
                    var e = f.engine();
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var h = acquire(f, e, lease, id);
                    var before = f.row(id);
                    try (var lock = f.admin.getConnection()) {
                        lock.setAutoCommit(false);
                        lock.createStatement()
                                .execute("lock table consumption_slot in access exclusive mode");
                        var task = f.threads.submit(() -> e.renewClaims(List.of(h.claim())));
                        var failure =
                                assertThrows(
                                        ExecutionException.class,
                                        () -> task.get(8, TimeUnit.SECONDS));
                        assertInstanceOf(Failure.class, failure.getCause());
                        f.trace.add("relation timeout=" + failure.getCause());
                        lock.rollback();
                    }
                    assertEquals(before, f.row(id));
                    assertEquals(0, f.row(id).get("attempt_count"));
                    e.abandon(h);
                });
    }

    @Test
    void i3_acquisition_lease_clock() throws Exception {
        both(
                f -> {
                    f.settings =
                            new LifecycleSettings(
                                    32,
                                    Duration.ofMillis(200),
                                    Duration.ofSeconds(10),
                                    Duration.ofMillis(50),
                                    Duration.ofSeconds(1),
                                    Duration.ofSeconds(2),
                                    f.settings.retry());
                    var e = f.engine();
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    try (var lock = f.admin.getConnection()) {
                        lock.setAutoCommit(false);
                        lock.createStatement()
                                .execute("lock table consumption_slot in access exclusive mode");
                        var task = f.threads.submit(() -> e.acquire(lease, List.of(id)));
                        f.await(
                                () ->
                                        f.oracle.queryForObject(
                                                        "select count(*) from pg_stat_activity"
                                                                + " where usename=? and"
                                                                + " wait_event_type='Lock'",
                                                        Integer.class,
                                                        f.runtimeRole)
                                                > 0);
                        f.await(() -> f.now().isAfter(lease.ownedUntil()));
                        f.trace.add("ownership deadline crossed under relation wait " + f.now());
                        lock.rollback();
                        assertTrue(task.get(8, TimeUnit.SECONDS).isEmpty());
                    }
                    assertNull(f.row(id).get("claim_token"));
                    assertEquals(0, f.capacity.snapshot().worker());
                });
    }

    void failed(
            ConsumptionLifecyclePostgresFixture f,
            boolean preparation,
            RuntimeException fault,
            int n) {
        var business =
                preparation
                        ? preparationFailure(f, fault)
                        : wrap(
                                f,
                                p -> {},
                                p -> {
                                    throw fault;
                                });
        var e = f.engine(business, f.tx, f.slots);
        var lease = e.acquirePartition(0).orElseThrow();
        var id = f.seed(0);
        var h = acquire(f, e, lease, id);
        var attempt = e.execute(h);
        assertEquals(ConsumptionEngine.Status.ROLLED_BACK, attempt.status());
        assertEquals(Resolution.ROLLED_BACK, attempt.resolution());
        assertNotNull(attempt.cession());
        assertEquals(n, attempt.cession().attemptCount());
        f.noEffects(id);
        assertEquals(n, f.row(id).get("attempt_count"));
        assertNull(f.row(id).get("claim_token"));
        assertEquals(0, f.capacity.snapshot().worker());
        assertThrows(IllegalArgumentException.class, () -> e.execute(h));
        f.trace.add("rollback then separate cession=" + attempt);
    }

    @Test
    void i3_c05_a() throws Exception {
        both(f -> failed(f, true, new IllegalArgumentException("durable payload corrupt"), 1));
    }

    @Test
    void i3_c05_b() throws Exception {
        both(
                f -> {
                    var id = f.seed(0);
                    var snapshots = new ArrayList<String>();
                    var business =
                            wrap(
                                    f,
                                    p -> {},
                                    p -> {
                                        snapshots.add(
                                                "inside effects="
                                                        + f.sql.queryForObject(
                                                                f.write
                                                                        ? "select count(*) from"
                                                                              + " v3_command_result"
                                                                              + " where"
                                                                              + " request_id=?"
                                                                        : "select count(*) from"
                                                                                + " fixture_effect"
                                                                                + " where id=?",
                                                                Integer.class,
                                                                id)
                                                        + " xid="
                                                        + f.sql.queryForObject(
                                                                "select pg_current_xact_id()::text",
                                                                String.class));
                                        throw new IllegalStateException(
                                                "after all effects before physical commit");
                                    });
                    var engine =
                            f.engine(
                                    business,
                                    f.tx,
                                    intercept(
                                            f,
                                            (name, args) -> {
                                                if (name.equals("cede")) {
                                                    f.noEffects(id);
                                                    snapshots.add(
                                                            "cession xid="
                                                                    + f.sql.queryForObject(
                                                                            "select"
                                                                                + " pg_current_xact_id()::text",
                                                                            String.class));
                                                }
                                            }));
                    var lease = engine.acquirePartition(0).orElseThrow();
                    var h = acquire(f, engine, lease, id);
                    var result = engine.execute(h);
                    assertEquals(1, result.cession().attemptCount());
                    f.noEffects(id);
                    assertEquals(2, snapshots.size());
                    assertNotEquals(
                            snapshots.get(0).split("xid=")[1], snapshots.get(1).split("xid=")[1]);
                    f.trace.addAll(snapshots);
                    // A caught SQL fault still poisons the final unit and retains its real cause.
                    f.expireLease(0);
                    var sqlFailure =
                            f.engine(
                                    wrap(
                                            f,
                                            p -> {
                                                try {
                                                    SpringAttemptTransactions.guarded(
                                                            f.source,
                                                            () ->
                                                                    f.sql.queryForObject(
                                                                            "select 1/0",
                                                                            Integer.class));
                                                } catch (RuntimeException caught) {
                                                }
                                            },
                                            p -> {}),
                                    f.tx,
                                    f.slots);
                    var l2 = sqlFailure.acquirePartition(0).orElseThrow();
                    var h2 = acquire(f, sqlFailure, l2, f.seed(0));
                    assertEquals(1, sqlFailure.execute(h2).cession().attemptCount());
                    assertEquals(0, f.effects(h2.claim().consumableId()));
                });
    }

    void collisionThenRecalculation(ConsumptionLifecyclePostgresFixture f, boolean obsolete)
            throws Exception {
        if (!f.write) {
            var bad =
                    f.engine(
                            preparationFailure(f, new ExecutePotCommand.Collision()),
                            f.tx,
                            f.slots);
            var lease = bad.acquirePartition(0).orElseThrow();
            var id = f.seed(0);
            var old = acquire(f, bad, lease, id);
            assertEquals(1, bad.execute(old).cession().attemptCount());
            var good = f.engine();
            completed(f, good, acquire(f, good, lease, id));
            f.trace.add(
                    "READ technical collision/recalculation analogue; no business rejection"
                            + " claimed");
            return;
        }
        var good = f.engine();
        var lease = good.acquirePartition(0).orElseThrow();
        var create = f.seed(0);
        completed(f, good, acquire(f, good, lease, create));
        var pot =
                f.oracle.queryForObject(
                        "select pot_id from v3_command where request_id=?", UUID.class, create);
        var intent =
                new com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand
                        .UpdatePotDetails(
                        new com.kartaguez.pocoma.domain.pot.value.Label("original intent"));
        var original = f.seedCommand(0, pot, obsolete ? 1 : 99, intent);
        var concurrent =
                f.seedCommand(
                        0,
                        pot,
                        1,
                        new com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand
                                .UpdatePotDetails(
                                new com.kartaguez.pocoma.domain.pot.value.Label(
                                        "concurrent revision")));
        var sourceBefore =
                f.oracle.queryForMap(
                        "select payload,observed_version from v3_command where request_id=?",
                        original);
        var preparations = new AtomicInteger();
        var injected = new AtomicBoolean();
        var owner =
                new ForwardingTransactions(f.tx) {
                    public <T> T read(Function<RecoveryReadContext, T> action) {
                        var result = f.tx.read(action);
                        if (injected.compareAndSet(false, true)) {
                            preparations.incrementAndGet();
                            completed(f, good, acquire(f, good, lease, concurrent));
                        } else preparations.incrementAndGet();
                        return result;
                    }
                };
        var bad = f.engine(f.participant, owner, f.slots);
        var h = acquire(f, bad, lease, original);
        var first = bad.execute(h);
        assertEquals(ConsumptionEngine.Status.ROLLED_BACK, first.status());
        assertEquals(AttemptFailureClassifier.FailureKind.COLLISION, first.failureKind());
        assertEquals(1, first.cession().attemptCount());
        assertEquals(0, f.effects(original));
        var next = acquire(f, bad, lease, original);
        assertNotEquals(h.claim().claimToken(), next.claim().claimToken());
        var result = bad.execute(next);
        assertEquals(Resolution.COMMITTED, result.resolution());
        assertNull(result.cession());
        assertEquals(2, preparations.get());
        assertEquals(
                sourceBefore,
                f.oracle.queryForMap(
                        "select payload,observed_version from v3_command where request_id=?",
                        original));
        assertEquals(
                "REJECTED",
                f.oracle.queryForObject(
                        "select outcome from v3_command_result where request_id=?",
                        String.class,
                        original));
        assertEquals(
                2L,
                f.oracle.queryForObject(
                        "select current_version from v3_pot_identity where pot_id=?",
                        Long.class,
                        pot));
        f.trace.add(
                "actual Pot CAS collision -> count=1 -> new claim/preparation -> obsolete terminal"
                        + " rejection; immutable intention="
                        + sourceBefore);
    }

    @Test
    void i3_c05_c() throws Exception {
        both(f -> collisionThenRecalculation(f, true));
    }

    @Test
    void i3_c05_d() throws Exception {
        both(f -> collisionThenRecalculation(f, true));
    }

    @Test
    void i3_c05_e() throws Exception {
        both(f -> collisionThenRecalculation(f, false));
    }

    @Test
    void i3_c05_f() throws Exception {
        both(
                f -> {
                    f.settings =
                            new LifecycleSettings(
                                    32,
                                    Duration.ofSeconds(30),
                                    Duration.ofSeconds(60),
                                    Duration.ofSeconds(10),
                                    Duration.ofMillis(500),
                                    Duration.ofSeconds(2),
                                    new LifecycleSettings.RetryPolicy(3, Duration.ofMillis(300)));
                    var bad =
                            f.engine(
                                    preparationFailure(f, new IllegalStateException("fault")),
                                    f.tx,
                                    f.slots);
                    var lease = bad.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var result = bad.execute(acquire(f, bad, lease, id));
                    var deadline = result.cession().retryAfter();
                    assertNotNull(deadline);
                    var good = f.engine();
                    assertTrue(good.acquire(lease, List.of(id)).isEmpty());
                    f.await(() -> !f.now().isBefore(deadline));
                    completed(f, good, acquire(f, good, lease, id));
                });
    }

    @Test
    void i3_c05_g() throws Exception {
        both(
                f -> {
                    var e = f.engine();
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var old = acquire(f, e, lease, id);
                    f.expire(id);
                    assertTrue(
                            f.technical(() -> f.slots.cede(old.claim(), f.settings.retry()))
                                    .isEmpty());
                    e.abandon(old);
                    var next = acquire(f, e, lease, id);
                    var before = f.row(id);
                    assertTrue(
                            f.technical(() -> f.slots.cede(old.claim(), f.settings.retry()))
                                    .isEmpty());
                    assertEquals(before, f.row(id));
                    completed(f, e, next);
                });
    }

    @Test
    void i3_c05_h() throws Exception {
        both(f -> wire(f, "cession"));
    }

    @Test
    void i3_c05_i() throws Exception {
        both(
                f -> {
                    var bad =
                            f.engine(
                                    preparationFailure(f, new IllegalStateException("fault")),
                                    f.tx,
                                    f.slots);
                    var lease = bad.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var tokens = new HashSet<UUID>();
                    for (int i = 1; i < 3; i++) {
                        var h = acquire(f, bad, lease, id);
                        assertTrue(tokens.add(h.claim().claimToken()));
                        assertEquals(i, bad.execute(h).cession().attemptCount());
                        assertEquals("AVAILABLE", f.row(id).get("state"));
                    }
                    var good = f.engine();
                    var h = acquire(f, good, lease, id);
                    assertTrue(tokens.add(h.claim().claimToken()));
                    completed(f, good, h);
                });
    }

    @Test
    void i3_c05_j() throws Exception {
        both(
                f -> {
                    var bad =
                            f.engine(
                                    preparationFailure(
                                            f, new IllegalStateException("repeatable bug")),
                                    f.tx,
                                    f.slots);
                    var lease = bad.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    for (int i = 1; i <= 3; i++)
                        assertEquals(
                                i,
                                bad.execute(acquire(f, bad, lease, id)).cession().attemptCount());
                    assertEquals("QUARANTINED", f.row(id).get("state"));
                    assertNull(f.row(id).get("retry_after"));
                    assertTrue(bad.acquire(lease, List.of(id)).isEmpty());
                    f.noEffects(id);
                    if (f.write)
                        assertEquals(
                                "PENDING",
                                f.oracle.queryForObject(
                                        "select status from v3_command where request_id=?",
                                        String.class,
                                        id));
                    // UNKNOWN preparation does not manufacture rollback or charge a second slot.
                    var unknown =
                            new ForwardingTransactions(f.tx) {
                                public <T> T read(Function<RecoveryReadContext, T> action) {
                                    throw new Failure(
                                            Resolution.UNKNOWN,
                                            new SQLException("unavailable", "08006"));
                                }
                            };
                    var e = f.engine(f.participant, unknown, f.slots);
                    var id2 = f.seed(0);
                    var h = acquire(f, e, lease, id2);
                    assertEquals(Resolution.UNKNOWN, e.execute(h).resolution());
                    assertEquals(0, f.row(id2).get("attempt_count"));
                    assertEquals(1, e.settlePending());
                });
    }

    @Test
    void i3_c05_k() throws Exception {
        both(
                f -> {
                    var crashed =
                            new ForwardingTransactions(f.tx) {
                                public <T> T write(Supplier<T> action) {
                                    return f.tx.write(action);
                                }

                                public <T> T read(Function<RecoveryReadContext, T> action) {
                                    throw new AssertionError("process stops before cession");
                                }
                            };
                    var e = f.engine(f.participant, crashed, f.slots);
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var h = acquire(f, e, lease, id);
                    assertThrows(AssertionError.class, () -> e.execute(h));
                    assertEquals(0, f.row(id).get("attempt_count"));
                    assertEquals(1, f.capacity.snapshot().worker());
                    assertEquals(1, e.settlePending());
                    f.expire(id);
                    var restarted = f.engine();
                    completed(f, restarted, acquire(f, restarted, lease, id));
                });
    }

    @Test
    void i3_c05_l() throws Exception {
        both(
                f -> {
                    var e =
                            f.engine(
                                    wrap(
                                            f,
                                            p -> {
                                                f.expireLease(0);
                                                try {
                                                    f.threads
                                                            .submit(
                                                                    () ->
                                                                            f.engine()
                                                                                    .acquirePartition(
                                                                                            0)
                                                                                    .orElseThrow())
                                                            .get(8, TimeUnit.SECONDS);
                                                } catch (Exception error) {
                                                    throw new AssertionError(error);
                                                }
                                                throw new IllegalStateException(
                                                        "lost partition after confirmed fault");
                                            },
                                            p -> {}),
                                    f.tx,
                                    f.slots);
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var attempt = e.execute(acquire(f, e, lease, id));
                    assertEquals(1, attempt.cession().attemptCount());
                    assertTrue(e.acquire(lease, List.of(id)).isEmpty());
                });
    }

    @Test
    void i3_c05_m() throws Exception {
        both(
                f -> {
                    var e =
                            f.engine(
                                    preparationFailure(f, new IllegalStateException("fault")),
                                    f.tx,
                                    f.slots);
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var failure = e.execute(acquire(f, e, lease, id));
                    f.trace.add(
                            "attempt=1 physical="
                                    + failure.resolution()
                                    + " kind="
                                    + failure.failureKind()
                                    + " cession="
                                    + failure.cessionResolution());
                    assertEquals(Resolution.COMMITTED, failure.cessionResolution());
                    var good = f.engine();
                    var success = good.execute(acquire(f, good, lease, id));
                    f.trace.add(
                            "attempt=2 physical="
                                    + success.resolution()
                                    + " kind="
                                    + success.failureKind());
                    assertEquals(1, f.effects(id));
                    assertNull(success.cession());
                });
    }

    @Test
    void i3_failure_classification() throws Exception {
        both(
                f -> {
                    var lease = f.engine().acquirePartition(0).orElseThrow();
                    for (boolean countable : List.of(true, false)) {
                        var classifier =
                                (AttemptFailureClassifier)
                                        (cause ->
                                                countable
                                                        ? AttemptFailureClassifier.FailureKind
                                                                .TECHNICAL
                                                        : AttemptFailureClassifier.FailureKind
                                                                .NON_COUNTABLE);
                        var e =
                                new ConsumptionEngine(
                                        f.definition,
                                        f.tx,
                                        f.slots,
                                        preparationFailure(
                                                f,
                                                new IllegalStateException(
                                                        "same physical rollback, different actual"
                                                                + " cause policy")),
                                        f.ownership,
                                        f.settings,
                                        f.capacity,
                                        classifier);
                        var id = f.seed(0);
                        var result = e.execute(acquire(f, e, lease, id));
                        assertEquals(Resolution.ROLLED_BACK, result.resolution());
                        assertEquals(countable ? 1 : 0, f.row(id).get("attempt_count"));
                        assertEquals(countable, result.cession() != null);
                    }
                });
    }

    @Test
    void i3_cession_once() throws Exception {
        both(
                f -> {
                    var e = f.engine();
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var h = acquire(f, e, lease, id);
                    var start = new CountDownLatch(1);
                    var futures = new ArrayList<Future<Optional<Cession>>>();
                    for (int i = 0; i < 2; i++)
                        futures.add(
                                f.threads.submit(
                                        () -> {
                                            latch(start);
                                            return f.technical(
                                                    () ->
                                                            f.slots.cede(
                                                                    h.claim(), f.settings.retry()));
                                        }));
                    start.countDown();
                    int wins = 0;
                    for (var future : futures)
                        if (future.get(8, TimeUnit.SECONDS).isPresent()) wins++;
                    assertEquals(1, wins);
                    assertEquals(1, f.row(id).get("attempt_count"));
                    e.abandon(h);
                });
    }

    @Test
    void i3_quarantine_persists() throws Exception {
        both(
                f -> {
                    f.settings =
                            new LifecycleSettings(
                                    32,
                                    Duration.ofSeconds(30),
                                    Duration.ofSeconds(60),
                                    Duration.ofSeconds(10),
                                    Duration.ofMillis(500),
                                    Duration.ofSeconds(2),
                                    new LifecycleSettings.RetryPolicy(1, Duration.ZERO));
                    var bad =
                            f.engine(
                                    preparationFailure(f, new IllegalStateException("fault")),
                                    f.tx,
                                    f.slots);
                    var lease = bad.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    var h = acquire(f, bad, lease, id);
                    bad.execute(h);
                    var before = f.row(id);
                    var restarted = f.engine();
                    assertTrue(restarted.acquire(lease, List.of(id)).isEmpty());
                    assertTrue(restarted.renewClaims(List.of(h.claim())).isEmpty());
                    assertEquals(
                            Protection.STALE_OR_EXPIRED,
                            f.technical(() -> f.slots.protectFinal(h.claim())));
                    assertEquals(before, f.row(id));
                });
    }

    void quarantine(ConsumptionLifecyclePostgresFixture f, UUID id) {
        f.oracle.update(
                "update consumption_slot set"
                    + " state='QUARANTINED',attempt_count=3,claim_token=null,claimed_until=null,retry_after=null"
                    + " where consumable_id=?",
                id);
    }

    String reactivate(ConsumptionLifecyclePostgresFixture f, UUID id) throws Exception {
        return f.script(
                "reactivate-l2-slot.sql",
                f.adminRole,
                Map.of(
                        "consumption_id",
                        f.definition.id().value().toString(),
                        "consumable_id",
                        id.toString()));
    }

    @Test
    void i3_reactivate() throws Exception {
        both(
                f -> {
                    var e = f.engine();
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    quarantine(f, id);
                    assertTrue(reactivate(f, id).contains("UPDATE 1"));
                    assertEquals(0, f.row(id).get("attempt_count"));
                    assertEquals("AVAILABLE", f.row(id).get("state"));
                    for (String col : List.of("claim_token", "claimed_until", "retry_after"))
                        assertNull(f.row(id).get(col));
                    f.noEffects(id);
                    assertTrue(reactivate(f, id).contains("UPDATE 0"));
                    assertTrue(reactivate(f, new UUID(0, 99999)).contains("UPDATE 0"));
                    var h = acquire(f, e, lease, id);
                    var before = f.row(id);
                    assertTrue(reactivate(f, id).contains("UPDATE 0"));
                    assertEquals(before, f.row(id));
                    completed(f, e, h);
                });
    }

    @Test
    void i3_two_admins() throws Exception {
        both(
                f -> {
                    var id = f.seed(0);
                    quarantine(f, id);
                    var start = new CountDownLatch(1);
                    try (var lock = f.lock(id)) {
                        var a =
                                f.threads.submit(
                                        () -> {
                                            latch(start);
                                            return reactivate(f, id);
                                        });
                        var b =
                                f.threads.submit(
                                        () -> {
                                            latch(start);
                                            return reactivate(f, id);
                                        });
                        start.countDown();
                        f.await(
                                () ->
                                        f.oracle.queryForObject(
                                                        "select count(*) from pg_stat_activity"
                                                                + " where usename=? and"
                                                                + " wait_event_type='Lock'",
                                                        Integer.class,
                                                        f.adminRole)
                                                == 2);
                        f.trace.add(
                                "two administrative PIDs waiting under same slot lock at "
                                        + f.now());
                        lock.rollback();
                        var x = a.get(8, TimeUnit.SECONDS);
                        var y = b.get(8, TimeUnit.SECONDS);
                        assertEquals(
                                1,
                                (x.contains("UPDATE 1") ? 1 : 0)
                                        + (y.contains("UPDATE 1") ? 1 : 0));
                        assertEquals(
                                1,
                                (x.contains("UPDATE 0") ? 1 : 0)
                                        + (y.contains("UPDATE 0") ? 1 : 0));
                    }
                    f.noEffects(id);
                });
    }

    @Test
    void i3_reactivate_over_cap() throws Exception {
        both(
                f -> {
                    for (int i = 0; i < 64; i++) f.seed(0);
                    var id = f.seed(0);
                    quarantine(f, id);
                    assertTrue(reactivate(f, id).contains("UPDATE 1"));
                    assertEquals(
                            65,
                            f.oracle.queryForObject(
                                    "select count(*) from consumption_slot where"
                                            + " state<>'QUARANTINED'",
                                    Integer.class));
                    f.noEffects(id);
                });
    }

    @Test
    void i3_other_slot_progresses() throws Exception {
        both(
                f -> {
                    var e = f.engine();
                    var lease = e.acquirePartition(0).orElseThrow();
                    UUID q, next;
                    if (f.write) {
                        var create = f.seed(0);
                        completed(f, e, acquire(f, e, lease, create));
                        var pot =
                                f.oracle.queryForObject(
                                        "select pot_id from v3_command where request_id=?",
                                        UUID.class,
                                        create);
                        q =
                                f.seedCommand(
                                        0,
                                        pot,
                                        1,
                                        new com.kartaguez.pocoma.engine.write.pot.port.execution.v3
                                                .PotCommand.UpdatePotDetails(
                                                new com.kartaguez.pocoma.domain.pot.value.Label(
                                                        "quarantined intention")));
                        next =
                                f.seedCommand(
                                        0,
                                        pot,
                                        1,
                                        new com.kartaguez.pocoma.engine.write.pot.port.execution.v3
                                                .PotCommand.UpdatePotDetails(
                                                new com.kartaguez.pocoma.domain.pot.value.Label(
                                                        "later same Pot progresses")));
                    } else {
                        q = f.seed(0);
                        next = f.seed(0);
                    }
                    quarantine(f, q);
                    var before = f.row(q);
                    completed(f, e, acquire(f, e, lease, next));
                    var other = e.acquirePartition(1).orElseThrow();
                    completed(f, e, acquire(f, e, other, f.seed(1)));
                    assertEquals(before, f.row(q));
                    // A second canonical Consumption, explicitly technical (Identity
                    // producer/projection absent).
                    var id =
                            new ConsumptionId(
                                    UUID.fromString(
                                            f.write
                                                    ? "2999e75c-272d-5afe-a966-c9364ef021fe"
                                                    : "190a93c6-b8af-5ac7-8001-72e2ec53c2df"));
                    var d =
                            new ConsumptionDefinition(
                                    id,
                                    f.write
                                            ? ConsumableFamily.IDENTITY_REQUEST
                                            : ConsumableFamily.POT_EVENT,
                                    f.definition.authority());
                    f.oracle.execute(
                            "create table fixture_other_effect(id uuid primary key);grant"
                                    + " select,insert on fixture_other_effect to "
                                    + f.runtimeRole);
                    var key = new UUID(91, 1);
                    f.oracle.update(
                            "insert into"
                                + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                                + " select ?,?,h,((h::bigint%4+4)%4)::int from (select"
                                + " consumption_hash_v1(?,?) h)x",
                            id.value(), key, d.family().name(), key);
                    var participant =
                            new ConsumptionParticipant() {
                                public Optional<Prepared> prepare(UUID k) {
                                    return Optional.of(
                                            new Prepared() {
                                                public void finalizeIn(SlotCompletion completion) {
                                                    SpringAttemptTransactions.guarded(
                                                            f.source,
                                                            () -> {
                                                                f.sql.update(
                                                                        "insert into"
                                                                            + " fixture_other_effect"
                                                                            + " values (?)",
                                                                        k);
                                                                return null;
                                                            });
                                                    completion.complete();
                                                    SpringAttemptTransactions.closePublication(
                                                            f.source);
                                                }

                                                public void requireCompletionReady() {
                                                    assertEquals(
                                                            1,
                                                            f.sql.queryForObject(
                                                                    "select count(*) from"
                                                                        + " fixture_other_effect"
                                                                        + " where id=?",
                                                                    Integer.class,
                                                                    k));
                                                }
                                            });
                                }

                                public RecoveryObservation observe(UUID k, RecoveryReadContext c) {
                                    SpringAttemptTransactions.requireRead(f.source, c);
                                    return f.sql.queryForObject(
                                                            "select count(*) from"
                                                                    + " fixture_other_effect where"
                                                                    + " id=?",
                                                            Integer.class,
                                                            k)
                                                    == 1
                                            ? RecoveryObservation.COMPLETED
                                            : RecoveryObservation.NOT_COMPLETED;
                                }
                            };
                    var separate =
                            new ConsumptionEngine(
                                    d,
                                    f.tx,
                                    f.slots,
                                    participant,
                                    f.ownership,
                                    f.settings,
                                    new WorkerCapacity(4, Map.of(id, 2)),
                                    cause -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
                    int p =
                            f.oracle.queryForObject(
                                    "select partition_id from consumption_slot where"
                                            + " consumption_id=? and consumable_id=?",
                                    Integer.class,
                                    id.value(),
                                    key);
                    var l = separate.acquirePartition(p).orElseThrow();
                    assertEquals(
                            ConsumptionEngine.Status.COMMITTED,
                            separate.execute(separate.acquire(l, List.of(key)).getFirst())
                                    .status());
                    assertEquals(
                            1,
                            f.oracle.queryForObject(
                                    "select count(*) from fixture_other_effect", Integer.class));
                    assertEquals(before, f.row(q));
                    f.trace.add(
                            "Q persisted; same Pot/Consumption later command and other partition"
                                    + " progressed; second C technical effect=1 (no real"
                                    + " Identity/projection claim)");
                });
    }

    @Test
    void i3_retry_config_prospective() throws Exception {
        both(
                f -> {
                    var e = f.engine();
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    f.oracle.update(
                            "update consumption_slot set"
                                    + " attempt_count=2,retry_after=clock_timestamp()+interval '1"
                                    + " second' where consumable_id=?",
                            id);
                    var before = f.row(id);
                    for (int n : List.of(1, 3, 6)) {
                        f.settings =
                                new LifecycleSettings(
                                        32,
                                        Duration.ofSeconds(30),
                                        Duration.ofSeconds(60),
                                        Duration.ofSeconds(10),
                                        Duration.ofMillis(500),
                                        Duration.ofSeconds(2),
                                        new LifecycleSettings.RetryPolicy(
                                                n, Duration.ofSeconds(n)));
                        f.engine();
                        assertEquals(before, f.row(id));
                    }
                    f.await(
                            () ->
                                    !f.now()
                                            .isBefore(
                                                    ((Timestamp) before.get("retry_after"))
                                                            .toInstant()));
                    f.settings =
                            new LifecycleSettings(
                                    32,
                                    Duration.ofSeconds(30),
                                    Duration.ofSeconds(60),
                                    Duration.ofSeconds(10),
                                    Duration.ofMillis(500),
                                    Duration.ofSeconds(2),
                                    new LifecycleSettings.RetryPolicy(1, Duration.ZERO));
                    var bad =
                            f.engine(
                                    preparationFailure(
                                            f,
                                            new IllegalStateException(
                                                    "next failure uses new pair")),
                                    f.tx,
                                    f.slots);
                    assertEquals(
                            "QUARANTINED",
                            bad.execute(acquire(f, bad, lease, id)).cession().state());
                    assertEquals(3, f.row(id).get("attempt_count"));
                    f.settings = LifecycleSettings.pilot();
                    assertTrue(f.engine().acquire(lease, List.of(id)).isEmpty());
                    assertEquals("QUARANTINED", f.row(id).get("state"));
                });
    }

    void wire(ConsumptionLifecyclePostgresFixture f, String phase) throws Exception {
        for (var fault :
                List.of(
                        CommitAcknowledgementProxy.Fault.BEFORE_COMMIT,
                        CommitAcknowledgementProxy.Fault.AFTER_COMMIT)) {
            try (var proxy = new CommitAcknowledgementProxy(PG.getHost(), PG.getMappedPort(5432))) {
                var source =
                        new DriverManagerDataSource(
                                "jdbc:postgresql://"
                                        + PG.getHost()
                                        + ":"
                                        + proxy.localPort()
                                        + "/"
                                        + f.database
                                        + "?currentSchema="
                                        + f.schema
                                        + "&sslmode=disable&preferQueryMode=simple&socketTimeout=15",
                                f.runtimeRole,
                                "i3-fixture");
                org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean emf = null;
                org.springframework.transaction.PlatformTransactionManager manager;
                if (f.write) {
                    emf = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
                    emf.setDataSource(source);
                    emf.setPackagesToScan(
                            "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity");
                    emf.setJpaVendorAdapter(
                            new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
                    emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
                    emf.afterPropertiesSet();
                    var jpa =
                            new org.springframework.orm.jpa.JpaTransactionManager(emf.getObject());
                    jpa.setDataSource(source);
                    jpa.setNestedTransactionAllowed(false);
                    manager = jpa;
                } else manager = ConsumptionLifecyclePostgresFixture.jdbcManager(source);
                try {
                    var owner = new SpringAttemptTransactions(source, manager);
                    var sqlSlots = new JdbcConsumptionSlotStore(source);
                    var leases = new JdbcPartitionOwnershipStore(source);
                    var writes = new AtomicInteger();
                    var armed = new AtomicBoolean();
                    var forwarding =
                            new ForwardingTransactions(owner) {
                                public <T> T write(Supplier<T> action) {
                                    int n = writes.incrementAndGet();
                                    if (armed.get()
                                            && (phase.equals("acquisition")
                                                    || phase.equals("final") && n == 1
                                                    || phase.equals("cession") && n == 1)) {
                                        proxy.armed.set(fault);
                                        armed.set(false);
                                    }
                                    return owner.write(action);
                                }
                            };
                    var business = f.participantFor(source, owner);
                    if (phase.equals("cession"))
                        business =
                                preparationFailure(
                                        f, new IllegalStateException("fault before cession"));
                    var e =
                            new ConsumptionEngine(
                                    f.definition,
                                    forwarding,
                                    sqlSlots,
                                    business,
                                    leases,
                                    f.settings,
                                    f.capacity,
                                    c -> AttemptFailureClassifier.FailureKind.TECHNICAL);
                    f.expireLease(0);
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    if (phase.equals("acquisition")) {
                        writes.set(0);
                        armed.set(true);
                        var failure =
                                assertThrows(Failure.class, () -> e.acquire(lease, List.of(id)));
                        assertEquals(Resolution.UNKNOWN, failure.resolution());
                        f.noEffects(id);
                        assertEquals(0, f.row(id).get("attempt_count"));
                        assertEquals(1, f.capacity.snapshot().worker());
                        assertEquals(1, e.settlePending());
                        assertEquals(0, f.capacity.snapshot().worker());
                        assertEquals(
                                fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT,
                                f.row(id).get("claim_token") != null);
                    } else {
                        var h = acquire(f, e, lease, id);
                        writes.set(0);
                        armed.set(true);
                        var result = e.execute(h);
                        assertEquals(
                                phase.equals("final") ? Resolution.UNKNOWN : Resolution.ROLLED_BACK,
                                result.resolution());
                        if (phase.equals("final")) {
                            f.await(
                                    () ->
                                            f.effects(id)
                                                    == (fault
                                                                    == CommitAcknowledgementProxy
                                                                            .Fault.AFTER_COMMIT
                                                            ? 1
                                                            : 0));
                            assertEquals(1, e.settlePending());
                        } else {
                            assertEquals(Resolution.UNKNOWN, result.cessionResolution());
                            f.noEffects(id);
                            assertEquals(
                                    fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT ? 1 : 0,
                                    f.row(id).get("attempt_count"));
                            assertEquals(1, e.settlePending());
                        }
                        assertEquals(0, f.capacity.snapshot().worker());
                    }
                    assertEquals(1, proxy.intercepted.get());
                    assertEquals(
                            fault == CommitAcknowledgementProxy.Fault.AFTER_COMMIT ? 1 : 0,
                            proxy.committedReplies.get());
                    f.trace.add(
                            "WIRE VARIANT PASS phase="
                                    + phase
                                    + " fault="
                                    + fault
                                    + " server commit replies="
                                    + proxy.committedReplies.get()
                                    + " external effects="
                                    + f.effects(id));
                } finally {
                    if (emf != null) emf.destroy();
                }
            }
        }
    }

    void unresolvedPhysicalFinalization(ConsumptionLifecyclePostgresFixture f) throws Exception {
        var arm = new AtomicBoolean();
        var physical = new AtomicReference<Connection>();
        var source =
                new DelegatingDataSource(f.source) {
                    @Override
                    public Connection getConnection() throws SQLException {
                        var actual = super.getConnection();
                        var uncertain = new AtomicBoolean();
                        return (Connection)
                                Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class<?>[] {Connection.class},
                                        (proxy, method, args) -> {
                                            if (method.getName().equals("commit")
                                                    && arm.compareAndSet(true, false)) {
                                                uncertain.set(true);
                                                physical.set(actual);
                                                throw new SQLException(
                                                        "commit transport lost; server left"
                                                                + " unresolved",
                                                        "08006");
                                            }
                                            if (uncertain.get()) {
                                                if (method.getName().equals("rollback"))
                                                    throw new SQLException(
                                                            "rollback ACK unavailable", "08006");
                                                if (method.getName().equals("close")
                                                        || method.getName().equals("setAutoCommit")
                                                        || method.getName().equals("abort"))
                                                    return null;
                                            }
                                            try {
                                                return method.invoke(actual, args);
                                            } catch (InvocationTargetException e) {
                                                throw e.getCause();
                                            }
                                        });
                    }
                };
        org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean emf = null;
        org.springframework.transaction.PlatformTransactionManager manager;
        if (f.write) {
            emf = new org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean();
            emf.setDataSource(source);
            emf.setPackagesToScan("com.kartaguez.pocoma.infra.persistence.primary.jpa.entity");
            emf.setJpaVendorAdapter(
                    new org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter());
            emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none"));
            emf.afterPropertiesSet();
            var jpa = new org.springframework.orm.jpa.JpaTransactionManager(emf.getObject());
            jpa.setDataSource(source);
            jpa.setNestedTransactionAllowed(false);
            manager = jpa;
        } else manager = ConsumptionLifecyclePostgresFixture.jdbcManager(source);
        try {
            var real = new SpringAttemptTransactions(source, manager);
            var enable = new AtomicBoolean();
            var tx =
                    new ForwardingTransactions(real) {
                        public <T> T write(Supplier<T> action) {
                            if (enable.compareAndSet(true, false)) arm.set(true);
                            return real.write(action);
                        }
                    };
            var capacity = new WorkerCapacity(1, Map.of(f.definition.id(), 1));
            var e =
                    new ConsumptionEngine(
                            f.definition,
                            tx,
                            new JdbcConsumptionSlotStore(source),
                            f.participantFor(source, real),
                            new JdbcPartitionOwnershipStore(source),
                            f.settings,
                            capacity,
                            c -> AttemptFailureClassifier.FailureKind.TECHNICAL);
            f.expireLease(0);
            var lease = e.acquirePartition(0).orElseThrow();
            var id = f.seed(0);
            var h = acquire(f, e, lease, id);
            f.oracle.update(
                    "update consumption_slot set claimed_until=clock_timestamp()+interval '300"
                            + " milliseconds' where consumable_id=?",
                    id);
            var deadline = ((Timestamp) f.row(id).get("claimed_until")).toInstant();
            enable.set(true);
            var result = e.execute(h);
            assertEquals(Resolution.UNKNOWN, result.resolution());
            assertNotNull(physical.get());
            f.noEffects(id);
            assertEquals(1, capacity.snapshot().worker());
            f.await(() -> f.now().isAfter(deadline));
            assertEquals(0, e.settlePending());
            assertTrue(e.acquire(lease, List.of(f.seed(0))).isEmpty());
            f.trace.add(
                    "local Engine exited; physical final transaction still open, uncommitted"
                            + " DELETE/expected effects; capacity="
                            + capacity.snapshot()
                            + " DB="
                            + f.now());
            // Explicit physical rollback ends protection. Expiration alone was never an oracle.
            physical.get().rollback();
            physical.get().close();
            physical.set(null);
            var start = new CountDownLatch(1);
            var a =
                    f.threads.submit(
                            () -> {
                                latch(start);
                                return e.settlePending();
                            });
            var b =
                    f.threads.submit(
                            () -> {
                                latch(start);
                                return e.settlePending();
                            });
            start.countDown();
            assertEquals(1, a.get(8, TimeUnit.SECONDS) + b.get(8, TimeUnit.SECONDS));
            assertEquals(0, capacity.snapshot().worker());
            assertEquals(0, f.row(id).get("attempt_count"));
            f.noEffects(id);
            // PostgreSQL has resolved, but the local callback remains active: still no release.
            var entered = new CountDownLatch(1);
            var finish = new CountDownLatch(1);
            var exited = new CountDownLatch(1);
            var participant =
                    new ConsumptionParticipant() {
                        public Optional<Prepared> prepare(UUID key) {
                            entered.countDown();
                            boolean done = false;
                            while (!done)
                                try {
                                    finish.await();
                                    done = true;
                                } catch (InterruptedException ignored) {
                                }
                            throw new IllegalStateException("local callback exits later");
                        }

                        public RecoveryObservation observe(UUID key, RecoveryReadContext c) {
                            return f.participantFor(source, real).observe(key, c);
                        }
                    };
            var active =
                    new ConsumptionEngine(
                            f.definition,
                            real,
                            new JdbcConsumptionSlotStore(source),
                            participant,
                            new JdbcPartitionOwnershipStore(source),
                            f.settings,
                            capacity,
                            c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
            var h2 = acquire(f, active, lease, id);
            var task =
                    f.threads.submit(
                            () -> {
                                try {
                                    return active.execute(h2);
                                } finally {
                                    exited.countDown();
                                }
                            });
            latch(entered);
            try {
                task.cancel(true);
                assertEquals(0, active.settlePending());
                assertEquals(1, capacity.snapshot().worker());
                assertTrue(active.acquire(lease, List.of(id)).isEmpty());
                f.trace.add(
                        "claim transaction resolved; local callback active despite cancellation,"
                                + " capacity retained");
            } finally {
                finish.countDown();
            }
            latch(exited);
            assertEquals(0, capacity.snapshot().worker());
            assertEquals(0, f.row(id).get("attempt_count"));
        } finally {
            if (physical.get() != null) {
                physical.get().rollback();
                physical.get().close();
            }
            if (emf != null) emf.destroy();
        }
    }

    void resolvedTransactionBeforeLocalExit(ConsumptionLifecyclePostgresFixture f)
            throws Exception {
        var resolved = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        var exited = new CountDownLatch(1);
        var once = new AtomicBoolean(true);
        var pid = new AtomicInteger();
        var owner =
                new ForwardingTransactions(f.tx) {
                    public <T> T read(Function<RecoveryReadContext, T> action) {
                        var result =
                                f.tx.read(
                                        context -> {
                                            pid.set(
                                                    f.sql.queryForObject(
                                                            "select pg_backend_pid()",
                                                            Integer.class));
                                            return action.apply(context);
                                        });
                        if (once.compareAndSet(true, false)) {
                            resolved.countDown();
                            boolean done = false;
                            while (!done)
                                try {
                                    finish.await();
                                    done = true;
                                } catch (InterruptedException ignored) {
                                }
                        }
                        return result;
                    }
                };
        var capacity = new WorkerCapacity(1, Map.of(f.definition.id(), 1));
        var e =
                new ConsumptionEngine(
                        f.definition,
                        owner,
                        f.slots,
                        f.participant,
                        f.ownership,
                        f.settings,
                        capacity,
                        c -> AttemptFailureClassifier.FailureKind.TECHNICAL);
        f.expireLease(0);
        var lease = e.acquirePartition(0).orElseThrow();
        var id = f.seed(0);
        var h = acquire(f, e, lease, id);
        var task =
                f.threads.submit(
                        () -> {
                            try {
                                return e.execute(h);
                            } finally {
                                exited.countDown();
                            }
                        });
        latch(resolved);
        try {
            assertFalse(
                    f.oracle.queryForObject(
                            "select exists(select 1 from pg_stat_activity where pid=? and"
                                + " xact_start is not null)",
                            Boolean.class,
                            pid.get()));
            assertEquals(0, e.settlePending());
            task.cancel(true);
            assertEquals(1, capacity.snapshot().worker());
            assertEquals(0, e.settlePending());
            f.noEffects(id);
            f.trace.add(
                    "physical preparation transaction resolved; local continuation can still start"
                        + " business callback; ticket retained until actual exit");
        } finally {
            finish.countDown();
        }
        latch(exited);
        assertEquals(0, capacity.snapshot().worker());
        assertEquals(1, f.effects(id));
    }

    void absentRequiresCoherentR9(ConsumptionLifecyclePostgresFixture f) {
        var coherent = new AtomicBoolean();
        var armed = new AtomicBoolean();
        var tx =
                new ForwardingTransactions(f.tx) {
                    public <T> T write(Supplier<T> action) {
                        var result = f.tx.write(action);
                        if (armed.compareAndSet(true, false))
                            throw new Failure(
                                    Resolution.UNKNOWN,
                                    new SQLException("commit ACK lost", "08006"));
                        return result;
                    }
                };
        var business =
                new ConsumptionParticipant() {
                    public Optional<Prepared> prepare(UUID id) {
                        return f.participant.prepare(id);
                    }

                    public RecoveryObservation observe(UUID id, RecoveryReadContext context) {
                        return coherent.get()
                                ? f.participant.observe(id, context)
                                : RecoveryObservation.INCONSISTENT;
                    }
                };
        var capacity = new WorkerCapacity(1, Map.of(f.definition.id(), 1));
        var e =
                new ConsumptionEngine(
                        f.definition,
                        tx,
                        f.slots,
                        business,
                        f.ownership,
                        f.settings,
                        capacity,
                        c -> AttemptFailureClassifier.FailureKind.TECHNICAL);
        f.expireLease(0);
        var lease = e.acquirePartition(0).orElseThrow();
        var id = f.seed(0);
        var h = acquire(f, e, lease, id);
        armed.set(true);
        assertEquals(Resolution.UNKNOWN, e.execute(h).resolution());
        assertEquals(1, f.effects(id));
        assertEquals(0, e.settlePending());
        assertEquals(1, capacity.snapshot().worker());
        coherent.set(true);
        assertEquals(1, e.settlePending());
        assertEquals(0, e.settlePending());
        assertEquals(0, capacity.snapshot().worker());
        f.trace.add("absent slot did not release until coherent business R9; no replay/no budget");
    }

    @Test
    void i3_capacity_uncertainty() throws Exception {
        both(
                f -> {
                    var capacity = new WorkerCapacity(1, Map.of(f.definition.id(), 1));
                    var failureMode = new AtomicBoolean();
                    var e =
                            new ConsumptionEngine(
                                    f.definition,
                                    new ForwardingTransactions(f.tx) {
                                        public <T> T write(Supplier<T> action) {
                                            if (failureMode.get())
                                                throw new Failure(
                                                        Resolution.UNKNOWN,
                                                        new SQLException(
                                                                "uncertain server transaction",
                                                                "08006"));
                                            return f.tx.write(action);
                                        }
                                    },
                                    f.slots,
                                    f.participant,
                                    f.ownership,
                                    f.settings,
                                    capacity,
                                    c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE);
                    var lease = e.acquirePartition(0).orElseThrow();
                    var id = f.seed(0);
                    try (var lock = f.lock(id)) {
                        failureMode.set(true);
                        assertThrows(Failure.class, () -> e.acquire(lease, List.of(id)));
                        failureMode.set(false);
                        assertEquals(1, capacity.snapshot().worker());
                        assertEquals(0, e.settlePending());
                        assertTrue(e.acquire(lease, List.of(id)).isEmpty());
                        f.trace.add(
                                "local acquisition returned UNKNOWN, existing slot protection"
                                        + " unresolved; capacity retained="
                                        + capacity.snapshot());
                        lock.rollback();
                    }
                    var start = new CountDownLatch(1);
                    var a =
                            f.threads.submit(
                                    () -> {
                                        latch(start);
                                        return e.settlePending();
                                    });
                    var b =
                            f.threads.submit(
                                    () -> {
                                        latch(start);
                                        return e.settlePending();
                                    });
                    start.countDown();
                    assertEquals(1, a.get(8, TimeUnit.SECONDS) + b.get(8, TimeUnit.SECONDS));
                    assertEquals(0, capacity.snapshot().worker());
                    wire(f, "acquisition");
                    unresolvedPhysicalFinalization(f);
                    absentRequiresCoherentR9(f);
                    resolvedTransactionBeforeLocalExit(f);
                });
    }

    @Test
    void i3_sql_costs() throws Exception {
        both(
                f -> {
                    for (int size : List.of(100, 1000, 10000)) {
                        f.oracle.update(
                                "insert into"
                                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                                    + " select ?,id,h,p from (select id,h,p,row_number()"
                                    + " over(partition by p order by id) n from (select"
                                    + " id,h,((h::bigint%4+4)%4)::int p from (select"
                                    + " id,consumption_hash_v1(?,id) h from (select"
                                    + " md5((?::text||':'||g::text))::uuid id from"
                                    + " generate_series(1,?) g)x)y)z)ranked where n<=?",
                                f.definition.id().value(),
                                f.definition.family().name(),
                                size,
                                size * 8,
                                size);
                        assertEquals(
                                4 * size,
                                f.oracle.queryForObject(
                                        "select count(*) from consumption_slot", Integer.class));
                        // Controlled mix: active, expired, retry, Q and free, with identical
                        // bounded SQL shapes.
                        f.oracle.update(
                                "update consumption_slot set"
                                    + " claim_token='00000000-0000-0000-0000-000000000081',claimed_until=clock_timestamp()+interval"
                                    + " '60 seconds' where"
                                    + " get_byte(uuid_send(consumable_id),15)%5=0");
                        f.oracle.update(
                                "update consumption_slot set"
                                    + " claim_token='00000000-0000-0000-0000-000000000082',claimed_until=clock_timestamp()-interval"
                                    + " '1 second' where"
                                    + " get_byte(uuid_send(consumable_id),15)%5=1");
                        f.oracle.update(
                                "update consumption_slot set retry_after=clock_timestamp()+interval"
                                        + " '60 seconds' where"
                                        + " get_byte(uuid_send(consumable_id),15)%5=2");
                        f.oracle.update(
                                "update consumption_slot set state='QUARANTINED',attempt_count=3"
                                        + " where get_byte(uuid_send(consumable_id),15)%5=3");
                        f.oracle.execute("analyze consumption_slot");
                        var keys =
                                f.oracle.queryForList(
                                        "select consumable_id from consumption_slot where"
                                                + " partition_id=0 order by consumable_id limit 32",
                                        UUID.class);
                        String values =
                                keys.stream()
                                        .map(k -> "'" + k + "'::uuid")
                                        .collect(java.util.stream.Collectors.joining(","));
                        String c = "'" + f.definition.id().value() + "'::uuid";
                        var selection =
                                f.oracle.queryForList(
                                        "explain (analyze,buffers,wal,format json) select"
                                                + " consumable_id from consumption_slot where"
                                                + " consumption_id="
                                                + c
                                                + " and partition_id=0 and consumable_id in ("
                                                + values
                                                + ") order by consumable_id for update skip locked",
                                        String.class);
                        f.trace.add(
                                "COST exact slots/partition="
                                        + size
                                        + " B=32 mix="
                                        + f.oracle.queryForList(
                                                "select state,count(*) from consumption_slot group"
                                                        + " by state")
                                        + " selection="
                                        + selection);
                        try (var connection = f.admin.getConnection()) {
                            connection.setAutoCommit(false);
                            try (var sql = connection.createStatement()) {
                                for (String operation :
                                        List.of(
                                                "update consumption_slot set"
                                                    + " claim_token='00000000-0000-0000-0000-000000000083',claimed_until=clock_timestamp()+interval"
                                                    + " '60 seconds' where consumption_id="
                                                        + c
                                                        + " and consumable_id in ("
                                                        + values
                                                        + ") and state='AVAILABLE' and (retry_after"
                                                        + " is null or"
                                                        + " retry_after<=clock_timestamp()) and"
                                                        + " (claim_token is null or"
                                                        + " claimed_until<=clock_timestamp())",
                                                "update consumption_slot set"
                                                    + " claimed_until=greatest(claimed_until,clock_timestamp()+interval"
                                                    + " '120 seconds') where consumption_id="
                                                        + c
                                                        + " and consumable_id in ("
                                                        + values
                                                        + ") and state='AVAILABLE' and"
                                                        + " claim_token='00000000-0000-0000-0000-000000000083'"
                                                        + " and claimed_until>clock_timestamp()",
                                                "update consumption_slot set"
                                                    + " attempt_count=attempt_count+1,claim_token=null,claimed_until=null,retry_after=clock_timestamp()+interval"
                                                    + " '30 seconds' where consumption_id="
                                                        + c
                                                        + " and consumable_id='"
                                                        + keys.getFirst()
                                                        + "'::uuid and"
                                                        + " claim_token='00000000-0000-0000-0000-000000000083'"
                                                        + " and state='AVAILABLE' and"
                                                        + " claimed_until>clock_timestamp()")) {
                                    try (var rs =
                                            sql.executeQuery(
                                                    "explain (analyze,buffers,wal,format json) "
                                                            + operation)) {
                                        rs.next();
                                        f.trace.add(
                                                "COST DML probe SQL="
                                                        + operation
                                                        + " PLAN="
                                                        + rs.getString(1));
                                    }
                                }
                            } finally {
                                connection.rollback();
                            }
                        }
                        f.oracle.update(
                                "delete from consumption_slot"); // Disposable benchmark fixture
                        // only; no productive/admin purge
                        // entry point.
                    }
                });
    }
}
