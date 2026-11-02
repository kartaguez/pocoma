package com.kartaguez.pocoma.runtime.command.consumption.sequencing;

import static com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingFixture.Family.BUSINESS_EVENT;
import static com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingFixture.Family.COMMAND_TERMINAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingFixture.Collection;
import com.kartaguez.pocoma.runtime.command.consumption.sequencing.TerminalSequencingFixture.Family;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Explicit Step 3B exploratory contention benchmark, separated from correctness test gates.
 *
 * <p>Protocol:
 * <ul>
 *   <li>PostgreSQL 17 disposable container with {@code fsync=on} and {@code statement_timeout=20000}.</li>
 *   <li>Grid: {@code concurrency in {1, 4} x eventCount in {1, 8} x families in {1, 2}}.</li>
 *   <li>Paired alternating runs {@code enabled in {false, true}} over {@code step3b.repeats} (default 3).</li>
 *   <li>Per worker: {@code step3b.warmup} (default 8) warmup transactions, common barrier, then
 *       {@code step3b.measured} (default 40) measured transactions on fresh unpooled JDBC connections.</li>
 *   <li>Validity invariants checked after every run: exact event count, continuous positions {@code 1..N}
 *       per active family when sequencing is enabled, and zero positions when disabled.</li>
 *   <li>Output format: {@code STEP3B CSV,repeat,concurrency,eventCount,families,enabled,samples,txPerSec,latencyUs,waitUs,holdUs}
 *       where each distribution is {@code mean/median/p95/max/sd} in microseconds.</li>
 * </ul>
 */
public final class TerminalSequencingContentionBenchmark {
    private TerminalSequencingContentionBenchmark() {}

    public static void main(String[] args) throws Exception {
        int warmup = Integer.getInteger("step3b.warmup", 8);
        int measuredCount = Integer.getInteger("step3b.measured", 40);
        int repeats = Integer.getInteger("step3b.repeats", 3);
        try (PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
                .withCommand("postgres", "-c", "fsync=on", "-c", "statement_timeout=20000")
                .withDatabaseName("sequencing")
                .withUsername("experiment")
                .withPassword("experiment")) {
            postgres.start();
            DriverManagerDataSource ds = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            TerminalSequencingFixture base = new TerminalSequencingFixture(ds, true);
            base.schema();
            System.out.println("STEP3B ENV " + base.sql.queryForMap(
                "select version(), current_setting('synchronous_commit') synchronous_commit, current_setting('fsync') fsync"));
            runCampaign(ds, base, warmup, measuredCount, repeats);
        }
    }

    static void runCampaign(
            DataSource ds,
            TerminalSequencingFixture base,
            int warmup,
            int measuredCount,
            int repeats) throws Exception {
        System.out.printf(
            Locale.ROOT,
            "STEP3B MEASURE warmup=%d/worker measured=%d/worker repeats=%d; fresh connection/transaction; mean/median/p95/max/sd in us%n",
            warmup, measuredCount, repeats);
        for (int concurrency : List.of(1, 4)) {
            for (int eventCount : List.of(1, 8)) {
                for (int families : List.of(1, 2)) {
                    for (int repeat = 0; repeat < repeats; repeat++) {
                        for (boolean enabled : repeat % 2 == 0 ? List.of(false, true) : List.of(true, false)) {
                            base.reset();
                            TerminalSequencingFixture measured = new TerminalSequencingFixture(ds, enabled);
                            ExecutorService workers = Executors.newFixedThreadPool(concurrency);
                            List<Long> latency = Collections.synchronizedList(new ArrayList<>());
                            List<Long> wait = Collections.synchronizedList(new ArrayList<>());
                            List<Long> hold = Collections.synchronizedList(new ArrayList<>());
                            CountDownLatch ready = new CountDownLatch(concurrency);
                            CountDownLatch start = new CountDownLatch(1);
                            List<Future<?>> tasks = new ArrayList<>();
                            try {
                                for (int worker = 0; worker < concurrency; worker++) {
                                    tasks.add(workers.submit(() -> {
                                        for (int i = 0; i < warmup; i++) {
                                            measureTransaction(measured, eventCount, families, null, null, null);
                                        }
                                        ready.countDown();
                                        TerminalSequencingPostgresTest.await(start);
                                        for (int i = 0; i < measuredCount; i++) {
                                            measureTransaction(measured, eventCount, families, latency, wait, hold);
                                        }
                                    }));
                                }
                                TerminalSequencingPostgresTest.await(ready);
                                long begin = System.nanoTime();
                                start.countDown();
                                for (Future<?> task : tasks) {
                                    task.get(60, TimeUnit.SECONDS);
                                }
                                double seconds = (System.nanoTime() - begin) / 1e9;
                                long expectedPerFamily = (long) (warmup + measuredCount) * concurrency * eventCount;
                                assertEquals(expectedPerFamily * families, measured.count("event"));
                                if (enabled) {
                                    for (Family family : families == 1
                                            ? List.of(BUSINESS_EVENT)
                                            : List.of(BUSINESS_EVENT, COMMAND_TERMINAL)) {
                                        TerminalSequencingPostgresTest.continuous(measured, family, expectedPerFamily);
                                    }
                                } else {
                                    assertEquals(0, measured.count("event_position"));
                                }
                                System.out.printf(
                                    Locale.ROOT,
                                    "STEP3B CSV,%d,%d,%d,%d,%s,%d,%.3f,%s,%s,%s%n",
                                    repeat,
                                    concurrency,
                                    eventCount,
                                    families,
                                    enabled,
                                    latency.size(),
                                    latency.size() / seconds,
                                    stats(latency),
                                    stats(wait),
                                    stats(hold));
                            } finally {
                                start.countDown();
                                workers.shutdownNow();
                                assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS));
                            }
                        }
                    }
                }
            }
        }
    }

    static void measureTransaction(
            TerminalSequencingFixture m,
            int events,
            int families,
            List<Long> latency,
            List<Long> wait,
            List<Long> hold) {
        long begin = System.nanoTime();
        Collection c = m.runner.runInTransaction(() -> {
            for (int i = 0; i < events; i++) {
                m.publish(BUSINESS_EVENT);
                if (families == 2) m.publish(COMMAND_TERMINAL);
            }
            return m.current();
        });
        if (latency != null) {
            latency.add(System.nanoTime() - begin);
            wait.add(c.waitNanos);
            hold.add(c.acquiredNanos == 0 ? 0 : c.endNanos - c.acquiredNanos);
        }
    }

    static String stats(List<Long> values) {
        long[] a = values.stream().mapToLong(Long::longValue).sorted().toArray();
        double mean = Arrays.stream(a).average().orElseThrow();
        double sd = Math.sqrt(Arrays.stream(a).mapToDouble(v -> (v - mean) * (v - mean)).average().orElseThrow());
        return String.format(
            Locale.ROOT,
            "%.1f/%.1f/%.1f/%.1f/%.1f",
            mean / 1000,
            a[(a.length - 1) / 2] / 1000.0,
            a[(int) Math.ceil(a.length * .95) - 1] / 1000.0,
            a[a.length - 1] / 1000.0,
            sd / 1000);
    }
}
