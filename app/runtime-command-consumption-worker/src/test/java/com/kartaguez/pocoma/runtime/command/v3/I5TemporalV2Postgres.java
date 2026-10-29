package com.kartaguez.pocoma.runtime.command.v3;

import static com.kartaguez.pocoma.runtime.command.v3.I5TemporalV2Test.*;
import static com.kartaguez.pocoma.runtime.command.v3.V3WorkerPostgresTest.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.*;
import com.kartaguez.pocoma.port.transaction.v3.AttemptTransactions;

import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Campaign-only observation. Real owners, stores, four pools and renewal threads are retained. */
final class I5TemporalV2Postgres {
    static Object invoke(Object target, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw e.getCause();
        }
    }

    static final class Tx {
        final String authority, thread;
        final long start = System.nanoTime();
        final Set<String> keys = ConcurrentHashMap.newKeySet();
        volatile long end = Long.MAX_VALUE;
        volatile int pid;
        volatile String xid = "", resolution = "ACTIVE";

        Tx(String authority) {
            this.authority = authority;
            thread = Thread.currentThread().getName();
        }

        public String toString() {
            return "Tx["
                    + authority
                    + ","
                    + thread
                    + ","
                    + start
                    + ","
                    + end
                    + ",pid="
                    + pid
                    + ",xid="
                    + xid
                    + ","
                    + resolution
                    + ",keys="
                    + keys
                    + "]";
        }
    }

    static final class Attempt {
        final String authority;
        final int worker;
        final long due, start, storeStart;
        final List<PartitionLease> leases;
        final Tx tx;
        final Map<String, Long> retryDeadlines = new HashMap<>();
        volatile long storeEnd, end;
        volatile List<PartitionLease> returned = List.of();
        volatile String outcome = "ACTIVE";

        Attempt(String authority, int worker, Object call, List<PartitionLease> leases, Tx tx)
                throws Exception {
            this.authority = authority;
            this.worker = worker;
            this.leases = List.copyOf(leases);
            this.tx = tx;
            due = (long) field(call, "due");
            start = (long) field(call, "began");
            storeStart = System.nanoTime();
            for (var entry : (List<?>) field(call, "leases")) {
                retryDeadlines.put(
                        key((PartitionLease) field(entry, "lease")),
                        (long) field(entry, "retryUntil"));
            }
        }

        public String toString() {
            return "Attempt[retryDeadlines="
                    + retryDeadlines
                    + ",w="
                    + worker
                    + ","
                    + authority
                    + ",due="
                    + due
                    + ",start="
                    + start
                    + ",store="
                    + storeStart
                    + ".."
                    + storeEnd
                    + ",end="
                    + end
                    + ","
                    + outcome
                    + ",candidates="
                    + leases
                    + ",returned="
                    + returned
                    + "]";
        }
    }

    static String key(PartitionLease lease) {
        return lease.consumptionId().value() + "/" + lease.partitionId();
    }

    static final class Holder implements AutoCloseable {
        final Connection connection;
        final Tx span;
        final Instant oldUntil;

        Holder(JdbcTemplate sql, String authority, PartitionLease lease, List<Tx> all)
                throws Exception {
            connection = sql.getDataSource().getConnection();
            connection.setAutoCommit(false);
            span = new Tx(authority);
            span.keys.add(key(lease));
            all.add(span);
            try (var q =
                    connection.prepareStatement(
                            "select owner_token,owned_until from partition_ownership where"
                                    + " consumption_id=? and partition_id=? for update")) {
                q.setObject(1, lease.consumptionId().value());
                q.setInt(2, lease.partitionId());
                try (var r = q.executeQuery()) {
                    assertTrue(r.next());
                    assertEquals(lease.ownerToken(), r.getObject(1, UUID.class));
                    oldUntil = r.getTimestamp(2).toInstant();
                }
            }
            try (var q = connection.createStatement();
                    var r = q.executeQuery("select pg_backend_pid(),pg_current_xact_id()::text")) {
                r.next();
                span.pid = r.getInt(1);
                span.xid = r.getString(2);
            }
            assertTrue(
                    sql.queryForObject(
                            "select exists(select 1 from pg_stat_activity where pid=? and"
                                    + " backend_xid::text=? and xact_start is not null)",
                            Boolean.class,
                            span.pid,
                            span.xid));
        }

        public synchronized void close() throws SQLException {
            if (span.end != Long.MAX_VALUE) return;
            connection.commit();
            connection.close();
            span.resolution = "COMMITTED";
            span.end = System.nanoTime();
        }
    }

    static final class Journal {
        final Fixture fixture;
        final List<Tx> transactions;
        final List<Attempt> attempts = new CopyOnWriteArrayList<>();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final ThreadLocal<Tx> currentTx = new ThreadLocal<>();
        final ThreadLocal<Attempt> currentAttempt = new ThreadLocal<>();
        final Map<String, Holder> releaseOnOmission = new ConcurrentHashMap<>();
        final AtomicInteger suspensions = new AtomicInteger();
        final int index, workers;
        V3WorkerPostgresTest.Runtime runtime;

        Journal(Fixture f, List<Tx> transactions, int index, int workers) {
            fixture = f;
            this.transactions = transactions;
            this.index = index;
            this.workers = workers;
        }

        void observe(
                com.kartaguez.pocoma.domain.consumption.v3.ConsumptionId id,
                ConsumptionObservation.Phase phase,
                ConsumptionObservation.Outcome outcome,
                long amount,
                Duration duration) {
            try {
                if (phase == ConsumptionObservation.Phase.LEASE_SUSPEND)
                    suspensions.addAndGet((int) amount);
                if (phase == ConsumptionObservation.Phase.RENEWAL_LEASE) {
                    var a = currentAttempt.get();
                    assertNotNull(a, "missing independent attempt");
                    a.end = System.nanoTime();
                    a.outcome = outcome.toString();
                    currentAttempt.remove();
                }
            } catch (Throwable e) {
                errors.add(e.toString());
            }
        }

        void attach(V3WorkerPostgresTest.Runtime r) throws Exception {
            runtime = r;
            for (var b : r.bindings) {
                String authority = b.definition().authority().toString();
                for (var component : List.of(b.engine(), b.discovery())) {
                    String ownerField =
                            component instanceof ConsumptionEngine
                                    ? "controlTransactions"
                                    : "effects";
                    var owner = (AttemptTransactions) field(component, ownerField);
                    var pool = r.pool(authority.equals("WRITE") ? "writeControl" : "readControl");
                    var sql = new JdbcTemplate(pool);
                    replace(
                            component,
                            ownerField,
                            java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class[] {AttemptTransactions.class},
                                    (proxy, m, args) -> {
                                        if (!m.getName().equals("write"))
                                            return invoke(owner, m, args);
                                        var tx = new Tx(authority);
                                        transactions.add(tx);
                                        var action = (Supplier<?>) args[0];
                                        Object[] wrapped = {
                                            (Supplier<Object>)
                                                    () -> {
                                                        currentTx.set(tx);
                                                        var row =
                                                                sql.queryForMap(
                                                                        "select pg_backend_pid()"
                                                                            + " pid,pg_current_xact_id()::text"
                                                                            + " xid,current_setting('transaction_read_only')"
                                                                            + " ro");
                                                        tx.pid =
                                                                ((Number) row.get("pid"))
                                                                        .intValue();
                                                        tx.xid = row.get("xid").toString();
                                                        assertEquals("off", row.get("ro"));
                                                        return action.get();
                                                    }
                                        };
                                        try {
                                            var result = invoke(owner, m, wrapped);
                                            tx.resolution = "COMMITTED";
                                            return result;
                                        } catch (AttemptTransactions.Failure failure) {
                                            tx.resolution = failure.resolution().toString();
                                            throw failure;
                                        } finally {
                                            tx.end = System.nanoTime();
                                            currentTx.remove();
                                        }
                                    }));
                    var store = (PartitionOwnershipStore) field(component, "ownership");
                    replace(
                            component,
                            "ownership",
                            java.lang.reflect.Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class[] {PartitionOwnershipStore.class},
                                    (proxy, m, args) -> {
                                        if (m.getName().equals("bound"))
                                            return invoke(store, m, args);
                                        var tx = currentTx.get();
                                        assertNotNull(
                                                tx,
                                                "uncovered physical ownership call: "
                                                        + m.getName());
                                        if (m.getName().equals("acquire"))
                                            tx.keys.add(
                                                    b.definition().id().value() + "/" + args[1]);
                                        else if (m.getName().equals("protectAcquisition"))
                                            tx.keys.add(key((PartitionLease) args[1]));
                                        else if (m.getName().equals("renew"))
                                            for (var l : (List<PartitionLease>) args[1])
                                                tx.keys.add(key(l));
                                        if (!m.getName().equals("renew"))
                                            return invoke(store, m, args);
                                        Object call;
                                        synchronized (field(r.worker, "monitor")) {
                                            call =
                                                    ((Collection<?>)
                                                                    field(r.worker, "renewalCalls"))
                                                            .stream()
                                                                    .filter(
                                                                            c -> {
                                                                                try {
                                                                                    return (int)
                                                                                                            field(
                                                                                                                    c,
                                                                                                                    "lane")
                                                                                                    == 2
                                                                                            && field(
                                                                                                            c,
                                                                                                            "authority")
                                                                                                    .toString()
                                                                                                    .equals(
                                                                                                            authority);
                                                                                } catch (
                                                                                        Exception
                                                                                                e) {
                                                                                    throw new IllegalStateException(
                                                                                            e);
                                                                                }
                                                                            })
                                                                    .findFirst()
                                                                    .orElseThrow();
                                        }
                                        var a =
                                                new Attempt(
                                                        authority,
                                                        index,
                                                        call,
                                                        (List<PartitionLease>) args[1],
                                                        tx);
                                        attempts.add(a);
                                        currentAttempt.set(a);
                                        try {
                                            a.returned =
                                                    (List<PartitionLease>) invoke(store, m, args);
                                            for (var l : a.leases)
                                                if (a.returned.stream()
                                                        .noneMatch(v -> key(v).equals(key(l)))) {
                                                    var h = releaseOnOmission.remove(key(l));
                                                    if (h != null) h.close();
                                                }
                                            return a.returned;
                                        } finally {
                                            a.storeEnd = System.nanoTime();
                                        }
                                    }));
                }
            }
        }

        List<PartitionLease> leases() throws Exception {
            synchronized (field(runtime.worker, "monitor")) {
                var values = (Map<?, ?>) field(runtime.worker, "leases");
                var result = new ArrayList<PartitionLease>();
                for (var e : values.values()) result.add((PartitionLease) field(e, "lease"));
                return result;
            }
        }

        void assertSuspended(String key) throws Exception {
            synchronized (field(runtime.worker, "monitor")) {
                for (var e : ((Map<?, ?>) field(runtime.worker, "leases")).values())
                    if (key((PartitionLease) field(e, "lease")).equals(key))
                        assertFalse(
                                (boolean) field(e, "usable"),
                                "C new local use enabled after omission");
            }
        }

        boolean removed(String key) throws Exception {
            return leases().stream().noneMatch(l -> key(l).equals(key));
        }

        List<long[]> conflicts(Attempt attempt, String key) {
            return transactions.stream()
                    .filter(
                            t ->
                                    t != attempt.tx
                                            && t.authority.equals(attempt.authority)
                                            && (t.keys.contains(key) || t.end == Long.MAX_VALUE))
                    .map(t -> new long[] {t.start, t.end})
                    .toList();
        }

        void verify(int from, boolean availabilityRequired) {
            assertTrue(errors.isEmpty(), errors.toString());
            coverage(
                    transactions.stream()
                            .filter(t -> t.end != Long.MAX_VALUE)
                            .allMatch(
                                    t ->
                                            t.pid > 0
                                                    && !t.xid.isEmpty()
                                                    && Set.of("COMMITTED", "ROLLED_BACK")
                                                            .contains(t.resolution)));

            var selected = List.copyOf(attempts.subList(from, attempts.size()));
            assertFalse(selected.isEmpty());
            for (var a : selected) {
                coverage(
                        a.tx.pid > 0
                                && !a.tx.xid.isEmpty()
                                && a.tx.end != Long.MAX_VALUE
                                && a.end > 0);
                assertEquals("COMMITTED", a.tx.resolution);
                assertEquals("CONFIRMED", a.outcome);
                int n = (a.authority.equals("WRITE") ? 4 : 8) / workers;
                below(a.end - a.start, ns("operation"), "B physical complete operation");
                // due is deliberately preserved across omissions. Separate per-attempt eligibility
                // is computed below; the original due never becomes a fresh deadline.
                for (var lease : a.leases) {
                    var previous =
                            attempts.stream()
                                    .filter(
                                            p ->
                                                    p != a
                                                            && p.start < a.start
                                                            && p.leases.stream()
                                                                    .anyMatch(
                                                                            l ->
                                                                                    key(l).equals(
                                                                                                    key(
                                                                                                            lease))))
                                    .max(Comparator.comparingLong(p -> p.start));
                    long eligible = a.due;
                    if (previous.isPresent()
                            && previous.get().returned.stream()
                                    .noneMatch(l -> key(l).equals(key(lease))))
                        eligible = Math.max(previous.get().start + ns("poll"), previous.get().end);
                    below(a.end - eligible, bound(n), "B next attempt complete");
                    long retryUntil = a.retryDeadlines.getOrDefault(key(lease), 0L);
                    if (retryUntil != 0)
                        below(a.end, retryUntil, "B local retry5s frozen original cutoff");
                    var conflicts = conflicts(a, key(lease));
                    boolean available = free(a.storeStart, a.storeEnd, conflicts);
                    fixture.trace.add(
                            "AVAILABILITY key="
                                    + key(lease)
                                    + " window="
                                    + a.storeStart
                                    + ".."
                                    + a.storeEnd
                                    + " physicalFree="
                                    + available
                                    + " physicalConflictSpans="
                                    + conflicts.stream().map(java.util.Arrays::toString).toList());
                    boolean returned =
                            a.returned.stream()
                                    .anyMatch(
                                            l ->
                                                    key(l).equals(key(lease))
                                                            && l.ownerToken()
                                                                    .equals(lease.ownerToken()));
                    if (available)
                        assertTrue(returned, "B available physical window without same-token CAS");
                }
                long busy =
                        attempts.stream()
                                .filter(p -> p.authority.equals(a.authority))
                                .mapToLong(
                                        p ->
                                                Math.max(
                                                        0,
                                                        Math.min(p.end, a.end)
                                                                - Math.max(p.start, a.due)))
                                .sum();
                // The full due interval may include arbitrarily many known omissions; all of
                // their active and polling time is accounted, never capped by R.
                long count =
                        attempts.stream()
                                .filter(
                                        p ->
                                                p.authority.equals(a.authority)
                                                        && p.end > a.due
                                                        && p.start < a.end)
                                .count();
                below(
                        Math.max(0, a.end - a.due - busy - ns("idle") - count * ns("poll")),
                        ns("jitter"),
                        "B cumulative jitter");
            }
            if (availabilityRequired) {
                var keys =
                        selected.stream()
                                .flatMap(a -> a.leases.stream())
                                .map(I5TemporalV2Postgres::key)
                                .collect(java.util.stream.Collectors.toSet());
                assertEquals(12 / workers, keys.size(), "all expected local candidates");
                for (var key : keys)
                    assertTrue(
                            selected.stream()
                                    .anyMatch(
                                            a ->
                                                    a.leases.stream()
                                                                    .anyMatch(
                                                                            l -> key(l).equals(key))
                                                            && free(
                                                                    a.storeStart,
                                                                    a.storeEnd,
                                                                    conflicts(a, key))),
                            "B missing independent available window " + key);
            }
            fixture.trace.add("ATTEMPTS worker=" + index + " " + selected);
        }
    }

    static void campaign(PostgreSQLContainer pg, int workers, int batch) throws Exception {
        String variant = "W" + workers + "-B" + batch + "-MW4";
        try (var f = new Fixture(pg)) {
            try (var setup = new V3WorkerPostgresTest.Runtime(f, ConsumptionObservation.noop())) {
                seedPublications(f, setup, 32);
                for (int w = 0; w < workers; w++) {
                    var id = pendingAtPartition(f, 91000 + w * 1000, w * 2);
                    admitBeforeStart(f, setup, id);
                }
            }
            var txs = new CopyOnWriteArrayList<Tx>();
            var journals = new ArrayList<Journal>();
            var runtimes = new ArrayList<V3WorkerPostgresTest.Runtime>();
            var release = new CountDownLatch(1);
            var holders = new ArrayList<Holder>();
            var held = ConcurrentHashMap.<UUID>newKeySet();
            var preparations = new ConcurrentHashMap<UUID, Map<String, Object>>();
            try {
                for (int w = 0; w < workers; w++) {
                    var properties = new LinkedHashMap<>(f.properties);
                    properties.put("pocoma.v3.worker.worker-name", "i5-v2-" + w);
                    properties.put("pocoma.v3.worker.worker-limit", "4");
                    properties.put("pocoma.v3.worker.lifecycle.batch-size", "" + batch);
                    properties.put("pocoma.v3.worker.lifecycle.lease-duration-ms", "90000");
                    String ps = workers == 1 ? "0|1|2|3" : w == 0 ? "0|1" : "2|3";
                    properties.put(
                            "pocoma.v3.worker.partitions",
                            "Command="
                                    + ps
                                    + ";I5ReadPotProbe="
                                    + ps
                                    + ";I5ReadCompletionProbe="
                                    + ps);
                    var j = new Journal(f, txs, w, workers);
                    journals.add(j);
                    var r = new V3WorkerPostgresTest.Runtime(properties, j::observe);
                    runtimes.add(r);
                    j.attach(r);
                    var entered = new CountDownLatch(4);
                    holdFourWithWrite(
                            r,
                            entered,
                            release,
                            id -> {
                                held.add(id);
                                boolean write =
                                        f.write.queryForObject(
                                                "select exists(select 1 from v3_command where"
                                                        + " request_id=?)",
                                                Boolean.class,
                                                id);
                                try {
                                    var sql =
                                            new JdbcTemplate(
                                                    r.pool(
                                                            write
                                                                    ? "writeBusiness"
                                                                    : "readBusiness"));
                                    var row =
                                            sql.queryForMap(
                                                    "select pg_backend_pid()"
                                                        + " pid,pg_current_xact_id()::text"
                                                        + " xid,current_setting('transaction_read_only')"
                                                        + " ro");
                                    assertEquals("on", row.get("ro"));
                                    preparations.put(id, row);
                                } catch (Exception e) {
                                    throw new IllegalStateException(e);
                                }
                            });
                    r.worker.start();
                    latch(entered);
                }
                await(
                        () -> {
                            try {
                                return journals.stream()
                                                .mapToInt(
                                                        j -> {
                                                            try {
                                                                return j.leases().size();
                                                            } catch (Exception e) {
                                                                throw new IllegalStateException(e);
                                                            }
                                                        })
                                                .sum()
                                        == 12;
                            } catch (Exception e) {
                                throw new IllegalStateException(e);
                            }
                        });
                assertEquals(workers * 4, held.size());
                assertEquals(held.size(), preparations.size());
                for (var row : preparations.values())
                    assertTrue(
                            f.write.queryForObject(
                                    "select exists(select 1 from pg_stat_activity where pid=? and"
                                            + " backend_xid::text=? and xact_start is not null)",
                                    Boolean.class,
                                    row.get("pid"),
                                    row.get("xid")));
                int roleSessions =
                        f.write.queryForObject(
                                "select count(*) from pg_stat_activity where usename in (?,?)",
                                Integer.class,
                                f.writeRole,
                                f.readRole);
                assertEquals(workers * 32, roleSessions);
                assertTrue(
                        roleSessions + 16
                                <= f.write.queryForObject("show max_connections", Integer.class));
                f.trace.add(
                        "FROZEN V2 sha256="
                                + HASH
                                + " variant="
                                + variant
                                + " preparations="
                                + preparations
                                + " roleSessions="
                                + roleSessions
                                + " sessions="
                                + f.write.queryForList(
                                        "select pid,datname,usename,state,backend_xid::text from"
                                                + " pg_stat_activity where usename is not null"));
                for (int cycle = 1; cycle <= 2; cycle++) {
                    int stage = cycle;
                    var starts = journals.stream().map(j -> j.attempts.size()).toList();
                    var vectors =
                            Map.of(
                                    f.write,
                                    V3WorkerResourcesPostgresTest.cVector(f.write, held),
                                    f.read,
                                    V3WorkerResourcesPostgresTest.cVector(f.read, held));
                    assertEquals(
                            12 + held.size(), vectors.values().stream().mapToInt(Map::size).sum());
                    if (cycle == 2)
                        for (var j : journals)
                            for (String authority : List.of("WRITE", "READ")) {
                                var lease =
                                        j.leases().stream()
                                                .filter(
                                                        l ->
                                                                j.runtime.bindings.stream()
                                                                        .anyMatch(
                                                                                b ->
                                                                                        b.definition()
                                                                                                        .id()
                                                                                                        .equals(
                                                                                                                l
                                                                                                                        .consumptionId())
                                                                                                && b.definition()
                                                                                                        .authority()
                                                                                                        .toString()
                                                                                                        .equals(
                                                                                                                authority)))
                                                .findFirst()
                                                .orElseThrow();
                                var h =
                                        new Holder(
                                                authority.equals("WRITE") ? f.write : f.read,
                                                authority,
                                                lease,
                                                txs);
                                holders.add(h);
                                j.releaseOnOmission.put(key(lease), h);
                            }
                    System.gc();
                    for (var entry : vectors.entrySet()) {
                        var sql = entry.getKey();
                        var before = entry.getValue();
                        await(
                                () -> {
                                    var after = V3WorkerResourcesPostgresTest.cVector(sql, held);
                                    return before.entrySet().stream()
                                            .allMatch(
                                                    e ->
                                                            after.containsKey(e.getKey())
                                                                    && after.get(e.getKey())
                                                                            .until()
                                                                            .isAfter(
                                                                                    e.getValue()
                                                                                            .until()));
                                },
                                Duration.ofNanos(ns("watchdog")));
                        var after = V3WorkerResourcesPostgresTest.cVector(sql, held);
                        f.trace.add("PG stage=" + stage + " before=" + before + " after=" + after);
                        for (var e : before.entrySet()) {
                            var next = after.get(e.getKey());
                            extension(
                                    e.getValue().token(),
                                    next.token(),
                                    e.getValue().until(),
                                    next.until(),
                                    next.now());
                            below(
                                    Duration.between(
                                                    e.getValue()
                                                            .until()
                                                            .minusMillis(
                                                                    e.getKey().startsWith("lease/")
                                                                            ? 90000
                                                                            : 60000),
                                                    e.getValue().now())
                                            .toNanos(),
                                    ns("delta"),
                                    "B initial physical age");
                        }
                    }
                    await(
                            () ->
                                    journals.stream()
                                            .allMatch(
                                                    j ->
                                                            j.attempts.stream()
                                                                    .allMatch(a -> a.end > 0)),
                            Duration.ofSeconds(2));
                    for (int w = 0; w < workers; w++) {
                        var j = journals.get(w);
                        assertTrue(j.releaseOnOmission.isEmpty(), "injected holder not exercised");
                        j.verify(starts.get(w), true);
                        if (cycle == 2)
                            assertTrue(j.suspensions.get() >= 2, "WRITE/READ suspend observed");
                    }
                    f.evidence("temporal-v2-" + variant + "-stage" + cycle);
                }
                // Deliberately violate availability; prove safe local retirement without extension.
                for (var j : journals)
                    for (String authority : List.of("WRITE", "READ")) {
                        var lease =
                                j.leases().stream()
                                        .filter(
                                                l ->
                                                        j.runtime.bindings.stream()
                                                                .anyMatch(
                                                                        b ->
                                                                                b.definition()
                                                                                                .id()
                                                                                                .equals(
                                                                                                        l
                                                                                                                .consumptionId())
                                                                                        && b.definition()
                                                                                                .authority()
                                                                                                .toString()
                                                                                                .equals(
                                                                                                        authority)))
                                        .findFirst()
                                        .orElseThrow();
                        var sql = authority.equals("WRITE") ? f.write : f.read;
                        var h = new Holder(sql, authority, lease, txs);
                        holders.add(h);
                        await(
                                () ->
                                        j.attempts.stream()
                                                .anyMatch(
                                                        a ->
                                                                a.start >= h.span.start
                                                                        && a.end > 0
                                                                        && a.leases.stream()
                                                                                .anyMatch(
                                                                                        l ->
                                                                                                key(l).equals(
                                                                                                                key(
                                                                                                                        lease)))
                                                                        && a.returned.stream()
                                                                                .noneMatch(
                                                                                        l ->
                                                                                                key(l).equals(
                                                                                                                key(
                                                                                                                        lease)))));
                        j.assertSuspended(key(lease));
                        long omittedAt = System.nanoTime();
                        await(
                                () -> {
                                    try {
                                        return j.removed(key(lease));
                                    } catch (Exception e) {
                                        throw new IllegalStateException(e);
                                    }
                                },
                                Duration.ofSeconds(7));
                        await(
                                () -> System.nanoTime() - omittedAt >= ns("excessive.hold"),
                                Duration.ofSeconds(7));
                        var row =
                                sql.queryForMap(
                                        "select owner_token,owned_until,clock_timestamp() now from"
                                                + " partition_ownership where consumption_id=? and"
                                                + " partition_id=?",
                                        lease.consumptionId().value(),
                                        lease.partitionId());
                        assertEquals(lease.ownerToken(), row.get("owner_token"));
                        assertEquals(h.oldUntil, ((Timestamp) row.get("owned_until")).toInstant());
                        assertTrue(
                                System.nanoTime() - omittedAt
                                        >= ns("revalidation") - ns("operation"));
                        assertTrue(
                                h.span.end == Long.MAX_VALUE,
                                "physical holder still unresolved before release");
                        f.trace.add(
                                "C-EXCESS key="
                                        + key(lease)
                                        + " removed=true suspended=true unextended="
                                        + row
                                        + " holder="
                                        + h.span);
                        h.close();
                    }
                f.trace.add(
                        "V2 QUALIFICATION PASS "
                                + variant
                                + " WRITE/READ nominal/admissible/excessive; no 30s certificate");
            } finally {
                for (var h : holders) h.close();
                release.countDown();
                f.trace.add("ALL_TRANSACTIONS " + txs);
                for (var j : journals)
                    f.trace.add(
                            "ALL_ATTEMPTS worker="
                                    + j.index
                                    + " "
                                    + j.attempts
                                    + " errors="
                                    + j.errors);
                f.evidence("temporal-v2-" + variant + "-final");
                for (var r : runtimes) r.close();
            }
        }
    }
}
