package com.kartaguez.pocoma.infra.persistence.primary.jpa.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.consumption.*;
import com.kartaguez.pocoma.port.transaction.v3.*;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.*;
import java.util.*;
import java.util.function.*;

@Testcontainers
class SlotNavigationPostgresTest {
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
    void method(TestInfo info) {
        method = info.getTestMethod().orElseThrow().getName();
    }

    interface Scenario {
        void run(ConsumptionLifecyclePostgresFixture f) throws Exception;
    }

    void both(Scenario scenario) throws Exception {
        for (boolean write : List.of(true, false))
            try (var f = new ConsumptionLifecyclePostgresFixture(PG, write)) {
                try {
                    scenario.run(f);
                    f.trace.add("VARIANT PASS " + method + " " + (write ? "WRITE" : "READ"));
                } finally {
                    f.snapshot("external final vector");
                    var path =
                            Path.of(
                                    "target/v3-i5-evidence/"
                                            + method
                                            + "-"
                                            + (write ? "WRITE" : "READ")
                                            + ".txt");
                    Files.createDirectories(path.getParent());
                    Files.write(path, f.trace);
                }
            }
    }

    SlotKeyNavigation.Page page(
            ConsumptionLifecyclePostgresFixture f, int p, SlotKeyNavigation.Walk walk, int limit) {
        return f.tx.read(
                c ->
                        new JdbcSlotKeyNavigation(f.source, f.settings)
                                .readPage(f.definition.id(), p, walk, limit, c));
    }

    List<UUID> raw(ConsumptionLifecyclePostgresFixture f, int p) {
        return f.oracle.queryForList(
                "select consumable_id from consumption_slot where consumption_id=? and"
                        + " partition_id=? order by consumable_id",
                UUID.class,
                f.definition.id().value(),
                p);
    }

    void insert(ConsumptionLifecyclePostgresFixture f, UUID id) {
        f.oracle.update(
                "insert into"
                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                    + " select ?,?,h,((h::bigint%4+4)%4)::int from (select consumption_hash_v1(?,?)"
                    + " h) x",
                f.definition.id().value(), id, f.definition.family().name(), id);
    }

    ConsumptionEngine engine(ConsumptionLifecyclePostgresFixture f, AttemptTransactions control) {
        return new ConsumptionEngine(
                f.definition,
                f.tx,
                f.slots,
                f.participant,
                control,
                f.slots,
                f.ownership,
                f.settings,
                f.capacity,
                c -> AttemptFailureClassifier.FailureKind.NON_COUNTABLE,
                new JdbcSlotKeyNavigation(f.source, f.settings),
                ConsumptionObservation.noop());
    }

    @Test
    void i5_n01() throws Exception {
        both(
                f -> {
                    for (long hi : List.of(0L, Long.MAX_VALUE, Long.MIN_VALUE, -1L))
                        for (long lo = 1; lo < 17; lo++) insert(f, new UUID(hi, lo));
                    for (int p = 0; p < 4; p++) {
                        var expected = raw(f, p);
                        var actual = new ArrayList<UUID>();
                        var walk = SlotKeyNavigation.Walk.fresh();
                        while (true) {
                            var result = page(f, p, walk, 3);
                            if (result.keys().isEmpty()) break;
                            actual.addAll(result.keys());
                            walk =
                                    new SlotKeyNavigation.Walk(
                                            Optional.of(result.keys().getLast()),
                                            result.upperInclusive());
                        }
                        assertEquals(expected, actual);
                        assertEquals(
                                expected,
                                expected.stream().sorted(SlotKeyNavigation::compare).toList());
                        f.trace.add("p=" + p + " orderedRaw=" + actual);
                    }
                });
    }

    @Test
    void i5_n02() throws Exception {
        both(
                f -> {
                    var ids = new ArrayList<UUID>();
                    for (int i = 0; i < 6; i++) ids.add(f.seed(0));
                    var e = engine(f, f.tx);
                    var lease = e.acquirePartition(0).orElseThrow();
                    var active = e.acquire(lease, List.of(ids.get(2))).claims().getFirst();
                    e.abandon(active);
                    var expired = e.acquire(lease, List.of(ids.get(3))).claims().getFirst();
                    e.abandon(expired);
                    f.expire(ids.get(3));
                    f.oracle.update(
                            "update consumption_slot set retry_after=clock_timestamp()+interval '1"
                                    + " hour' where consumable_id=?",
                            ids.get(4));
                    f.oracle.update(
                            "update consumption_slot set state='QUARANTINED' where consumable_id=?",
                            ids.get(5));
                    try (var blocker = f.admin.getConnection()) {
                        blocker.setAutoCommit(false);
                        try (var q =
                                blocker.prepareStatement(
                                        "select * from consumption_slot where consumable_id=? for"
                                                + " update")) {
                            q.setObject(1, ids.get(1));
                            q.executeQuery().close();
                        }
                        assertEquals(ids, page(f, 0, SlotKeyNavigation.Walk.fresh(), 32).keys());
                        var got = e.acquire(lease, ids);
                        assertEquals(2, got.examinedCount());
                        assertEquals(1, got.claims().size());
                        got.claims().forEach(e::abandon);
                        blocker.rollback();
                    }
                    var revisited = e.acquire(lease, List.of(ids.get(1), ids.get(3)));
                    assertEquals(2, revisited.claims().size());
                    revisited.claims().forEach(e::abandon);
                    assertEquals("QUARANTINED", f.row(ids.get(5)).get("state"));
                    f.noEffects(ids.get(4));
                });
    }

    @Test
    void i5_n03() throws Exception {
        both(
                f -> {
                    for (long n = 20; n < 100; n++) insert(f, new UUID(0, n));
                    var first = page(f, 0, SlotKeyNavigation.Walk.fresh(), 1);
                    var upper = first.upperInclusive();
                    var after = first.keys().getLast();
                    for (long n = 1; n < 20; n++) insert(f, new UUID(0, n));
                    for (long n = 100; n < 120; n++) insert(f, new UUID(0, n));
                    var next =
                            page(f, 0, new SlotKeyNavigation.Walk(Optional.of(after), upper), 32);
                    assertEquals(upper, next.upperInclusive());
                    assertTrue(
                            next.keys().stream()
                                    .allMatch(
                                            k ->
                                                    SlotKeyNavigation.compare(k, after) > 0
                                                            && SlotKeyNavigation.compare(
                                                                            k, upper.orElseThrow())
                                                                    <= 0));
                    var fresh = page(f, 0, SlotKeyNavigation.Walk.fresh(), 32);
                    assertTrue(SlotKeyNavigation.compare(fresh.keys().getFirst(), after) < 0);
                    assertTrue(
                            SlotKeyNavigation.compare(
                                            fresh.upperInclusive().orElseThrow(),
                                            upper.orElseThrow())
                                    > 0);
                });
    }

    @Test
    void i5_n04() throws Exception {
        both(
                f -> {
                    var id = f.seed(0);
                    var result = page(f, 0, SlotKeyNavigation.Walk.fresh(), 32);
                    var e = engine(f, f.tx);
                    var lease = e.acquirePartition(0).orElseThrow();
                    var h = e.acquire(lease, List.of(id)).claims().getFirst();
                    assertEquals(ConsumptionEngine.Status.COMMITTED, e.execute(h).status());
                    assertTrue(
                            page(
                                            f,
                                            0,
                                            new SlotKeyNavigation.Walk(
                                                    Optional.of(id), result.upperInclusive()),
                                            32)
                                    .keys()
                                    .isEmpty());
                    assertTrue(
                            page(f, 0, SlotKeyNavigation.Walk.fresh(), 32)
                                    .upperInclusive()
                                    .isEmpty());
                    assertEquals(0, f.capacity.snapshot().worker());
                });
    }

    @Test
    void i5_n05() throws Exception {
        both(
                f -> {
                    var ids = List.of(f.seed(0), f.seed(0), f.seed(0), f.seed(0));
                    var e = engine(f, f.tx);
                    var lease = e.acquirePartition(0).orElseThrow();
                    var got = e.acquire(lease, ids);
                    assertEquals(2, got.examinedCount());
                    assertEquals(2, got.claims().size());
                    assertEquals(0, e.acquire(lease, ids.subList(2, 4)).examinedCount());
                    assertNull(f.row(ids.get(2)).get("claim_token"));
                    got.claims().forEach(e::abandon);
                    f.expireLease(0);
                    assertEquals(0, e.acquire(lease, ids).examinedCount());
                    assertEquals(0, f.capacity.snapshot().worker());
                });
    }

    @Test
    void i5_n06() throws Exception {
        both(
                f -> {
                    var id = f.seed(0);
                    var baseline = engine(f, f.tx);
                    var lease = baseline.acquirePartition(0).orElseThrow();
                    for (var resolution :
                            List.of(
                                    AttemptTransactions.Resolution.COMMITTED,
                                    AttemptTransactions.Resolution.UNKNOWN)) {
                        var loseOnce = new java.util.concurrent.atomic.AtomicBoolean(true);
                        var lost =
                                new AttemptTransactions() {
                                    public <T> T read(Function<RecoveryReadContext, T> action) {
                                        return f.tx.read(action);
                                    }

                                    public <T> T write(Supplier<T> action) {
                                        var result = f.tx.write(action);
                                        if (loseOnce.getAndSet(false))
                                            throw new Failure(
                                                    resolution,
                                                    new IllegalStateException(
                                                            "lost callback result after physical"
                                                                    + " commit"));
                                        return result;
                                    }

                                    public void poison(Throwable failure) {
                                        f.tx.poison(failure);
                                    }

                                    public void requirePublicationClosed() {
                                        f.tx.requirePublicationClosed();
                                    }
                                };
                        var e = engine(f, lost);
                        assertThrows(
                                AttemptTransactions.Failure.class,
                                () -> e.acquire(lease, List.of(id)));
                        assertNotNull(f.row(id).get("claim_token"));
                        assertEquals(1, f.capacity.snapshot().worker());
                        f.noEffects(id);
                        // The callback result was lost: no executable handle or cursor prefix
                        // escaped.
                        // Normal technical inspection resolves that reservation, never a
                        // reconstructed execute.
                        assertEquals(1, e.settlePending());
                        assertEquals(0, f.capacity.snapshot().worker());
                        f.expire(id);
                    }
                });
    }

    @Test
    void i5_n07() throws Exception {
        both(
                f -> {
                    String cid = f.definition.id().value().toString(),
                            family = f.definition.family().name();
                    var cpu = java.lang.management.ManagementFactory.getThreadMXBean();
                    var allocated =
                            cpu instanceof com.sun.management.ThreadMXBean bean ? bean : null;
                    if (allocated != null && allocated.isThreadAllocatedMemorySupported())
                        allocated.setThreadAllocatedMemoryEnabled(true);
                    for (int size : List.of(100, 1000, 10000)) {
                        f.oracle.update("delete from consumption_slot");
                        f.oracle.execute(
                                "insert into"
                                    + " consumption_slot(consumption_id,consumable_id,partition_hash,partition_id)"
                                    + " select '"
                                        + cid
                                        + "'::uuid,id,h,p from (select *,row_number()"
                                        + " over(partition by p order by id) n from (select"
                                        + " id,h,((h::bigint%4+4)%4)::int p from(select"
                                        + " id,consumption_hash_v1('"
                                        + family
                                        + "',id) h from(select md5('i5-cost/'||n)::uuid id from"
                                        + " generate_series(1,"
                                        + (size * 6)
                                        + ") n) u) h) p) ranked where n<="
                                        + size);
                        for (int p0 = 0; p0 < 4; p0++)
                            assertEquals(size, raw(f, p0).size(), "population per partition");
                        for (String state : List.of("AVAILABLE", "QUARANTINED", "CLAIMED", "RETRY"))
                            for (int percent :
                                    state.equals("AVAILABLE") ? List.of(0) : List.of(50, 90)) {
                                f.oracle.update(
                                        "update consumption_slot set"
                                            + " state='AVAILABLE',claim_token=null,claimed_until=null,retry_after=null");
                                if (percent > 0) {
                                    String mutation =
                                            switch (state) {
                                                case "QUARANTINED" -> "state='QUARANTINED'";
                                                case "CLAIMED" ->
                                                        "claim_token=md5('claim/'||s.consumable_id)::uuid,claimed_until=clock_timestamp()+interval"
                                                            + " '1 hour'";
                                                default ->
                                                        "retry_after=clock_timestamp()+interval '1"
                                                            + " hour'";
                                            };
                                    f.oracle.execute(
                                            "with numbered as(select consumable_id,row_number()"
                                                + " over(partition by partition_id order by"
                                                + " consumable_id) n from consumption_slot) update"
                                                + " consumption_slot s set "
                                                    + mutation
                                                    + " from numbered r where"
                                                    + " s.consumable_id=r.consumable_id and"
                                                    + " (r.n-1)%100<"
                                                    + percent);
                                }
                                f.oracle.execute("analyze consumption_slot");
                                for (int batch : List.of(1, 32)) {
                                    var keys = raw(f, 0);
                                    UUID upper = keys.getLast(), after = keys.get(size / 2);
                                    assertEquals(
                                            keys.subList(0, batch),
                                            page(f, 0, SlotKeyNavigation.Walk.fresh(), batch)
                                                    .keys());
                                    // Administrator-only disposable comparison. Rollback restores
                                    // walk index.
                                    for (boolean walkIndex : List.of(true, false))
                                        try (var connection = f.admin.getConnection()) {
                                            connection.setAutoCommit(false);
                                            try {
                                                if (!walkIndex)
                                                    try (var command =
                                                            connection.createStatement()) {
                                                        command.execute(
                                                                "drop index"
                                                                    + " consumption_slot_partition_walk_idx");
                                                    }
                                                for (int seek = 0; seek < 3; seek++) {
                                                    String query =
                                                            "select consumable_id from"
                                                                + " consumption_slot where"
                                                                + " consumption_id=? and"
                                                                + " partition_id=0"
                                                                    + (seek == 1
                                                                            ? " and consumable_id>?"
                                                                                  + " and consumable_id<=?"
                                                                            : "")
                                                                    + " order by consumable_id"
                                                                    + (seek == 2 ? " desc" : "")
                                                                    + " limit "
                                                                    + (seek == 2 ? 1 : batch);
                                                    var samples = new ArrayList<Long>();
                                                    long bytes0 =
                                                            allocated == null
                                                                    ? 0
                                                                    : allocated
                                                                            .getThreadAllocatedBytes(
                                                                                    Thread
                                                                                            .currentThread()
                                                                                            .threadId());
                                                    int rows = 0;
                                                    for (int sample = -5; sample < 30; sample++) {
                                                        long begin = System.nanoTime();
                                                        int count = 0;
                                                        try (var statement =
                                                                connection.prepareStatement(
                                                                        query)) {
                                                            statement.setObject(
                                                                    1, f.definition.id().value());
                                                            if (seek == 1) {
                                                                statement.setObject(2, after);
                                                                statement.setObject(3, upper);
                                                            }
                                                            try (var result =
                                                                    statement.executeQuery()) {
                                                                while (result.next()) {
                                                                    result.getObject(1, UUID.class);
                                                                    count++;
                                                                }
                                                            }
                                                        }
                                                        assertTrue(
                                                                count <= (seek == 2 ? 1 : batch));
                                                        if (sample >= 0) {
                                                            samples.add(System.nanoTime() - begin);
                                                            rows += count;
                                                        }
                                                    }
                                                    String explain;
                                                    try (var statement =
                                                            connection.prepareStatement(
                                                                    "explain"
                                                                        + " (analyze,buffers,wal,format"
                                                                        + " json) "
                                                                            + query)) {
                                                        statement.setObject(
                                                                1, f.definition.id().value());
                                                        if (seek == 1) {
                                                            statement.setObject(2, after);
                                                            statement.setObject(3, upper);
                                                        }
                                                        try (var result =
                                                                statement.executeQuery()) {
                                                            result.next();
                                                            explain = result.getString(1);
                                                        }
                                                    }
                                                    Collections.sort(samples);
                                                    long bytes =
                                                            allocated == null
                                                                    ? -1
                                                                    : allocated
                                                                                    .getThreadAllocatedBytes(
                                                                                            Thread
                                                                                                    .currentThread()
                                                                                                    .threadId())
                                                                            - bytes0;
                                                    f.trace.add(
                                                            "cost slotsPerPartition="
                                                                    + size
                                                                    + " state="
                                                                    + state
                                                                    + " percent="
                                                                    + percent
                                                                    + " B="
                                                                    + batch
                                                                    + " walkIndex="
                                                                    + walkIndex
                                                                    + " seek="
                                                                    + seek
                                                                    + " warmups=5 samples=30"
                                                                    + " jdbcRows="
                                                                    + rows
                                                                    + " p50ns="
                                                                    + samples.get(14)
                                                                    + " p95ns="
                                                                    + samples.get(28)
                                                                    + " p99maxNs="
                                                                    + samples.get(29)
                                                                    + " allocatedBytesIncludingWarmup="
                                                                    + bytes
                                                                    + " plan="
                                                                    + explain);
                                                }
                                            } finally {
                                                connection.rollback();
                                            }
                                        }
                                }
                            }
                    }
                    f.trace.add(
                            "Standalone JDBC cost: no Hikari in this navigation fixture."
                                + " Pool/GC/scheduling costs separately r05/w16; no integrated"
                                + " SPEC09 certification.");
                });
    }

    @Test
    void i5_n08() throws Exception {
        both(
                f -> {
                    var first = f.seed(0);
                    var second = f.seed(0);
                    var before = page(f, 0, SlotKeyNavigation.Walk.fresh(), 1);
                    var continued =
                            page(
                                    f,
                                    0,
                                    new SlotKeyNavigation.Walk(
                                            Optional.of(first), before.upperInclusive()),
                                    1);
                    assertEquals(List.of(second), continued.keys());
                    assertEquals(
                            List.of(first),
                            engine(f, f.tx)
                                    .navigate(
                                            engine(f, f.tx).acquirePartition(0).orElseThrow(),
                                            SlotKeyNavigation.Walk.fresh(),
                                            1)
                                    .keys());
                    assertEquals(List.of(first, second), raw(f, 0));
                });
    }
}
