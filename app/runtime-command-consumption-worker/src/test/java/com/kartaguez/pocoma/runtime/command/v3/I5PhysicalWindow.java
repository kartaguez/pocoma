package com.kartaguez.pocoma.runtime.command.v3;

import static com.kartaguez.pocoma.runtime.command.v3.I5TemporalV2Postgres.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.engine.consumption.v3.PartitionLease;

import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Test-only admission and PostgreSQL barriers. Never calls a renewal service. */
final class I5PhysicalWindow implements AutoCloseable {
    static final long KEY = 53005003L;
    final V3WorkerPostgresTest.Fixture fixture;
    final ReentrantReadWriteLock others = new ReentrantReadWriteLock(true);
    final ReentrantReadWriteLock renewals = new ReentrantReadWriteLock(true);
    final List<Connection> barriers = new ArrayList<>();
    final List<Window> windows = new CopyOnWriteArrayList<>();
    volatile boolean open;
    boolean sealed;
    String contractHash;

    static final class Window {
        final String key;
        final UUID token;
        final long start;
        volatile long end = Long.MAX_VALUE;

        Window(PartitionLease lease, long start) {
            key = key(lease);
            token = lease.ownerToken();
            this.start = start;
        }

        boolean covers(PartitionLease lease, long from, long until) {
            return key.equals(key(lease))
                    && token.equals(lease.ownerToken())
                    && start <= from
                    && from <= until
                    && until <= end;
        }

        public String toString() {
            return "Window[" + key + "," + token + "," + start + ".." + end + "]";
        }
    }

    I5PhysicalWindow(V3WorkerPostgresTest.Fixture fixture) throws Exception {
        this.fixture = fixture;
        Path file = null;
        for (var root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            var candidate =
                    root.resolve(
                            "docs/testing/evidence/L2-I5-temporal-final/temporal-contract-v3.properties");
            if (Files.isRegularFile(candidate)) {
                file = candidate;
                break;
            }
        }
        assertNotNull(file);
        var bytes = Files.readAllBytes(file);
        var contract = new Properties();
        contract.load(new java.io.ByteArrayInputStream(bytes));
        assertEquals("I5-CB-90-v3", contract.getProperty("version"));
        var numeric = new Properties();
        numeric.putAll(contract);
        numeric.setProperty("version", I5TemporalV2Test.INPUT.getProperty("version"));
        numeric.setProperty("availability", I5TemporalV2Test.INPUT.getProperty("availability"));
        assertEquals(I5TemporalV2Test.INPUT, numeric, "only physical availability may change");
        contractHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        fixture.trace.add("V3 CONTRACT sha256=" + contractHash);
    }

    boolean renewalThread() {
        return Thread.currentThread().getName().startsWith("v3-renew-");
    }

    java.util.concurrent.locks.Lock admission() {
        return (renewalThread() ? renewals : others).readLock();
    }

    void guard(JdbcTemplate sql, Tx tx) {
        tx.renewal = renewalThread();
        if (!tx.renewal) sql.execute("select pg_advisory_xact_lock_shared(" + KEY + ")");
        tx.guarded = true;
    }

    void ownership(Tx tx, String method, List<PartitionLease> leases, int worker) {
        assertTrue(tx.guarded, "INCONCLUSIVE unguarded ownership transaction");
        if (open) {
            assertTrue(tx.renewal, "INCONCLUSIVE conflicting admission inside window");
            assertEquals("renew", method, "INCONCLUSIVE unexpected ownership method");
            for (var lease : leases)
                assertEquals(
                        worker,
                        lease.partitionId() / 2,
                        "renewal crosses static W2 partition boundary");
        }
    }

    void begin(List<Journal> journals) throws Exception {
        assertFalse(open);
        others.writeLock().lockInterruptibly();
        renewals.writeLock().lockInterruptibly();
        try {
            for (var sql : List.of(fixture.write, fixture.read)) {
                var c = sql.getDataSource().getConnection();
                barriers.add(c);
                try (var s = c.createStatement()) {
                    s.execute("set statement_timeout='1000ms'");
                    s.execute("select pg_advisory_lock(" + KEY + ")");
                    try (var rows = s.executeQuery("select pg_backend_pid()")) {
                        rows.next();
                        int pid = rows.getInt(1);
                        assertTrue(
                                sql.queryForObject(
                                        "select exists(select 1 from pg_locks where pid=? and"
                                            + " locktype='advisory' and mode='ExclusiveLock' and"
                                            + " granted)",
                                        Boolean.class,
                                        pid));
                        fixture.trace.add("BARRIER EXCLUSIVE pid=" + pid + " db=" + c.getCatalog());
                    }
                }
            }
            // Admission locks ensure no new owner can appear while all previous XIDs are checked.
            var active =
                    fixture.write.queryForList(
                            "select pid,backend_xid::text xid from pg_stat_activity where"
                                    + " backend_xid is not null");
            for (var tx : journals.getFirst().transactions) {
                assertTrue(tx.end != Long.MAX_VALUE, "INCONCLUSIVE owner not returned at barrier");
                assertTrue(Set.of("COMMITTED", "ROLLED_BACK").contains(tx.resolution));
                assertFalse(
                        active.stream()
                                .anyMatch(
                                        row ->
                                                ((Number) row.get("pid")).intValue() == tx.pid
                                                        && tx.xid.equals(row.get("xid"))),
                        "INCONCLUSIVE backend still physically active");
            }
            for (var j : journals) for (var lease : j.leases()) probe(lease);
            assertEquals(12, windows.stream().filter(w -> w.end == Long.MAX_VALUE).count());
            open = true;
            fixture.trace.add("WINDOW OPEN independently of renewal result " + windows);
        } finally {
            renewals.writeLock().unlock();
            if (!open) close();
        }
    }

    void probe(PartitionLease lease) throws Exception {
        boolean write =
                fixture.write.queryForObject(
                        "select exists(select 1 from partition_ownership where consumption_id=?)",
                        Boolean.class,
                        lease.consumptionId().value());
        var sql = write ? fixture.write : fixture.read;
        Map<String, Object> row;
        try (var c = sql.getDataSource().getConnection()) {
            c.setAutoCommit(false);
            try (var q =
                    c.prepareStatement(
                            "select owner_token,owned_until,clock_timestamp(),pg_backend_pid() from"
                                + " partition_ownership where consumption_id=? and partition_id=?"
                                + " for update nowait")) {
                q.setObject(1, lease.consumptionId().value());
                q.setInt(2, lease.partitionId());
                try (var r = q.executeQuery()) {
                    assertTrue(r.next(), "INCONCLUSIVE missing physical row");
                    assertEquals(lease.ownerToken(), r.getObject(1, UUID.class));
                    assertTrue(
                            r.getTimestamp(2).toInstant().isAfter(r.getTimestamp(3).toInstant()));
                    row =
                            Map.of(
                                    "token",
                                    r.getObject(1),
                                    "until",
                                    r.getTimestamp(2),
                                    "now",
                                    r.getTimestamp(3),
                                    "pid",
                                    r.getInt(4));
                }
            } finally {
                c.rollback();
            }
        }
        long opened = System.nanoTime();
        windows.add(new Window(lease, opened));
        fixture.trace.add(
                "NOWAIT PROBE resolved before window start="
                        + opened
                        + " key="
                        + key(lease)
                        + " "
                        + row);
    }

    void obstruct(PartitionLease lease) {
        for (var w : windows)
            if (w.key.equals(key(lease)) && w.end == Long.MAX_VALUE) w.end = System.nanoTime();
    }

    void released(Holder holder, PartitionLease lease) throws Exception {
        var sql = holder.span.authority.equals("WRITE") ? fixture.write : fixture.read;
        assertEquals("COMMITTED", holder.span.resolution);
        assertFalse(
                sql.queryForObject(
                        "select exists(select 1 from pg_stat_activity where pid=? and"
                                + " backend_xid::text=?)",
                        Boolean.class,
                        holder.span.pid,
                        holder.span.xid));
        probe(lease);
        fixture.trace.add("HOLDER physically resolved; target window reopened " + holder.span);
    }

    boolean available(Attempt a, PartitionLease lease, List<Tx> transactions) {
        assertTrue(a.tx.guarded, "INCONCLUSIVE uncovered attempt");
        boolean covered = windows.stream().anyMatch(w -> w.covers(lease, a.storeStart, a.storeEnd));
        // Other renewal transactions are live, but their admitted ownership keys must be disjoint.
        boolean conflict =
                transactions.stream()
                        .anyMatch(
                                t ->
                                        t != a.tx
                                                && t.authority.equals(a.authority)
                                                && t.keys.contains(key(lease))
                                                && t.start < a.storeEnd
                                                && t.end > a.storeStart);
        fixture.trace.add(
                "CONTROLLED AVAILABILITY key="
                        + key(lease)
                        + " attempt="
                        + a.storeStart
                        + ".."
                        + a.storeEnd
                        + " covered="
                        + covered
                        + " conflict="
                        + conflict);
        return witnessed(covered, a.tx.guarded, conflict);
    }

    static boolean witnessed(boolean covered, boolean complete, boolean conflict) {
        I5TemporalV2Test.coverage(complete);
        return covered && !conflict;
    }

    void seal(List<Journal> journals) throws Exception {
        assertTrue(
                renewals.writeLock().tryLock(2, java.util.concurrent.TimeUnit.SECONDS),
                "INCONCLUSIVE cannot seal physical journal");
        sealed = true;
        V3WorkerPostgresTest.await(
                () -> journals.stream().allMatch(j -> j.attempts.stream().allMatch(a -> a.end > 0)),
                java.time.Duration.ofSeconds(2));
        I5TemporalV2Test.coverage(
                journals.getFirst().transactions.stream()
                        .allMatch(
                                t ->
                                        (t.guarded || t.injected)
                                                && t.end != Long.MAX_VALUE
                                                && Set.of("COMMITTED", "ROLLED_BACK")
                                                        .contains(t.resolution)));
        fixture.trace.add("JOURNAL SEALED all admitted owners resolved and attempts complete");
    }

    public void close() throws Exception {
        for (var w : windows) if (w.end == Long.MAX_VALUE) w.end = System.nanoTime();
        open = false;
        for (var c : barriers) c.close(); // Session advisory locks released physically.
        barriers.clear();
        if (sealed) {
            sealed = false;
            renewals.writeLock().unlock();
        }
        if (others.isWriteLockedByCurrentThread()) others.writeLock().unlock();
        fixture.trace.add("WINDOW CLOSED " + windows);
    }
}
