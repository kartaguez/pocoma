package com.kartaguez.pocoma.runtime.command.v3;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Frozen inputs, shared by the deterministic negative oracle and the PostgreSQL campaigns. */
class I5TemporalBudgetTest {
    static final String VERSION = "I5-C-90-v1";
    static final Path CONTRACT = contractPath();
    static final Properties INPUT = load();
    static final String HASH = hash();

    private static Path contractPath() {
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            var path =
                    root.resolve(
                            "docs/testing/evidence/L2-I5-audit-remediation/temporal-contract-v1.properties");
            if (Files.isRegularFile(path)) return path;
        }
        throw new IllegalStateException("missing pre-campaign contract");
    }

    private static Properties load() {
        try (var in = Files.newInputStream(CONTRACT)) {
            var p = new Properties();
            p.load(in);
            if (!VERSION.equals(p.getProperty("version")))
                throw new IllegalStateException("contract version");
            return p;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hash() {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(Files.readAllBytes(CONTRACT)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static Duration limit(String key) {
        return Duration.ofMillis(Long.parseLong(INPUT.getProperty(key + ".ms")));
    }

    static Duration service(int candidates, int retries) {
        return limit("operation")
                .multipliedBy(candidates + retries)
                .plus(limit("idle"))
                .plus(limit("poll").multipliedBy(candidates + retries))
                .plus(limit("jitter"));
    }

    static void feasible(int candidates, int retries, Duration ttl) {
        assertTrue(
                limit("delta")
                                .plus(limit("cadence"))
                                .plus(service(candidates, retries))
                                .plus(limit("safety"))
                                .compareTo(ttl)
                        < 0,
                "C-PRE: deadline incompatible with frozen contract");
    }

    static void below(Duration actual, Duration bound, String oracle) {
        assertTrue(
                !actual.isNegative() && actual.compareTo(bound) < 0,
                oracle + ": " + actual + " >= " + bound);
    }

    static void calls(int actual, int candidates, int retries) {
        assertTrue(actual <= candidates + retries, "C-NEG: excess calls/revalidations");
    }

    static void sameIncarnation(
            Object before,
            Object after,
            java.time.Instant oldUntil,
            java.time.Instant newUntil,
            java.time.Instant dbNow) {
        assertEquals(before, after, "C-PG: incarnation replaced");
        assertTrue(newUntil.isAfter(oldUntil), "C-PG: missing durable extension");
        assertTrue(
                dbNow.plus(limit("safety")).isBefore(oldUntil), "C-PG: late external observation");
    }

    @Test
    void c_pre() {
        assertEquals(90000, limit("lease").toMillis());
        assertEquals(60000, limit("claim").toMillis());
        assertEquals(
                64090,
                limit("delta")
                        .plus(limit("cadence"))
                        .plus(service(8, 1))
                        .plus(limit("safety"))
                        .toMillis());
        for (int n : List.of(2, 4, 8)) feasible(n, 1, limit("lease"));
        for (int h : List.of(1, 4)) feasible(h, 0, limit("claim"));
        assertThrows(AssertionError.class, () -> feasible(8, 0, Duration.ofSeconds(30)));
        assertThrows(
                AssertionError.class,
                () ->
                        feasible(
                                8,
                                1,
                                limit("delta")
                                        .plus(limit("cadence"))
                                        .plus(service(8, 1))
                                        .plus(limit("safety"))));
        assertEquals(64, HASH.length());
        System.out.println(
                "C-PRE contract="
                        + VERSION
                        + " sha256="
                        + HASH
                        + " lease90s ONLY; default30s NOT CERTIFIED");
    }

    @Test
    void c_neg() {
        var calibration = new ArrayList<>(List.of(Duration.ofMillis(3)));
        var bad = Duration.ofSeconds(6);
        calibration.add(bad);
        assertThrows(AssertionError.class, () -> below(bad, limit("operation"), "C-OP"));
        assertThrows(AssertionError.class, () -> below(service(8, 1), service(8, 1), "C-SERVICE"));
        assertThrows(AssertionError.class, () -> calls(10, 8, 1));
        var now = java.time.Instant.parse("2026-10-29T00:00:00Z");
        assertThrows(
                AssertionError.class,
                () -> sameIncarnation("a", "b", now.plusSeconds(10), now.plusSeconds(90), now));
        assertThrows(
                AssertionError.class,
                () -> sameIncarnation("a", "a", now.plusSeconds(1), now.plusSeconds(90), now));
        assertThrows(
                AssertionError.class,
                () -> sameIncarnation("a", "a", now.plusSeconds(10), now.plusSeconds(10), now));
    }
}
