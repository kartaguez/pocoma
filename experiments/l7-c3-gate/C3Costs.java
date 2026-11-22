package com.kartaguez.pocoma.runtime.task;

import static com.kartaguez.pocoma.compose.polling.materialize.ReadParticipants.Name.*;
import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.compose.polling.materialize.ReadParticipants.Name;
import com.kartaguez.pocoma.contract.consumption.invocation.ConsumptionProtocol.Status;
import com.kartaguez.pocoma.contract.consumption.participation.ConsumptionParticipant;
import com.kartaguez.pocoma.contract.consumption.participation.PreparationReads;
import com.kartaguez.pocoma.contract.consumption.participation.SlotCompletion;
import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.domain.consumption.RoutingV1;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

final class C3Costs {
    private static final List<Name> FAMILIES =
            List.of(COMMAND_RESULT, IDENTITY_REQUEST_RESULT, POT_VERSION, POT_BALANCE);
    private static final int WARMUP = 12;
    private final PostgreSQLContainer pg;
    private final JdbcTemplate admin;
    private final Path output;
    private final Path tooling;
    private final int samples;
    private final boolean inspectWal;

    C3Costs(PostgreSQLContainer pg, Path output, Path tooling, int samples, boolean inspectWal) {
        this.pg = pg;
        this.admin = C3Complement.admin(pg);
        this.output = output;
        this.tooling = tooling;
        this.samples = samples;
        this.inspectWal = inspectWal;
    }

    void run() throws Exception {
        assertTrue(samples > 0 && samples < 128, "Use 1–127 measured finalizations per family");
        if (inspectWal) Files.writeString(output.resolve("wal.tsv"),
                "profile\tpublish\tfrom_lsn\tto_lsn\ttype\trecords\trecord_bytes\tfpi_bytes\ttotal_bytes\n");
        try (var summary = writer("windows.tsv"); var rows = writer("samples.tsv"); var sql = writer("sql.tsv");
             var waits = writer("waits.tsv"); var storage = writer("storage.tsv")) {
            summary.println("profile\tworkers\tpublish\tn\twall_ns\twal_bytes\tpg_cpu_us\tread_jdbc\twrite_jdbc"
                    + "\tread_tx_commit\twrite_tx_commit\tread_tx_rollback\twrite_tx_rollback\tdeadlocks"
                    + "\tread_sql_ms\twrite_sql_ms\tutc_start\tutc_end");
            rows.println("profile\tworkers\tpublish\tfamily\tpartition\tfinal_ns\tclaim_and_final_ns\tworker_cpu_ns");
            sql.println("profile\tworkers\tpublish\trole\tcalls\texec_ms\twal_bytes\twal_records\twal_fpi\tquery");
            storage.println("workers\tpublish\trelation\trows\tbytes_with_indexes");
            waits.println("profile\tworkers\tpublish\tpid\ttype\tevent\tblockers\tquery");
            for (int workers : inspectWal ? List.of(1) : List.of(1, 4)) {
                for (boolean publish : List.of(workers == 4, workers == 1)) {
                    runVariant(workers, publish, summary, rows, sql, waits, storage);
                }
            }
        }
        var lockLog = pg.getLogs().lines()
                .filter(line -> line.contains("still waiting for") || line.contains("acquired ")
                        || line.contains("deadlock detected") || line.contains("STATEMENT:"))
                .toList();
        Files.write(output.resolve("postgres-locks.log"), lockLog);
    }

    private PrintWriter writer(String name) throws Exception {
        return new PrintWriter(Files.newBufferedWriter(output.resolve(name)), true);
    }

    private void runVariant(int workers, boolean publish, PrintWriter summary, PrintWriter rows,
                            PrintWriter sql, PrintWriter waits, PrintWriter storage) throws Exception {
        try (var f = new ReadParticipantsFixture(pg, "c3-cost-" + workers + "-" + publish)) {
            var db = f.f.read;
            var published = new EnumMap<Name, Map<UUID, Work>>(Name.class);
            for (var name : FAMILIES) published.put(name, new HashMap<>());
            if (publish) {
                C3Complement.enablePilot(f);
                db.oracle.execute(Files.readString(tooling.resolve("publication.sql"))
                        .replace("__WORKER__", db.runtimeRole));
                for (var name : FAMILIES.subList(1, FAMILIES.size())) {
                    var real = f.participants.get(name).participant();
                    f.engines.put(name, f.engine(name, experimentalPublication(db, real, published.get(name)),
                            db.source, db.tx));
                }
            }

            var batch = new EnumMap<Name, List<Work>>(Name.class);
            for (var name : FAMILIES) batch.put(name, new ArrayList<>());
            for (int i = 0; i < WARMUP + samples; i++) {
                var pot = f.f.publish();
                batch.get(COMMAND_RESULT).add(new Work(COMMAND_RESULT, pot.completion(), pot.request(), null));
                batch.get(POT_VERSION).add(new Work(POT_VERSION, pot.event(), pot.pot(), 1L));
                batch.get(POT_BALANCE).add(new Work(POT_BALANCE, pot.event(), pot.pot(), 1L));
                var id = f.publishIdentity(new IdentityRequest.Registration(), null);
                var event = f.f.oracle.queryForObject(
                        "select event_id from v3_identity_completion_event where subject_id=?", UUID.class, id);
                batch.get(IDENTITY_REQUEST_RESULT).add(new Work(IDENTITY_REQUEST_RESULT, event, id, null));
            }
            for (var name : FAMILIES) {
                for (var work : batch.get(name)) published.get(name).put(work.event(), work);
            }
            enroll(f, batch.values().stream().flatMap(List::stream).toList());
            for (var name : FAMILIES) {
                var jobs = batch.get(name);
                renew(f);
                measure(f, "warmup", workers, publish, jobs.subList(0, WARMUP), null, null, null, null);
                measure(f, name.name(), workers, publish, jobs.subList(WARMUP, jobs.size()),
                        summary, rows, sql, waits);
            }

            if (workers > 1) {
                if (publish) {
                    for (var name : FAMILIES.subList(1, FAMILIES.size())) {
                        db.oracle.execute("alter table " + effectTable(name)
                                + " disable trigger c3_bench_publication");
                    }
                }
                var mixed = new ArrayList<Work>();
                for (int i = 0; i < WARMUP + samples; i++) {
                    var pot = f.f.publish();
                    mixed.add(new Work(COMMAND_RESULT, pot.completion(), pot.request(), null));
                    mixed.add(new Work(POT_VERSION, pot.event(), pot.pot(), 1L));
                    mixed.add(new Work(POT_BALANCE, pot.event(), pot.pot(), 1L));
                }
                enroll(f, mixed);
                measure(f, "warmup", workers, publish, mixed.subList(0, 3 * WARMUP), null, null, null, null);
                measure(f, "MIXED", workers, publish, mixed.subList(3 * WARMUP, mixed.size()),
                        summary, rows, sql, waits);
            }

            for (var name : FAMILIES) {
                long count = WARMUP + samples + (workers > 1 && name != IDENTITY_REQUEST_RESULT
                        ? WARMUP + samples : 0);
                assertEquals(count, db.oracle.queryForObject("select count(*) from " + effectTable(name), Long.class));
            }
            for (String table : List.of("read_availability_counter", "read_availability_event",
                    "c3_bench_counter", "c3_bench_event")) {
                if (publish) {
                    var physical = db.oracle.queryForMap("select count(*) as rows,"
                            + " pg_total_relation_size(?::regclass) as bytes from " + table, table);
                    storage.printf("%d\t%b\t%s\t%s\t%s%n", workers, publish, table,
                            physical.get("rows"), physical.get("bytes"));
                }
            }
            if (publish) {
                assertDense(db, true, "COMMAND_RESULT_AVAILABLE", (workers > 1 ? 2L : 1L) * (WARMUP + samples));
                for (var name : FAMILIES.subList(1, FAMILIES.size())) {
                    assertDense(db, false, name.name(), WARMUP + samples);
                }
            }
        }
    }

    private static void enroll(ReadParticipantsFixture f, List<Work> jobs) {
        var expected = new EnumMap<Name, Set<UUID>>(Name.class);
        for (var job : jobs)
            expected.computeIfAbsent(job.family(), ignored -> new HashSet<>()).add(job.event());
        for (int pass = 0; pass < 6; pass++) {
            renew(f);
            f.enroll();
            var missing = new EnumMap<Name, Integer>(Name.class);
            expected.forEach((name, ids) -> {
                var definition = f.participants.get(name).definition();
                var actual = new HashSet<>(f.f.read.oracle.queryForList(
                        "select consumable_id from consumption_slot where consumption_id=?",
                        UUID.class, definition.id().value()));
                int omitted = (int) ids.stream().filter(id -> !actual.contains(id)).count();
                if (omitted > 0) missing.put(name, omitted);
            });
            if (missing.isEmpty()) return;
            System.out.println("Discovery needs another pass: missing slots " + missing);
        }
        throw new AssertionError("Discovery did not enroll the complete benchmark corpus");
    }

    private static void renew(ReadParticipantsFixture f) {
        for (var entry : f.leases.entrySet()) {
            var name = Name.valueOf(entry.getKey().split(":")[0]);
            var engine = f.engines.get(name);
            var old = entry.getValue();
            var current = engine.renewPartitions(List.of(old)).stream().findFirst()
                    .orElseGet(() -> engine.acquirePartition(old.partitionId()).orElseThrow());
            entry.setValue(current);
        }
    }

    private record Work(Name family, UUID event, UUID key, Long version) {}
    private record Sample(Name family, int partition, long finalNs, long combinedNs, long cpuNs) {}

    private static String effectTable(Name name) {
        return switch (name) {
            case COMMAND_RESULT -> "read_command_result";
            case IDENTITY_REQUEST_RESULT -> "read_identity_request_result";
            case POT_VERSION -> "read_pot_version";
            case POT_BALANCE -> "read_pot_balance";
            default -> throw new IllegalArgumentException("No cost fixture for " + name);
        };
    }

    private static void assertDense(SubobjectPostgresFixture db, boolean pilot, String family, long expected) {
        String base = pilot ? "read_availability_" : "c3_bench_";
        assertEquals(expected, db.oracle.queryForObject(
                "select position from " + base + "counter where family=?", Long.class, family));
        assertEquals(Map.of("n", expected, "lo", 1L, "hi", expected, "unique_ids", expected),
                db.oracle.queryForMap("select count(*) n, min(position) lo, max(position) hi,"
                        + " count(distinct event_id) unique_ids from " + base + "event where family=?", family));
    }

    private static ConsumptionParticipant experimentalPublication(SubobjectPostgresFixture db,
            ConsumptionParticipant real, Map<UUID, Work> targets) {
        return new ConsumptionParticipant() {
            @Override
            public Optional<Prepared> prepare(UUID event, PreparationReads reads) {
                var work = targets.get(event);
                if (work == null) return real.prepare(event, reads);
                return real.prepare(event, reads).map(prepared -> new Prepared() {
                    @Override
                    public void requireCompletionReady() {
                        prepared.requireCompletionReady();
                        var existing = db.sql.queryForList("""
                                select 1 from c3_bench_event where family=? and projection_key=?
                                    and logical_version is not distinct from cast(? as bigint)
                                """, Integer.class, work.family().name(), work.key(), work.version());
                        assertTrue(existing.isEmpty(), "Cost workload must consist of new effects");
                    }

                    @Override
                    public void finalizeIn(SlotCompletion completion) {
                        // The outer guarded callback is entered before the real participant closes.
                        SpringAttemptTransactions.guarded(db.source, () -> {
                            prepared.finalizeIn(completion);
                            long position = db.sql.queryForObject("""
                                    update c3_bench_counter set position=position+1 where family=? returning position
                                    """, Long.class, work.family().name());
                            assertEquals(1, db.sql.update("""
                                    insert into c3_bench_event(family,position,event_id,projection_key,logical_version)
                                    values (?,?,?,?,?)
                                    """, work.family().name(), position, UUID.randomUUID(), work.key(), work.version()));
                            return null;
                        });
                    }
                });
            }

            @Override
            public Recovery prepareRecovery(UUID event, PreparationReads reads) {
                return real.prepareRecovery(event, reads);
            }
        };
    }

    private void measure(ReadParticipantsFixture f, String profile, int workers, boolean publish,
                         List<Work> jobs, PrintWriter summary, PrintWriter rows, PrintWriter sql, PrintWriter waits)
            throws Exception {
        boolean measured = summary != null;
        var db = f.f.read;
        var samples = new Sample[jobs.size()];
        var ready = new CountDownLatch(workers);
        var start = new CountDownLatch(1);
        var cpu = ManagementFactory.getThreadMXBean();
        assertTrue(cpu.isCurrentThreadCpuTimeSupported());
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < workers; i++) {
                int lane = i;
                tasks.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    for (int j = 0; j < jobs.size(); j++) {
                        var work = jobs.get(j);
                        var definition = f.participants.get(work.family()).definition();
                        int partition = RoutingV1.partition(
                                RoutingV1.hash(definition.family(), work.event()), definition.partitionCount());
                        if (partition % workers != lane) continue;
                        var engine = f.engines.get(work.family());
                        long cpuStart = cpu.getCurrentThreadCpuTime();
                        long began = System.nanoTime();
                        var lease = f.leases.get(work.family() + ":" + partition);
                        var acquisition = engine.acquire(lease, List.of(work.event()));
                        assertEquals(1, acquisition.claims().size(), () -> "Cannot acquire " + work
                                + " " + acquisition + " slot=" + db.oracle.queryForList("""
                                select state, claimed_until, retry_after, clock_timestamp()
                                from consumption_slot where consumption_id=? and consumable_id=?
                                """, definition.id().value(), work.event()));
                        var claim = acquisition.claims().getFirst();
                        long finalStart = System.nanoTime();
                        var result = engine.execute(claim);
                        long done = System.nanoTime();
                        assertEquals(Status.COMMITTED, result.status(), result::toString);
                        samples[j] = new Sample(work.family(), partition, done - finalStart, done - began,
                                cpu.getCurrentThreadCpuTime() - cpuStart);
                    }
                    return null;
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                if (!measured) {
                    start.countDown();
                    for (var task : tasks) task.get(90, TimeUnit.SECONDS);
                    return;
                }
                admin.execute("select pg_stat_statements_reset()");
                String lsn = admin.queryForObject("select pg_current_wal_insert_lsn()::text", String.class);
                int readBefore = db.source.statements.size();
                int writeBefore = f.f.primary.statements.size();
                long deadlocks = deadlocks();
                long pgCpu = cpuMicroseconds();
                var running = new java.util.concurrent.atomic.AtomicBoolean(true);
                var waitSamples = new ArrayList<String>();
                long wall;
                java.time.Instant beganAt, endedAt;
                try (var diagnostics = Executors.newSingleThreadExecutor()) {
                    var observer = diagnostics.submit(() -> {
                        sampleWaits(running, db.runtimeRole, f.f.role, waitSamples);
                        return null;
                    });
                    beganAt = java.time.Instant.now();
                    long clock = System.nanoTime();
                    start.countDown();
                    try {
                        for (var task : tasks) task.get(90, TimeUnit.SECONDS);
                        wall = System.nanoTime() - clock;
                        endedAt = java.time.Instant.now();
                    } finally {
                        running.set(false);
                    }
                    observer.get(10, TimeUnit.SECONDS);
                }
                long cpuDelta = cpuMicroseconds() - pgCpu;
                String endLsn = admin.queryForObject("select pg_current_wal_insert_lsn()::text", String.class);
                long wal = admin.queryForObject(
                        "select pg_wal_lsn_diff(cast(? as pg_lsn), cast(? as pg_lsn))::bigint",
                        Long.class, endLsn, lsn);
                if (inspectWal) inspectWal(profile, publish, lsn, endLsn);
                var read = sqlStats(db.runtimeRole, "READ", profile, workers, publish, sql);
                var write = sqlStats(f.f.role, "WRITE", profile, workers, publish, sql);
                assertTrue(read.commits() >= (long) jobs.size() * 2, read::toString);
                assertTrue(write.commits() >= (long) jobs.size(), write::toString);
                assertEquals(0, read.rollbacks() + write.rollbacks());
                summary.printf(Locale.ROOT, "%s\t%d\t%b\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%.4f\t%.4f\t%s\t%s%n",
                        profile, workers, publish, jobs.size(), wall, wal, cpuDelta,
                        db.source.statements.size() - readBefore, f.f.primary.statements.size() - writeBefore,
                        read.commits(), write.commits(), read.rollbacks(), write.rollbacks(),
                        deadlocks() - deadlocks, read.execMs(), write.execMs(), beganAt, endedAt);
                for (var sample : samples) {
                    rows.printf("%s\t%d\t%b\t%s\t%d\t%d\t%d\t%d%n", profile, workers, publish,
                            sample.family(), sample.partition(), sample.finalNs(), sample.combinedNs(), sample.cpuNs());
                }
                for (var wait : waitSamples) waits.printf("%s\t%d\t%b\t%s%n", profile, workers, publish, wait);
                System.out.println("PASS " + profile + " workers=" + workers + " publication=" + publish);
            } finally {
                start.countDown();
            }
        }
    }

    private void inspectWal(String profile, boolean publish, String fromLsn, String toLsn) throws Exception {
        var data = admin.query("""
                select "resource_manager/record_type", count, record_size, fpi_size, combined_size
                from public.pg_get_wal_stats(cast(? as pg_lsn), cast(? as pg_lsn), true)
                where count > 0 order by combined_size desc
                """, (rs, i) -> String.join("\t", profile, Boolean.toString(publish), fromLsn, toLsn,
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5)),
                fromLsn, toLsn);
        Files.write(output.resolve("wal.tsv"), data, java.nio.file.StandardOpenOption.APPEND);
    }

    private record SqlStats(long commits, long rollbacks, double execMs) {}

    private SqlStats sqlStats(String role, String authority, String profile, int workers, boolean publish,
                              PrintWriter sql) {
        return admin.query("""
                select query, calls, total_exec_time, wal_bytes, wal_records, wal_fpi from public.pg_stat_statements
                where userid=(select oid from pg_roles where rolname=?) and toplevel order by query
                """, rs -> {
                    long commits = 0, rollbacks = 0;
                    double exec = 0;
                    while (rs.next()) {
                        String query = rs.getString(1).strip().replaceAll("\\s+", " ");
                        long calls = rs.getLong(2);
                        double time = rs.getDouble(3);
                        if (query.equalsIgnoreCase("COMMIT")) commits += calls;
                        if (query.equalsIgnoreCase("ROLLBACK")) rollbacks += calls;
                        exec += time;
                        sql.printf(Locale.ROOT, "%s\t%d\t%b\t%s\t%d\t%.4f\t%d\t%d\t%d\t%s%n", profile, workers,
                                publish, authority, calls, time, rs.getLong(4), rs.getLong(5), rs.getLong(6), query);
                    }
                    return new SqlStats(commits, rollbacks, exec);
                }, role);
    }

    private long deadlocks() {
        return admin.queryForObject("select sum(deadlocks)::bigint from pg_stat_database", Long.class);
    }

    private long cpuMicroseconds() throws Exception {
        var result = pg.execInContainer("cat", "/sys/fs/cgroup/cpu.stat");
        assertEquals(0, result.getExitCode(), result.getStderr());
        return result.getStdout().lines().filter(line -> line.startsWith("usage_usec "))
                .mapToLong(line -> Long.parseLong(line.split("\\s+")[1])).findFirst().orElseThrow();
    }

    private void sampleWaits(java.util.concurrent.atomic.AtomicBoolean running, String readRole, String writeRole,
                             List<String> results) throws Exception {
        try (Connection observer = Objects.requireNonNull(admin.getDataSource()).getConnection();
             var query = observer.prepareStatement("""
                     select pid, wait_event_type, wait_event, pg_blocking_pids(pid), query
                     from pg_stat_activity where usename in (?,?)
                         and wait_event_type in ('Lock','LWLock','IO') order by pid
                     """)) {
            query.setString(1, readRole);
            query.setString(2, writeRole);
            while (running.get()) {
                try (var rs = query.executeQuery()) {
                    while (rs.next()) {
                        results.add(rs.getInt(1) + "\t" + rs.getString(2) + "\t" + rs.getString(3)
                                + "\t" + rs.getString(4) + "\t" + rs.getString(5).strip().replaceAll("\\s+", " "));
                    }
                }
                Thread.sleep(5);
            }
        }
    }
}
