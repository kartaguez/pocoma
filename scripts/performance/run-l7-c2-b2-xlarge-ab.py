#!/usr/bin/env python3
"""Pocoma V3 — L7-C2-B2.4 XLARGE (500 shareholders, 100 versions) A/B Store Qualification
and Aggregate Granularity Analysis (shareholders vs buckets).

Runs the real production JdbcPotBalanceStore of:
  - Baseline A (commit e25cd84b, V1+V2+V3 JSONB)
  - Candidate V4 (feat/v3-l7 HEAD, V1+V2+V3+V4 binary content-addressed)

Against identical disposable PostgreSQL 17 containers on the exact same 500-shareholder
dataset (100 Situation D financial versions + 50 Situation A identical versions) to isolate:
  - read_pot_balance, read_pot_balance_content, read_pot_balance_aggregate, read_pot_balance_aggregate_content
    (heap, index, TOAST, total bytes, row counts)
  - WAL bytes (pg_wal_lsn_diff) in Situation D and Situation A
  - Write latencies (p50/p95/p99) and historical read latencies (p50/p95/p99)
  - F4 unreachable content diagnostic
  - Section 7 granularity analysis (shareholders vs buckets bytes for G=1, G=61, G=240)
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
F4_BALANCE_SQL = ROOT / "scripts/v3/diagnose-pot-balance-unreachable.sql"

JAVA_DRIVER = r"""
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketKey;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketValue;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.ShareholderEntry;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregateCalculator;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotBalanceStore;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.port.projection.PotBalanceStore;
import com.kartaguez.pocoma.port.transaction.RecoveryReadContext;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.math.BigInteger;
import java.sql.Connection;
import java.util.*;

public final class XLargeBalanceDriver {
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

    static String tableJson(JdbcTemplate jdbc, String table) {
        String exists = jdbc.queryForObject("SELECT to_regclass('v3_read." + table + "')::text", String.class);
        if (exists == null) return "null";
        return jdbc.queryForObject(
            "SELECT jsonb_build_object(" +
            "  'rows', (SELECT count(*) FROM v3_read." + table + ")," +
            "  'heapBytes', pg_relation_size('v3_read." + table + "'::regclass)," +
            "  'indexBytes', pg_indexes_size('v3_read." + table + "'::regclass)," +
            "  'toastBytes', pg_total_relation_size('v3_read." + table + "'::regclass) - pg_relation_size('v3_read." + table + "'::regclass) - pg_indexes_size('v3_read." + table + "'::regclass)," +
            "  'totalBytes', pg_total_relation_size('v3_read." + table + "'::regclass)" +
            ")::text", String.class);
    }

    static String storageJson(JdbcTemplate jdbc) {
        return "{"
            + "\"read_pot_shareholder_set\":" + tableJson(jdbc, "read_pot_shareholder_set") + ","
            + "\"read_pot_balance\":" + tableJson(jdbc, "read_pot_balance") + ","
            + "\"read_pot_balance_content\":" + tableJson(jdbc, "read_pot_balance_content") + ","
            + "\"read_pot_balance_aggregate\":" + tableJson(jdbc, "read_pot_balance_aggregate") + ","
            + "\"read_pot_balance_aggregate_content\":" + tableJson(jdbc, "read_pot_balance_aggregate_content")
            + "}";
    }

    static String lsn(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT pg_current_wal_lsn()::text", String.class);
    }

    static long walDiff(JdbcTemplate jdbc, String start, String end) {
        return jdbc.queryForObject("SELECT pg_wal_lsn_diff(?::pg_lsn, ?::pg_lsn)::bigint", Long.class, end, start);
    }

    public static void main(String[] args) throws Exception {
        String jdbcUrl = args[0];
        int shCount = Integer.parseInt(args[1]);
        int sitDVersions = Integer.parseInt(args[2]);
        int sitAVersions = Integer.parseInt(args[3]);
        boolean isV4 = Boolean.parseBoolean(args[4]);

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
            var store = new JdbcPotBalanceStore(ds, tx);
            var calc = new PotBalanceAggregateCalculator();

            Random rng = new Random(42L);
            UUID potId = new UUID(0x11111111L, 0x22222222L);
            List<UUID> sids = new ArrayList<>(shCount);
            for (int i = 0; i < shCount; i++) {
                sids.add(new UUID(rng.nextLong(), rng.nextLong()));
            }
            sids.sort(SubobjectKey::compareUuid);

            // Build initial aggregate with 1 full-default bucket and 500 shareholders
            List<Integer> allIndices = new ArrayList<>(shCount);
            for (int i = 0; i < shCount; i++) {
                allIndices.add(i);
            }
            BucketKey fullKey = new BucketKey(allIndices, Fraction.ZERO);

            // Situation D: sitDVersions distinct financial mutations
            List<Long> sitDWriteNs = new ArrayList<>(sitDVersions);
            String lsnD0 = lsn(admin);
            long[] fixedNets = new long[shCount];
            long bucketTotal = 0L;
            PotBalanceAggregate lastAgg = null;
            PotBalance lastBal = null;

            for (int v = 1; v <= sitDVersions; v++) {
                int payer = (v - 1) % shCount;
                long amount = 100L + v;
                fixedNets[payer] += amount;
                bucketTotal += amount;

                List<ShareholderEntry> verEntries = new ArrayList<>(shCount);
                for (int i = 0; i < shCount; i++) {
                    verEntries.add(new ShareholderEntry(
                        sids.get(i), Fraction.ONE, false, new Fraction(BigInteger.valueOf(fixedNets[i]), BigInteger.ONE)));
                }
                Map<BucketKey, BucketValue> buckets = Map.of(
                    fullKey, new BucketValue(new Fraction(BigInteger.valueOf(bucketTotal), BigInteger.ONE), Map.of())
                );
                PotBalanceAggregate agg = new PotBalanceAggregate(potId, (long) v, verEntries, buckets);
                PotBalance bal = calc.calculate(agg);
                lastAgg = agg;
                lastBal = bal;

                long t0 = System.nanoTime();
                tx.write(() -> {
                    __WRITE_SIT_D__
                    SpringAttemptTransactions.closePublication(ds);
                    return null;
                });
                sitDWriteNs.add(System.nanoTime() - t0);
            }
            String lsnD1 = lsn(admin);
            long sitDWal = walDiff(admin, lsnD0, lsnD1);
            String storageAfterD = storageJson(admin);

            // Historical reads (v=1, v=mid, v=last)
            long midVer = Math.max(1L, sitDVersions / 2L);
            for (int w = 0; w < 5; w++) {
                final long fv = sitDVersions;
                tx.read(c -> { store.read(potId, fv, c); return null; });
            }
            List<Long> readCurNs = new ArrayList<>(30);
            List<Long> readMidNs = new ArrayList<>(30);
            List<Long> readV1Ns = new ArrayList<>(30);
            for (int i = 0; i < 30; i++) {
                final long fv = sitDVersions;
                long t0 = System.nanoTime();
                tx.read(c -> { store.read(potId, fv, c).orElseThrow(); return null; });
                readCurNs.add(System.nanoTime() - t0);

                t0 = System.nanoTime();
                tx.read(c -> { store.read(potId, midVer, c).orElseThrow(); return null; });
                readMidNs.add(System.nanoTime() - t0);

                t0 = System.nanoTime();
                tx.read(c -> { store.read(potId, 1L, c).orElseThrow(); return null; });
                readV1Ns.add(System.nanoTime() - t0);
            }

            // Situation A: sitAVersions identical balance & aggregate content
            List<Long> sitAWriteNs = new ArrayList<>(sitAVersions);
            String lsnA0 = lsn(admin);
            for (int k = 1; k <= sitAVersions; k++) {
                long v = sitDVersions + k;
                PotBalance balA = new PotBalance(potId, v, lastBal.values());
                PotBalanceAggregate aggA = new PotBalanceAggregate(potId, v, lastAgg.shareholders(), lastAgg.buckets());
                long t0 = System.nanoTime();
                tx.write(() -> {
                    __WRITE_SIT_A__
                    SpringAttemptTransactions.closePublication(ds);
                    return null;
                });
                sitAWriteNs.add(System.nanoTime() - t0);
            }
            String lsnA1 = lsn(admin);
            long sitAWal = walDiff(admin, lsnA0, lsnA1);
            String storageAfterA = storageJson(admin);

            System.out.println("{"
                + "\"sitDWriteMs\":" + statsJson(sitDWriteNs) + ","
                + "\"sitDWalBytes\":" + sitDWal + ","
                + "\"sitDWalPerVerBytes\":" + Math.round((double) sitDWal / sitDVersions) + ","
                + "\"storageAfterSitD\":" + storageAfterD + ","
                + "\"readCurrentMs\":" + statsJson(readCurNs) + ","
                + "\"readMidMs\":" + statsJson(readMidNs) + ","
                + "\"readV1Ms\":" + statsJson(readV1Ns) + ","
                + "\"sitAWriteMs\":" + statsJson(sitAWriteNs) + ","
                + "\"sitAWalBytes\":" + sitAWal + ","
                + "\"sitAWalPerVerBytes\":" + Math.round((double) sitAWal / sitAVersions) + ","
                + "\"storageAfterSitA\":" + storageAfterA
                + "}");
        }
    }
}
"""

JAVA_GRANULARITY_DRIVER = r"""
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketKey;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.BucketValue;
import com.kartaguez.pocoma.domain.projection.PotBalanceAggregate.ShareholderEntry;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.balance.PotBalanceAggregateBinaryCodec;

import java.math.BigInteger;
import java.util.*;

public final class GranularityProbe {
    static String measure(int shCount, int bucketCount) {
        Random rng = new Random(42L + bucketCount);
        UUID potId = new UUID(0x11111111L, 0x22222222L);
        List<UUID> sids = new ArrayList<>(shCount);
        for (int i = 0; i < shCount; i++) {
            sids.add(new UUID(rng.nextLong(), rng.nextLong()));
        }
        sids.sort(SubobjectKey::compareUuid);

        List<ShareholderEntry> entries = new ArrayList<>(shCount);
        long[] fixedNets = new long[shCount];
        Map<BucketKey, BucketValue> buckets = new TreeMap<>();
        for (int b = 0; b < bucketCount; b++) {
            int groupSize = (b == 0) ? shCount : Math.min(shCount, 2 + (b % 30));
            List<Integer> defIdx = new ArrayList<>(groupSize);
            for (int j = 0; j < groupSize; j++) {
                defIdx.add((b * 3 + j) % shCount);
            }
            Collections.sort(defIdx);
            List<Integer> uniqueIdx = new ArrayList<>(new LinkedHashSet<>(defIdx));
            int payer = b % shCount;
            long amt = 1000L + b;
            Map<Integer, Fraction> sq = new LinkedHashMap<>();
            if (b > 0 && b % 4 == 0 && uniqueIdx.size() < shCount) {
                int specIdx = 0;
                while (uniqueIdx.contains(specIdx)) specIdx++;
                sq.put(specIdx, new Fraction(BigInteger.valueOf(amt * 2), BigInteger.ONE));
            }
            Fraction swTotal = sq.isEmpty() ? Fraction.ZERO : new Fraction(BigInteger.valueOf(2), BigInteger.ONE);
            BucketKey key = new BucketKey(uniqueIdx, swTotal);
            if (buckets.containsKey(key)) continue;

            fixedNets[payer] += amt;
            buckets.put(key, new BucketValue(new Fraction(BigInteger.valueOf(amt), BigInteger.ONE), sq));
        }
        for (int i = 0; i < shCount; i++) {
            entries.add(new ShareholderEntry(
                sids.get(i), new Fraction(BigInteger.valueOf(1 + (i % 3)), BigInteger.ONE), false,
                new Fraction(BigInteger.valueOf(fixedNets[i]), BigInteger.ONE)));
        }

        PotBalanceAggregate aggFull = new PotBalanceAggregate(potId, 1L, entries, buckets);
        var encoded = PotBalanceAggregateBinaryCodec.encode(aggFull);
        byte[] fullPayload = encoded.content().payload();
        int shareholderSetBytes = encoded.shareholderSet().payload().length;

        int pos = 4; // shareholdersCount int32
        for (int i = 0; i < shCount; i++) {
            pos += 1; // deleted flag (UUIDs are factored into read_pot_shareholder_set)
            for (int f = 0; f < 4; f++) { // num, den for defaultWeight and fixedNet
                int len = 0, shift = 0;
                while (true) {
                    int b = fullPayload[pos++] & 0xFF;
                    len |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                pos += len;
            }
        }
        int shareholdersBytes = pos;
        int bucketsBytes = fullPayload.length - shareholdersBytes;
        double shPct = Math.round((shareholdersBytes * 10000.0) / fullPayload.length) / 100.0;
        double bkPct = Math.round((bucketsBytes * 10000.0) / fullPayload.length) / 100.0;
        return String.format(Locale.ROOT,
            "{\"shareholders\":%d,\"buckets\":%d,\"shareholderSetBytes\":%d,\"totalBytes\":%d,\"shareholdersBytes\":%d,\"bucketsBytes\":%d,\"shareholdersPct\":%.2f,\"bucketsPct\":%.2f}",
            shCount, buckets.size(), shareholderSetBytes, fullPayload.length, shareholdersBytes, bucketsBytes, shPct, bkPct);
    }

    public static void main(String[] args) {
        System.out.println("["
            + measure(100, 1) + ","
            + measure(100, 61) + ","
            + measure(100, 240)
            + "]");
    }
}
"""


def build_classpath(app_dir):
    app = Path(app_dir).resolve()
    cp_file = Path(tempfile.mkdtemp(prefix="pocoma-cp-")) / "cp.txt"
    subprocess.run(
        [
            "./mvnw", "-q", "-pl", "infra-postgres-read,infra-tx-spring",
            "dependency:build-classpath", f"-Dmdep.outputFile={cp_file}",
        ],
        cwd=app, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
    )
    jars = [
        str(app / "domain-pot/target/classes"),
        str(app / "domain-identity/target/classes"),
        str(app / "domain-projection/target/classes"),
        str(app / "port-projection/target/classes"),
        str(app / "port-transaction/target/classes"),
        str(app / "infra-tx-spring/target/classes"),
        str(app / "infra-postgres-core/target/classes"),
        str(app / "infra-postgres-read/target/classes"),
    ]
    jars.extend(cp_file.read_text().strip().split(":"))
    shutil.rmtree(cp_file.parent, ignore_errors=True)
    return ":".join(j for j in jars if j)


def run_side(label, app_dir, pg_image, sh_count, sit_d, sit_a, is_v4):
    work = Path(tempfile.mkdtemp(prefix=f"pocoma-b24-xl-{label}-"))
    pg = f"pocoma-b24-xl-{label}-{uuid.uuid4().hex[:6]}"
    try:
        subprocess.run(
            [
                "docker", "run", "-d", "--name", pg,
                "-e", "POSTGRES_USER=pocoma",
                "-e", "POSTGRES_PASSWORD=pocoma-local-only",
                "-e", "POSTGRES_DB=pocoma",
                "-p", "127.0.0.1::5432",
                "--tmpfs", "/var/lib/postgresql/data:size=1g",
                "--memory=2g", "--cpus=2",
                pg_image,
            ],
            check=True, capture_output=True, text=True,
        )
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            r = subprocess.run(
                ["docker", "exec", pg, "psql", "-X", "-U", "pocoma", "-d", "pocoma", "-tAc", "SELECT 1;"],
                capture_output=True, text=True,
            )
            if r.returncode == 0 and r.stdout.strip() == "1":
                break
            time.sleep(0.3)
        port_out = subprocess.check_output(["docker", "port", pg, "5432/tcp"], text=True).strip()
        host_port = port_out.splitlines()[0].split(":")[-1]
        jdbc_url = f"jdbc:postgresql://127.0.0.1:{host_port}/pocoma_read?currentSchema=v3_read"

        def psql(db, sql):
            return subprocess.check_output(
                ["docker", "exec", "-i", pg, "psql", "-X", "-U", "pocoma", "-d", db, "-v", "ON_ERROR_STOP=1", "-tAc", sql],
                text=True,
            ).strip()

        psql("pocoma", "CREATE DATABASE pocoma_read;")
        psql("pocoma_read", "CREATE EXTENSION IF NOT EXISTS pgcrypto;")
        psql("pocoma_read", "CREATE ROLE pocoma_read LOGIN PASSWORD 'pocoma-local-only';")
        psql("pocoma_read", "CREATE SCHEMA v3_read AUTHORIZATION pocoma;")
        psql("pocoma_read", "GRANT USAGE ON SCHEMA v3_read TO pocoma_read;")

        migs = [
            "infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql",
            "infra-postgres-read/src/main/resources/db/read/migration/V2__pot_balance_aggregate.sql",
            "infra-postgres-read/src/main/resources/db/read/migration/V3__pot_version_radix16.sql",
        ]
        if is_v4:
            migs.append("infra-postgres-read/src/main/resources/db/read/migration/V4__pot_balance_shared_storage.sql")
        for mig in migs:
            sql = (Path(app_dir) / mig).read_text(encoding="utf-8")
            sql = (
                sql.replace("${expectedDatabase}", "pocoma_read")
                .replace("${expectedSchema}", "v3_read")
                .replace("${expectedAdmin}", "pocoma")
                .replace("${expectedRuntime}", "pocoma_read")
            )
            subprocess.run(
                ["docker", "exec", "-i", "-e", "PGOPTIONS=-c search_path=v3_read,public", pg,
                 "psql", "-X", "-U", "pocoma", "-d", "pocoma_read", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                input=sql, check=True, capture_output=True, text=True,
            )

        cp = build_classpath(app_dir)
        driver_java = work / "XLargeBalanceDriver.java"
        driver_src = (
            JAVA_DRIVER
            .replace(
                "__WRITE_SIT_D__",
                "var res = store.insertOrVerifyVersionWithResult(bal, Optional.of(agg)); "
                "store.requireHashesStored(potId, agg.version(), Optional.of(res.balanceHash()), res.aggregateHash());"
                if is_v4
                else "store.insertOrVerify(bal); store.insertOrVerifyAggregate(agg);",
            )
            .replace(
                "__WRITE_SIT_A__",
                "var res = store.insertOrVerifyVersionWithResult(balA, Optional.of(aggA)); "
                "store.requireHashesStored(potId, v, Optional.of(res.balanceHash()), res.aggregateHash());"
                if is_v4
                else "store.insertOrVerify(balA); store.insertOrVerifyAggregate(aggA);",
            )
        )
        driver_java.write_text(driver_src)
        subprocess.run(["javac", "-cp", cp, str(driver_java)], check=True)
        out = subprocess.check_output(
            ["java", "-Xms256m", "-Xmx768m", "-cp", f"{work}:{cp}", "XLargeBalanceDriver",
             jdbc_url, str(sh_count), str(sit_d), str(sit_a), "true" if is_v4 else "false"],
            text=True,
        ).strip()
        json_line = next((ln for ln in reversed(out.splitlines()) if ln.startswith('{"sitDWriteMs":')), None)
        if not json_line:
            raise RuntimeError(f"Missing JSON output from XLargeBalanceDriver: {out[-500:]}")
        data = json.loads(json_line)

        if is_v4:
            f4_res = subprocess.check_output(
                ["docker", "exec", "-i", pg, "psql", "-X", "-U", "pocoma", "-d", "pocoma_read",
                 "-v", "ON_ERROR_STOP=1", "-v", "read_schema=v3_read", "-t", "-A", "-f", "-"],
                input=F4_BALANCE_SQL.read_text(encoding="utf-8"),
                text=True,
            )
            s, e = f4_res.find("{"), f4_res.rfind("}")
            data["f4Diagnostic"] = json.loads(f4_res[s:e + 1])
        return data
    finally:
        subprocess.run(["docker", "rm", "-f", pg], capture_output=True)
        shutil.rmtree(work, ignore_errors=True)


def run_granularity_probe(app_dir):
    work = Path(tempfile.mkdtemp(prefix="pocoma-b24-gran-"))
    try:
        cp = build_classpath(app_dir)
        java_file = work / "GranularityProbe.java"
        java_file.write_text(JAVA_GRANULARITY_DRIVER)
        subprocess.run(["javac", "-cp", cp, str(java_file)], check=True)
        out = subprocess.check_output(
            ["java", "-cp", f"{work}:{cp}", "GranularityProbe"], text=True
        ).strip()
        return json.loads(out)
    finally:
        shutil.rmtree(work, ignore_errors=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-app-dir", required=True)
    parser.add_argument("--candidate-app-dir", default=str(ROOT / "app"))
    parser.add_argument("--out", required=True)
    parser.add_argument("--shareholders", type=int, default=500)
    parser.add_argument("--sit-d-versions", type=int, default=100)
    parser.add_argument("--sit-a-versions", type=int, default=50)
    parser.add_argument("--postgres-image", default=DEFAULT_PG_IMAGE)
    args = parser.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    print(f"[xlarge-ab] Running Baseline A ({args.shareholders} sh, {args.sit_d_versions} SitD + {args.sit_a_versions} SitA)...")
    res_a = run_side("baseline-a", args.baseline_app_dir, args.postgres_image, args.shareholders, args.sit_d_versions, args.sit_a_versions, is_v4=False)
    print(f"[xlarge-ab] Running Candidate V4 ({args.shareholders} sh, {args.sit_d_versions} SitD + {args.sit_a_versions} SitA)...")
    res_b = run_side("candidate-v4", args.candidate_app_dir, args.postgres_image, args.shareholders, args.sit_d_versions, args.sit_a_versions, is_v4=True)
    print("[xlarge-ab] Running Aggregate Granularity Probe (G=1, 61, 240)...")
    granularity = run_granularity_probe(args.candidate_app_dir)

    manifest = {
        "shareholders": args.shareholders,
        "sitDVersions": args.sit_d_versions,
        "sitAVersions": args.sit_a_versions,
        "baselineA": res_a,
        "candidateV4": res_b,
        "granularityAnalysis": granularity,
    }
    (out_dir / "xlarge_ab_manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
