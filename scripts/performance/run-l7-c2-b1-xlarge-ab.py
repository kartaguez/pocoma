#!/usr/bin/env python3
"""Pocoma V3 — L7-C2-B1.4 Very Large (500 shareholders, 10,000 expenses, 100 versions) A/B Store Qualification.

Runs the real production JdbcPotVersionStore of:
  - Baseline A (commit b8924f3d, V1+V2 JSONB)
  - Candidate B (feat/v3-l7 HEAD, V1+V2+V3 Radix-16)

Against identical disposable PostgreSQL 17 containers on the exact same 500/10,000 dataset
to isolate PotVersion-specific storage, WAL (pg_wal_lsn_diff), incremental/fallback write
latencies, full & targeted read latencies (R1, R3a, R3b, R3c), and F4 unreachable content.
"""

import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_PG_IMAGE = "postgres@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7"
F4_SQL_PATH = ROOT / "scripts/v3/diagnose-pot-version-unreachable.sql"

JAVA_DRIVER_BASELINE_A = r"""
import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotVersionStore;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.sql.Connection;
import java.util.*;

public final class XLargeDriverA {
    static double quantile(long[] sortedNs, double q) {
        if (sortedNs.length == 0) return 0.0;
        int idx = Math.min(sortedNs.length - 1, Math.max(0, (int) Math.ceil(q * sortedNs.length) - 1));
        return Math.round((sortedNs[idx] / 1e6) * 1000.0) / 1000.0;
    }

    static String statsJson(List<Long> samples) {
        long[] arr = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        return String.format(Locale.ROOT,
            "{\"n\":%d,\"p50\":%.3f,\"p95\":%.3f,\"p99\":%.3f,\"max\":%.3f}",
            arr.length, quantile(arr, 0.50), quantile(arr, 0.95), quantile(arr, 0.99),
            arr.length == 0 ? 0.0 : Math.round((arr[arr.length - 1] / 1e6) * 1000.0) / 1000.0);
    }

    static String storageJson(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
            "SELECT jsonb_build_object(" +
            "'read_pot_version', jsonb_build_object(" +
            "  'rows', (SELECT count(*) FROM v3_read.read_pot_version)," +
            "  'heapBytes', pg_relation_size('v3_read.read_pot_version'::regclass)," +
            "  'indexBytes', pg_indexes_size('v3_read.read_pot_version'::regclass)," +
            "  'toastBytes', pg_total_relation_size('v3_read.read_pot_version'::regclass) - pg_relation_size('v3_read.read_pot_version'::regclass) - pg_indexes_size('v3_read.read_pot_version'::regclass)," +
            "  'totalBytes', pg_total_relation_size('v3_read.read_pot_version'::regclass)" +
            "))::text", String.class);
    }

    public static void main(String[] args) throws Exception {
        String jdbcUrl = args[0];
        int shCount = Integer.parseInt(args[1]);
        int expCount = Integer.parseInt(args[2]);
        int versions = Integer.parseInt(args[3]);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername("pocoma_read");
        cfg.setPassword("pocoma-local-only");
        cfg.setMaximumPoolSize(4);

        HikariConfig adminCfg = new HikariConfig();
        adminCfg.setJdbcUrl(jdbcUrl);
        adminCfg.setUsername("pocoma");
        adminCfg.setPassword("pocoma-local-only");
        adminCfg.setMaximumPoolSize(2);

        try (HikariDataSource ds = new HikariDataSource(cfg);
             HikariDataSource adminDs = new HikariDataSource(adminCfg)) {
            JdbcTemplate admin = new JdbcTemplate(adminDs);
            var tm = new DataSourceTransactionManager(ds);
            tm.setNestedTransactionAllowed(false);
            var tx = new SpringAttemptTransactions(ds, tm);
            var store = new JdbcPotVersionStore(ds, tx);

            Random rng = new Random(42L);
            UUID potId = new UUID(0x11111111L, 0x22222222L);
            List<PotVersion.Shareholder> shs = new ArrayList<>(shCount);
            Set<UUID> authUsers = new LinkedHashSet<>();
            Set<UUID> attUsers = new LinkedHashSet<>();
            for (int i = 0; i < shCount; i++) {
                UUID sid = new UUID(rng.nextLong(), rng.nextLong());
                shs.add(new PotVersion.Shareholder(sid, 1L, 1L, 1L, false, i < 50));
                if (i < 50) {
                    UUID uid = new UUID(rng.nextLong(), rng.nextLong());
                    authUsers.add(uid);
                    attUsers.add(uid);
                }
            }
            List<PotVersion.Expense> exps = new ArrayList<>(expCount);
            for (int i = 0; i < expCount; i++) {
                UUID eid = new UUID(rng.nextLong(), rng.nextLong());
                exps.add(new PotVersion.Expense(eid, 1L, 1L, 1L, false));
            }

            Map<Integer, String> growthCurve = new LinkedHashMap<>();
            List<Long> writeSamples = new ArrayList<>();

            // v=1 initial
            PotVersion v1 = new PotVersion(potId, 1L, 1L, 1L, false, shs, exps, authUsers, attUsers);
            long t0 = System.nanoTime();
            tx.write(() -> { store.insertAndAdvance(v1); SpringAttemptTransactions.closePublication(ds); return null; });
            long initNs = System.nanoTime() - t0;
            growthCurve.put(1, storageJson(admin));

            String walStart = admin.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
            for (int v = 2; v <= versions; v++) {
                int idx = (v * 97) % exps.size();
                PotVersion.Expense prev = exps.get(idx);
                exps.set(idx, new PotVersion.Expense(prev.id(), prev.headerVersion(), prev.detailsVersion() + 1, prev.financialsVersion(), false));
                final long ver = v;
                PotVersion next = new PotVersion(potId, ver, 1L, 1L, false, List.copyOf(shs), List.copyOf(exps), authUsers, attUsers);
                long s0 = System.nanoTime();
                tx.write(() -> { store.insertAndAdvance(next); SpringAttemptTransactions.closePublication(ds); return null; });
                writeSamples.add(System.nanoTime() - s0);
                if (v == 25 || v == 50 || v == versions) {
                    growthCurve.put(v, storageJson(admin));
                }
            }
            String walEnd = admin.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
            long walBytes = admin.queryForObject("SELECT pg_wal_lsn_diff(?::pg_lsn, ?::pg_lsn)::bigint", Long.class, walEnd, walStart);

            // Warm reads (30 iterations each)
            List<Long> readFullCur = new ArrayList<>();
            List<Long> readFullHist = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                tx.read(c -> store.read(potId, versions, c).orElseThrow());
                tx.read(c -> store.read(potId, Math.max(1, versions / 2), c).orElseThrow());
            }
            for (int i = 0; i < 30; i++) {
                long r0 = System.nanoTime();
                PotVersion p = tx.read(c -> store.read(potId, versions, c).orElseThrow());
                readFullCur.add(System.nanoTime() - r0);
                if (p.expenses().size() != expCount) throw new AssertionError("bad size");
                long r1 = System.nanoTime();
                tx.read(c -> store.read(potId, Math.max(1, versions / 2), c).orElseThrow());
                readFullHist.add(System.nanoTime() - r1);
            }

            StringBuilder curveJson = new StringBuilder("{");
            boolean first = true;
            for (var e : growthCurve.entrySet()) {
                if (!first) curveJson.append(",");
                first = false;
                curveJson.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            }
            curveJson.append("}");

            System.out.printf(Locale.ROOT,
                "RESULT_JSON:{\"side\":\"baseline-a\",\"shareholders\":%d,\"expenses\":%d,\"versions\":%d," +
                "\"initWriteMs\":%.3f,\"incrementalWriteMs\":%s," +
                "\"walTotalBytes\":%d,\"walBytesPerVersion\":%.1f," +
                "\"readFullCurrentMs\":%s,\"readFullHistoricalMs\":%s," +
                "\"storageGrowth\":%s}%n",
                shCount, expCount, versions,
                initNs / 1e6, statsJson(writeSamples),
                walBytes, walBytes / (double) (versions - 1),
                statsJson(readFullCur), statsJson(readFullHist),
                curveJson);
        }
    }
}
"""

JAVA_DRIVER_CANDIDATE_B = r"""
import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.radix.ContentHash;
import com.kartaguez.pocoma.domain.projection.radix.PotUserSet;
import com.kartaguez.pocoma.domain.projection.radix.Radix16Codec;
import com.kartaguez.pocoma.domain.projection.radix.Radix16Node;
import com.kartaguez.pocoma.domain.projection.radix.Radix16Tree;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotVersionStore;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.PotVersionManifest;
import com.kartaguez.pocoma.port.projection.PotVersionWritePlan;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.*;

public final class XLargeDriverB {
    static double quantile(long[] sortedNs, double q) {
        if (sortedNs.length == 0) return 0.0;
        int idx = Math.min(sortedNs.length - 1, Math.max(0, (int) Math.ceil(q * sortedNs.length) - 1));
        return Math.round((sortedNs[idx] / 1e6) * 1000.0) / 1000.0;
    }

    static String statsJson(List<Long> samples) {
        long[] arr = samples.stream().mapToLong(Long::longValue).sorted().toArray();
        return String.format(Locale.ROOT,
            "{\"n\":%d,\"p50\":%.3f,\"p95\":%.3f,\"p99\":%.3f,\"max\":%.3f}",
            arr.length, quantile(arr, 0.50), quantile(arr, 0.95), quantile(arr, 0.99),
            arr.length == 0 ? 0.0 : Math.round((arr[arr.length - 1] / 1e6) * 1000.0) / 1000.0);
    }

    static String storageJson(JdbcTemplate jdbc) {
        return jdbc.queryForObject(
            "SELECT jsonb_build_object(" +
            "'read_pot_version', jsonb_build_object(" +
            "  'rows', (SELECT count(*) FROM v3_read.read_pot_version)," +
            "  'heapBytes', pg_relation_size('v3_read.read_pot_version'::regclass)," +
            "  'indexBytes', pg_indexes_size('v3_read.read_pot_version'::regclass)," +
            "  'toastBytes', pg_total_relation_size('v3_read.read_pot_version'::regclass) - pg_relation_size('v3_read.read_pot_version'::regclass) - pg_indexes_size('v3_read.read_pot_version'::regclass)," +
            "  'totalBytes', pg_total_relation_size('v3_read.read_pot_version'::regclass)" +
            ")," +
            "'read_pot_version_node', jsonb_build_object(" +
            "  'rows', (SELECT count(*) FROM v3_read.read_pot_version_node)," +
            "  'heapBytes', pg_relation_size('v3_read.read_pot_version_node'::regclass)," +
            "  'indexBytes', pg_indexes_size('v3_read.read_pot_version_node'::regclass)," +
            "  'toastBytes', pg_total_relation_size('v3_read.read_pot_version_node'::regclass) - pg_relation_size('v3_read.read_pot_version_node'::regclass) - pg_indexes_size('v3_read.read_pot_version_node'::regclass)," +
            "  'totalBytes', pg_total_relation_size('v3_read.read_pot_version_node'::regclass)" +
            ")," +
            "'read_pot_user_set', jsonb_build_object(" +
            "  'rows', (SELECT count(*) FROM v3_read.read_pot_user_set)," +
            "  'heapBytes', pg_relation_size('v3_read.read_pot_user_set'::regclass)," +
            "  'indexBytes', pg_indexes_size('v3_read.read_pot_user_set'::regclass)," +
            "  'toastBytes', pg_total_relation_size('v3_read.read_pot_user_set'::regclass) - pg_relation_size('v3_read.read_pot_user_set'::regclass) - pg_indexes_size('v3_read.read_pot_user_set'::regclass)," +
            "  'totalBytes', pg_total_relation_size('v3_read.read_pot_user_set'::regclass)" +
            "))::text", String.class);
    }

    public static void main(String[] args) throws Exception {
        String jdbcUrl = args[0];
        int shCount = Integer.parseInt(args[1]);
        int expCount = Integer.parseInt(args[2]);
        int versions = Integer.parseInt(args[3]);

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername("pocoma_read");
        cfg.setPassword("pocoma-local-only");
        cfg.setMaximumPoolSize(4);

        HikariConfig adminCfg = new HikariConfig();
        adminCfg.setJdbcUrl(jdbcUrl);
        adminCfg.setUsername("pocoma");
        adminCfg.setPassword("pocoma-local-only");
        adminCfg.setMaximumPoolSize(2);

        try (HikariDataSource ds = new HikariDataSource(cfg);
             HikariDataSource adminDs = new HikariDataSource(adminCfg)) {
            JdbcTemplate admin = new JdbcTemplate(adminDs);
            var tm = new DataSourceTransactionManager(ds);
            tm.setNestedTransactionAllowed(false);
            var tx = new SpringAttemptTransactions(ds, tm);
            var store = new JdbcPotVersionStore(ds, tx);

            Random rng = new Random(42L);
            UUID potId = new UUID(0x11111111L, 0x22222222L);
            List<PotVersion.Shareholder> shs = new ArrayList<>(shCount);
            Set<UUID> authUsers = new LinkedHashSet<>();
            Set<UUID> attUsers = new LinkedHashSet<>();
            for (int i = 0; i < shCount; i++) {
                UUID sid = new UUID(rng.nextLong(), rng.nextLong());
                shs.add(new PotVersion.Shareholder(sid, 1L, 1L, 1L, false, i < 50));
                if (i < 50) {
                    UUID uid = new UUID(rng.nextLong(), rng.nextLong());
                    authUsers.add(uid);
                    attUsers.add(uid);
                }
            }
            List<PotVersion.Expense> exps = new ArrayList<>(expCount);
            for (int i = 0; i < expCount; i++) {
                UUID eid = new UUID(rng.nextLong(), rng.nextLong());
                exps.add(new PotVersion.Expense(eid, 1L, 1L, 1L, false));
            }

            Map<Integer, String> growthCurve = new LinkedHashMap<>();
            List<Long> incWriteSamples = new ArrayList<>();

            // v=1 initial (full build)
            PotVersion v1 = new PotVersion(potId, 1L, 1L, 1L, false, shs, exps, authUsers, attUsers);
            long t0 = System.nanoTime();
            tx.write(() -> { store.insertAndAdvance(v1); SpringAttemptTransactions.closePublication(ds); return null; });
            long initNs = System.nanoTime() - t0;
            growthCurve.put(1, storageJson(admin));

            String walStart = admin.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
            long totalNodesInserted = 0;
            long totalNodesLoaded = 0;
            for (int v = 2; v <= versions; v++) {
                int idx = (v * 97) % exps.size();
                PotVersion.Expense prev = exps.get(idx);
                PotVersion.Expense updated = new PotVersion.Expense(prev.id(), prev.headerVersion(), prev.detailsVersion() + 1, prev.financialsVersion(), false);
                exps.set(idx, updated);
                final long ver = v;
                long s0 = System.nanoTime();
                PotVersionManifest ref = tx.read(c -> store.readManifestLatestBefore(potId, ver, 1L, c).orElseThrow());
                Radix16Tree.BuildResult inc = Radix16Tree.applyIncremental(
                    'E',
                    ref.expensesRoot(),
                    Map.of(updated.id(), Radix16Codec.encodeExpense(updated)),
                    Set.of(),
                    hashes -> tx.read(c -> store.loadNodes(hashes, c))
                );
                totalNodesInserted += inc.newNodes().size();
                totalNodesLoaded += inc.nodesLoaded();
                PotVersionManifest manifest = new PotVersionManifest(
                    potId, ver, 1, 1L, 1L, false,
                    ref.shareholdersCount(), ref.expensesCount(),
                    ref.shareholdersRoot(), inc.rootHash(),
                    ref.authorizedUsersHash(), ref.attachedActiveUsersHash(),
                    ref.authorizedUserIds(), ref.attachedActiveUserIds()
                );
                PotVersionWritePlan plan = new PotVersionWritePlan(
                    manifest,
                    List.of(),
                    Radix16Tree.groupBottomUpByDepth(inc.newNodes().values()),
                    inc.nodesLoaded(),
                    inc.nodesReused()
                );
                tx.write(() -> { store.publishPrepared(plan); SpringAttemptTransactions.closePublication(ds); return null; });
                incWriteSamples.add(System.nanoTime() - s0);
                if (v == 25 || v == 50 || v == versions) {
                    growthCurve.put(v, storageJson(admin));
                }
            }
            String walEnd = admin.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
            long walBytes = admin.queryForObject("SELECT pg_wal_lsn_diff(?::pg_lsn, ?::pg_lsn)::bigint", Long.class, walEnd, walStart);

            // Also measure 1 full fallback build at v = versions + 1
            PotVersion vFallback = new PotVersion(potId, versions + 1L, 1L, 2L, false, shs, exps, authUsers, attUsers);
            long fb0 = System.nanoTime();
            tx.write(() -> { store.insertAndAdvance(vFallback); SpringAttemptTransactions.closePublication(ds); return null; });
            long fallbackNs = System.nanoTime() - fb0;

            // Warm reads (30 iterations each): R1 full cur, R1 full hist, R3a shareholders, R3b 1 expense, R3c 50 expenses
            UUID oneExp = exps.get(expCount / 2).id();
            List<UUID> fiftyExps = exps.subList(0, 50).stream().map(PotVersion.Expense::id).toList();
            for (int i = 0; i < 5; i++) {
                tx.read(c -> store.read(potId, versions, c).orElseThrow());
                tx.read(c -> store.read(potId, Math.max(1, versions / 2), c).orElseThrow());
                tx.read(c -> store.readShareholders(potId, versions, c).orElseThrow());
                tx.read(c -> store.readExpenses(potId, versions, List.of(oneExp), c).orElseThrow());
                tx.read(c -> store.readExpenses(potId, versions, fiftyExps, c).orElseThrow());
            }
            List<Long> r1Cur = new ArrayList<>(), r1Hist = new ArrayList<>(), r3a = new ArrayList<>(), r3b = new ArrayList<>(), r3c = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                long a0 = System.nanoTime();
                var p = tx.read(c -> store.read(potId, versions, c).orElseThrow());
                r1Cur.add(System.nanoTime() - a0);
                if (p.expenses().size() != expCount) throw new AssertionError("bad size");

                long a1 = System.nanoTime();
                tx.read(c -> store.read(potId, Math.max(1, versions / 2), c).orElseThrow());
                r1Hist.add(System.nanoTime() - a1);

                long a2 = System.nanoTime();
                var shv = tx.read(c -> store.readShareholders(potId, versions, c).orElseThrow());
                r3a.add(System.nanoTime() - a2);
                if (shv.shareholders().size() != shCount) throw new AssertionError("bad sh");

                long a3 = System.nanoTime();
                var e1 = tx.read(c -> store.readExpenses(potId, versions, List.of(oneExp), c).orElseThrow());
                r3b.add(System.nanoTime() - a3);
                if (e1.found().size() != 1) throw new AssertionError("bad e1");

                long a4 = System.nanoTime();
                var e50 = tx.read(c -> store.readExpenses(potId, versions, fiftyExps, c).orElseThrow());
                r3c.add(System.nanoTime() - a4);
                if (e50.found().size() != 50) throw new AssertionError("bad e50");
            }

            StringBuilder curveJson = new StringBuilder("{");
            boolean first = true;
            for (var e : growthCurve.entrySet()) {
                if (!first) curveJson.append(",");
                first = false;
                curveJson.append("\"").append(e.getKey()).append("\":").append(e.getValue());
            }
            curveJson.append("}");

            System.out.printf(Locale.ROOT,
                "RESULT_JSON:{\"side\":\"candidate-b\",\"shareholders\":%d,\"expenses\":%d,\"versions\":%d," +
                "\"initWriteMs\":%.3f,\"fallbackFullWriteMs\":%.3f,\"incrementalWriteMs\":%s," +
                "\"avgNodesInsertedPerVersion\":%.2f,\"avgNodesLoadedPerVersion\":%.2f," +
                "\"walTotalBytes\":%d,\"walBytesPerVersion\":%.1f," +
                "\"readFullCurrentMs\":%s,\"readFullHistoricalMs\":%s," +
                "\"readR3aShareholdersMs\":%s,\"readR3bExpense1Ms\":%s,\"readR3cExpense50Ms\":%s," +
                "\"storageGrowth\":%s}%n",
                shCount, expCount, versions,
                initNs / 1e6, fallbackNs / 1e6, statsJson(incWriteSamples),
                totalNodesInserted / (double) (versions - 1), totalNodesLoaded / (double) (versions - 1),
                walBytes, walBytes / (double) (versions - 1),
                statsJson(r1Cur), statsJson(r1Hist),
                statsJson(r3a), statsJson(r3b), statsJson(r3c),
                curveJson);
        }
    }
}
"""


def build_classpath(app_dir: Path) -> str:
    cp_file = Path(tempfile.mkdtemp(prefix="pocoma-cp-")) / "cp.txt"
    subprocess.run(
        [
            "./mvnw", "--batch-mode", "--no-transfer-progress",
            "-pl", "infra-postgres-read", "-am", "test-compile",
            "dependency:build-classpath", f"-Dmdep.outputFile={cp_file}",
        ],
        cwd=app_dir, check=True, capture_output=True, text=True,
    )
    jars = cp_file.read_text().strip()
    mod_classes = [
        str(app_dir / m / "target/classes")
        for m in (
            "infra-postgres-read", "infra-postgres-core", "infra-tx-spring",
            "port-projection", "port-transaction", "domain-projection", "domain-pot", "domain-identity",
        )
    ]
    return ":".join(mod_classes + ([jars] if jars else []))


def run_side(side, app_dir: Path, pg_image, sh_count, exp_count, versions, driver_src, driver_class, apply_v3):
    container = f"pocoma-xlarge-{side}-{uuid.uuid4().hex[:8]}"
    work = Path(tempfile.mkdtemp(prefix=f"pocoma-xlarge-{side}-"))
    try:
        subprocess.run(
            [
                "docker", "run", "-d", "--rm", "--name", container,
                "-e", "POSTGRES_USER=pocoma",
                "-e", "POSTGRES_PASSWORD=pocoma-local-only",
                "-e", "POSTGRES_DB=pocoma",
                "--tmpfs", "/var/lib/postgresql/data:size=2g",
                "-p", "127.0.0.1::5432",
                pg_image,
            ],
            check=True, capture_output=True, text=True,
        )
        # Wait for real SQL readiness (past initdb restart)
        for _ in range(60):
            if subprocess.run(
                ["docker", "exec", container, "psql", "-X", "-U", "pocoma", "-d", "pocoma", "-c", "SELECT 1"],
                capture_output=True,
            ).returncode == 0:
                break
            time.sleep(0.5)
        port_out = subprocess.check_output(["docker", "port", container, "5432/tcp"], text=True).strip()
        host_port = port_out.splitlines()[0].rsplit(":", 1)[1]

        def psql(db, sql, schema="public"):
            subprocess.run(
                [
                    "docker", "exec", "-i", "-e", f"PGOPTIONS=-c search_path={schema},public",
                    container, "psql", "-X", "-U", "pocoma", "-d", db, "-v", "ON_ERROR_STOP=1", "-f", "-",
                ],
                input=sql, check=True, capture_output=True, text=True,
            )

        psql("pocoma", "CREATE DATABASE pocoma_read;")
        psql("pocoma_read", "CREATE EXTENSION IF NOT EXISTS pgcrypto; CREATE ROLE pocoma_read LOGIN PASSWORD 'pocoma-local-only'; CREATE SCHEMA v3_read AUTHORIZATION pocoma; GRANT USAGE ON SCHEMA v3_read TO pocoma_read; ALTER ROLE pocoma_read IN DATABASE pocoma_read SET search_path = v3_read, public;")
        migs = [
            "infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql",
            "infra-postgres-read/src/main/resources/db/read/migration/V2__pot_balance_aggregate.sql",
        ]
        if apply_v3:
            migs.append("infra-postgres-read/src/main/resources/db/read/migration/V3__pot_version_radix16.sql")
        for m in migs:
            sql = (app_dir / m).read_text(encoding="utf-8")
            sql = (
                sql.replace("${expectedDatabase}", "pocoma_read")
                .replace("${expectedSchema}", "v3_read")
                .replace("${expectedAdmin}", "pocoma")
                .replace("${expectedRuntime}", "pocoma_read")
            )
            psql("pocoma_read", sql, schema="v3_read")

        cp = build_classpath(app_dir)
        src_file = work / f"{driver_class}.java"
        src_file.write_text(driver_src, encoding="utf-8")
        jc = subprocess.run(["javac", "-cp", cp, str(src_file)], capture_output=True, text=True)
        if jc.returncode != 0:
            raise RuntimeError(f"javac failed:\n{jc.stderr}")

        jdbc_url = f"jdbc:postgresql://127.0.0.1:{host_port}/pocoma_read?currentSchema=v3_read"
        proc = subprocess.run(
            ["java", "-Xms512m", "-Xmx1536m", "-cp", f"{work}:{cp}", driver_class, jdbc_url, str(sh_count), str(exp_count), str(versions)],
            check=True, capture_output=True, text=True, timeout=600,
        )
        json_lines = [ln[len("RESULT_JSON:"):] for ln in proc.stdout.splitlines() if ln.startswith("RESULT_JSON:")]
        if not json_lines:
            raise RuntimeError(f"Missing RESULT_JSON in output:\n{proc.stdout[-1000:]}")
        result = json.loads(json_lines[-1])

        if apply_v3:
            f4_proc = subprocess.run(
                [
                    "docker", "exec", "-i", container,
                    "psql", "-X", "-U", "pocoma", "-d", "pocoma_read",
                    "-v", "ON_ERROR_STOP=1", "-v", "read_schema=v3_read", "-t", "-A", "-f", "-",
                ],
                input=F4_SQL_PATH.read_text(encoding="utf-8"),
                check=True, capture_output=True, text=True,
            )
            txt = f4_proc.stdout.strip()
            result["f4Diagnostic"] = json.loads(txt[txt.find("{"):txt.rfind("}") + 1])
        return result
    finally:
        subprocess.run(["docker", "rm", "-f", container], capture_output=True)
        shutil.rmtree(work, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-app-dir", required=True)
    parser.add_argument("--candidate-app-dir", default=str(ROOT / "app"))
    parser.add_argument("--shareholders", type=int, default=500)
    parser.add_argument("--expenses", type=int, default=10000)
    parser.add_argument("--versions", type=int, default=100)
    parser.add_argument("--postgres-image", default=DEFAULT_PG_IMAGE)
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    print(f"[xlarge-ab] Running Baseline A ({args.shareholders} sh, {args.expenses} exp, {args.versions} versions)...")
    res_a = run_side(
        "a", Path(args.baseline_app_dir).resolve(), args.postgres_image,
        args.shareholders, args.expenses, args.versions,
        JAVA_DRIVER_BASELINE_A, "XLargeDriverA", apply_v3=False,
    )
    print(f"[xlarge-ab] Running Candidate B ({args.shareholders} sh, {args.expenses} exp, {args.versions} versions)...")
    res_b = run_side(
        "b", Path(args.candidate_app_dir).resolve(), args.postgres_image,
        args.shareholders, args.expenses, args.versions,
        JAVA_DRIVER_CANDIDATE_B, "XLargeDriverB", apply_v3=True,
    )
    summary = {"baselineA": res_a, "candidateB": res_b}
    (out / "xlarge_ab_manifest.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
