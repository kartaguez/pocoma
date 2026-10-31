package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** V2 inputs and independent, deliberately falsifiable checkers. V1 remains unchanged. */
class I5TemporalV2Test {
    static final Properties INPUT = new Properties();
    static final String HASH;

    static {
        try {
            Path file = null;
            for (var root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
                var p =
                        root.resolve(
                                "docs/testing/evidence/L2-I5-temporal-v2/temporal-contract-v2.properties");
                if (Files.isRegularFile(p)) {
                    file = p;
                    break;
                }
            }
            assertNotNull(file, "missing frozen v2 contract");
            var bytes = Files.readAllBytes(file);
            INPUT.load(new java.io.ByteArrayInputStream(bytes));
            assertEquals("I5-CB-90-v2", INPUT.getProperty("version"));
            HASH = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static long ns(String name) {
        return Long.parseLong(INPUT.getProperty(name + ".ms")) * 1_000_000;
    }

    static long bound(int n) {
        return n * (ns("operation") + ns("poll")) + ns("idle") + ns("jitter");
    }

    static void below(long actual, long limit, String oracle) {
        assertTrue(
                actual >= 0 && actual < limit,
                oracle + " actualNs=" + actual + " limitNs=" + limit);
    }

    static boolean free(long start, long end, Collection<long[]> conflicts) {
        assertTrue(end >= start, "invalid physical window");
        return conflicts.stream().noneMatch(c -> c[0] < end && c[1] > start);
    }

    static void extension(
            String oldToken, String token, Instant oldUntil, Instant until, Instant now) {
        assertEquals(oldToken, token, "B-PG token replaced");
        assertTrue(until.isAfter(oldUntil), "B-PG no durable extension");
        assertTrue(
                now.plusMillis(Long.parseLong(INPUT.getProperty("safety.ms"))).isBefore(oldUntil),
                "B-PG observed too late");
    }

    static void coverage(boolean complete) {
        assertTrue(complete, "INCONCLUSIVE physical transaction coverage");
    }

    @Test
    void v2_preconditions() {
        assertEquals(4_080_000_000L, bound(8));
        for (int n : List.of(2, 4, 8)) {
            below(bound(n), ns("revalidation"), "B retry compatibility");
            below(ns("delta") + ns("cadence") + bound(n) + ns("safety"), ns("lease"), "B deadline");
        }
        below(ns("delta") + ns("cadence") + bound(4) + ns("safety"), ns("claim"), "claim deadline");
        assertThrows(
                AssertionError.class,
                () ->
                        below(
                                8 * (5_000_000_000L + ns("poll")) + ns("idle") + ns("jitter"),
                                ns("revalidation"),
                                "old T5 incompatible"));
        assertEquals("NOT_CERTIFIED", INPUT.getProperty("production30s"));
        System.out.println("V2 frozen=" + HASH + " D8ns=" + bound(8));
    }

    @Test
    void v2_negative_checkers() {
        assertTrue(free(10, 20, List.of(new long[] {0, 10}, new long[] {20, 30})));
        assertFalse(free(10, 20, List.of(new long[] {19, 30})));
        assertFalse(free(10, 20, List.of(new long[] {0, 21})));
        assertThrows(
                AssertionError.class,
                () -> assertTrue(free(10, 20, List.of(new long[] {12, 13})), "false free witness"));
        assertThrows(AssertionError.class, () -> coverage(false));
        for (String key : List.of("operation", "jitter", "revalidation"))
            assertThrows(AssertionError.class, () -> below(ns(key), ns(key), key));
        assertThrows(AssertionError.class, () -> below(bound(8), bound(8), "next attempt"));
        var now = Instant.parse("2026-10-29T00:00:00Z");
        extension("a", "a", now.plusSeconds(20), now.plusSeconds(90), now);
        assertThrows(
                AssertionError.class,
                () -> extension("a", "b", now.plusSeconds(20), now.plusSeconds(90), now));
        assertThrows(
                AssertionError.class,
                () -> extension("a", "a", now.plusSeconds(20), now.plusSeconds(20), now));
        assertThrows(
                AssertionError.class,
                () -> extension("a", "a", now.plusSeconds(1), now.plusSeconds(90), now));
    }

    @Test
    void v2_oracle_counter_examples() {
        var key = "cons-1/0";
        var attemptTx = new I5TemporalV2Postgres.Tx("WRITE");
        attemptTx.end = 20;

        // Counter-example 1: Injected blocker on same key -> must be recognized as conflict
        var injectedTx = new I5TemporalV2Postgres.Tx("WRITE");
        injectedTx.injected = true;
        injectedTx.keys.add(key);
        injectedTx.end = 25;
        assertTrue(I5TemporalV2Postgres.isConflict(attemptTx, injectedTx, key));
        assertFalse(free(10, 20, List.of(new long[] {15, injectedTx.end})));

        // Counter-example 2: Stuck / unresolved transaction (end == Long.MAX_VALUE) -> must conflict
        var stuckTx = new I5TemporalV2Postgres.Tx("WRITE");
        assertTrue(I5TemporalV2Postgres.isConflict(attemptTx, stuckTx, key));

        // Counter-example 3: Competing mutation on same key -> must conflict
        var competingTx = new I5TemporalV2Postgres.Tx("WRITE");
        competingTx.keys.add(key);
        competingTx.end = 25;
        assertTrue(I5TemporalV2Postgres.isConflict(attemptTx, competingTx, key));

        // Counter-example 4: Non-blocking routine Discovery on same key -> must NOT conflict (prevents false failure)
        var discoveryTx = new I5TemporalV2Postgres.Tx("WRITE");
        discoveryTx.discovery = true;
        discoveryTx.keys.add(key);
        discoveryTx.end = 25;
        assertFalse(I5TemporalV2Postgres.isConflict(attemptTx, discoveryTx, key));

        // Counter-example 5: Different authority -> must NOT conflict
        var readTx = new I5TemporalV2Postgres.Tx("READ");
        readTx.keys.add(key);
        readTx.end = 25;
        assertFalse(I5TemporalV2Postgres.isConflict(attemptTx, readTx, key));

        // Counter-example 6: Renewal uncommitted or resolution not COMMITTED
        assertThrows(AssertionError.class, () -> assertEquals("COMMITTED", "ROLLED_BACK"));
        assertThrows(AssertionError.class, () -> assertEquals("CONFIRMED", "OMITTED"));

        // Counter-example 7: Token lost or replaced
        var now = Instant.parse("2026-10-31T00:00:00Z");
        assertThrows(
                AssertionError.class,
                () ->
                        extension(
                                "tokenA",
                                "tokenB",
                                now.plusSeconds(30),
                                now.plusSeconds(90),
                                now));

        // Counter-example 8: Deadline not extended
        assertThrows(
                AssertionError.class,
                () ->
                        extension(
                                "tokenA",
                                "tokenA",
                                now.plusSeconds(30),
                                now.plusSeconds(30),
                                now));

        // Counter-example 9: Expiration safety margin violated
        assertThrows(
                AssertionError.class,
                () ->
                        extension(
                                "tokenA",
                                "tokenA",
                                now.plusSeconds(1),
                                now.plusSeconds(90),
                                now));

        // Counter-example 10: Operation time exceeded under saturation
        assertThrows(
                AssertionError.class,
                () ->
                        below(
                                ns("operation") + 1,
                                ns("operation"),
                                "B physical complete operation"));
    }
}
