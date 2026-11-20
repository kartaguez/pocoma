#!/usr/bin/env python3
"""Pocoma V3 — L7-C2-B2.4 Consolidated A/B Qualification, Non-Financial A/B & Interleaved READ Rebuild Suite.

Compares:
  - Baseline A (e25cd84b, V3 Radix-16 + JSONB PotBalance)
  - Candidate B (feat/v3-l7, V4 Radix-16 + binary content-addressed PotBalance with shared read_pot_shareholder_set)

Modes:
  - ab               : Full A/B comparison (rebuild #1 + optional deterministic rebuild #2, F4, privileges, warm HTTP, 20 mixed mutations)
  - non-financial-ab : 2-way A/B on 50 Situation A + 10 Situation C commands
  - rebuild-ab       : ARB-6 interleaved A -> B -> A -> B READ reconstructions with common window (workers operational -> READY)
"""

import argparse
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import time
import uuid

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

_base_spec = importlib.util.spec_from_file_location("run_l7_4a_baseline", SCRIPT_DIR / "run-l7-4a-baseline.py")
base = importlib.util.module_from_spec(_base_spec)
_base_spec.loader.exec_module(base)

_b1_spec = importlib.util.spec_from_file_location("run_l7_c2_b1_qualification", SCRIPT_DIR / "run-l7-c2-b1-qualification.py")
b1 = importlib.util.module_from_spec(_b1_spec)
_b1_spec.loader.exec_module(b1)

import smoke_test_5_runtimes as oracle  # noqa: E402
import spec09_bench as bench  # noqa: E402

ROOT = bench.ROOT
F4_VERSION_SQL = ROOT / "scripts/v3/diagnose-pot-version-unreachable.sql"
F4_BALANCE_SQL = ROOT / "scripts/v3/diagnose-pot-balance-unreachable.sql"

POT_BALANCE_TABLES = (
    "read_pot_shareholder_set",
    "read_pot_balance",
    "read_pot_balance_content",
    "read_pot_balance_aggregate",
    "read_pot_balance_aggregate_content",
)

STORAGE_TABLES = POT_BALANCE_TABLES + (
    "read_pot_version",
    "read_pot_version_node",
    "read_pot_user_set",
    "read_subobject_version",
    "read_current_pot",
)


def measure_table_storage(pg):
    out = {}
    for table in STORAGE_TABLES:
        exists = b1.psql_text(pg, "pocoma_read", f"SELECT to_regclass('v3_read.{table}')::text;")
        if not exists:
            out[table] = None
            continue
        raw = b1.psql_text(
            pg,
            "pocoma_read",
            f"""
            SELECT jsonb_build_object(
                'rows', (SELECT count(*) FROM v3_read.{table}),
                'heapBytes', pg_relation_size('v3_read.{table}'::regclass),
                'indexBytes', pg_indexes_size('v3_read.{table}'::regclass),
                'toastBytes', pg_total_relation_size('v3_read.{table}'::regclass)
                    - pg_relation_size('v3_read.{table}'::regclass)
                    - pg_indexes_size('v3_read.{table}'::regclass),
                'totalBytes', pg_total_relation_size('v3_read.{table}'::regclass)
            )::text;
            """,
        )
        out[table] = json.loads(raw)
    return out


def pot_balance_total_bytes(storage_map):
    return sum(
        (storage_map[t]["totalBytes"] if storage_map.get(t) else 0)
        for t in POT_BALANCE_TABLES
    )


def run_sql_json_diagnostic(pg, sql_path):
    sql_text = sql_path.read_text(encoding="utf-8")
    cmd = [
        "docker", "exec", "-i", pg,
        "psql", "-X", "-U", "pocoma", "-d", "pocoma_read",
        "-v", "ON_ERROR_STOP=1", "-v", "read_schema=v3_read",
        "-t", "-A", "-f", "-",
    ]
    res = subprocess.run(cmd, input=sql_text, capture_output=True, text=True, check=True, timeout=120)
    text = res.stdout.strip()
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end < 0:
        raise RuntimeError(f"Unexpected diagnostic output from {sql_path.name}: {text!r}")
    return json.loads(text[start:end + 1])


def capture_v4_deterministic_digest(pg):
    """Capture SHA-256 digests of PotVersion Radix-16 and PotBalance V4 logical, content & shareholder-set tables."""
    pv_digest = b1.capture_version_tree_digest(pg)

    def query_digest(sql):
        raw = b1.psql_text(pg, "pocoma_read", sql)
        lines = [line for line in raw.splitlines() if line]
        return {
            "count": len(lines),
            "sha256": hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest(),
        }

    sh_sets = query_digest(
        "SELECT encode(set_hash, 'hex') || '|' || schema_version || '|' || shareholders_count || '|' "
        "|| encode(digest(payload, 'sha256'), 'hex') "
        "FROM v3_read.read_pot_shareholder_set ORDER BY set_hash;"
    )
    bal_ptrs = query_digest(
        "SELECT pot_id::text || '|' || pot_version || '|' || encode(content_hash, 'hex') "
        "FROM v3_read.read_pot_balance ORDER BY pot_id, pot_version;"
    )
    bal_contents = query_digest(
        "SELECT encode(content_hash, 'hex') || '|' || schema_version || '|' || shareholders_count || '|' "
        "|| encode(shareholder_set_hash, 'hex') || '|' || encode(digest(payload, 'sha256'), 'hex') "
        "FROM v3_read.read_pot_balance_content ORDER BY content_hash;"
    )
    agg_ptrs = query_digest(
        "SELECT pot_id::text || '|' || pot_version || '|' || encode(content_hash, 'hex') "
        "FROM v3_read.read_pot_balance_aggregate ORDER BY pot_id, pot_version;"
    )
    agg_contents = query_digest(
        "SELECT encode(content_hash, 'hex') || '|' || schema_version || '|' || shareholders_count || '|' "
        "|| bucket_count || '|' || encode(shareholder_set_hash, 'hex') || '|' "
        "|| encode(digest(payload, 'sha256'), 'hex') "
        "FROM v3_read.read_pot_balance_aggregate_content ORDER BY content_hash;"
    )
    return {
        "potVersion": pv_digest,
        "shareholderSets": sh_sets,
        "balancePointers": bal_ptrs,
        "balanceContents": bal_contents,
        "aggregatePointers": agg_ptrs,
        "aggregateContents": agg_contents,
    }


def verify_http_read_privileges(pg):
    """Verify pocoma_read_http can SELECT shareholder_set, balance & balance_content, but NOT aggregate tables."""
    def can_select(table):
        res = subprocess.run(
            [
                "docker", "exec", "-i", "-e", "PGPASSWORD=pocoma-read-http-local-only",
                pg, "psql", "-X", "-U", "pocoma_read_http", "-d", "pocoma_read",
                "-tAc", f"SELECT count(*) FROM v3_read.{table};",
            ],
            capture_output=True, text=True, timeout=30,
        )
        return res.returncode == 0

    sh_ok = can_select("read_pot_shareholder_set")
    bal_ok = can_select("read_pot_balance")
    bal_c_ok = can_select("read_pot_balance_content")
    agg_ok = can_select("read_pot_balance_aggregate")
    agg_c_ok = can_select("read_pot_balance_aggregate_content")
    if not (sh_ok and bal_ok and bal_c_ok and not agg_ok and not agg_c_ok):
        raise AssertionError(
            f"HTTP READ privilege check failed: sh_set={sh_ok}, bal={bal_ok}, bal_content={bal_c_ok}, "
            f"agg={agg_ok}, agg_content={agg_c_ok}"
        )
    return {
        "shareholderSet": sh_ok,
        "balance": bal_ok,
        "balanceContent": bal_c_ok,
        "aggregateDenied": not agg_ok,
        "aggregateContentDenied": not agg_c_ok,
    }


def await_readiness_oracle_ready(pg, expected_write_counters, is_v4, timeout_s=900):
    """Wait until all 28 checkpoints >= target, 0 slots, and ReadinessOracle relational integrity = 0 errors."""
    deadline = time.monotonic() + timeout_s
    v4_checks = ""
    if is_v4:
        v4_checks = (
            ",'danglingBalContent', (SELECT count(*) FROM v3_read.read_pot_balance pb LEFT JOIN v3_read.read_pot_balance_content bc ON bc.content_hash = pb.content_hash WHERE bc.content_hash IS NULL)"
            ",'danglingAggContent', (SELECT count(*) FROM v3_read.read_pot_balance_aggregate pba LEFT JOIN v3_read.read_pot_balance_aggregate_content bac ON bac.content_hash = pba.content_hash WHERE bac.content_hash IS NULL)"
            ",'danglingBalSet', (SELECT count(*) FROM v3_read.read_pot_balance_content bc LEFT JOIN v3_read.read_pot_shareholder_set ss ON ss.set_hash = bc.shareholder_set_hash WHERE ss.set_hash IS NULL OR ss.shareholders_count <> bc.shareholders_count)"
            ",'danglingAggSet', (SELECT count(*) FROM v3_read.read_pot_balance_aggregate_content bac LEFT JOIN v3_read.read_pot_shareholder_set ss ON ss.set_hash = bac.shareholder_set_hash WHERE ss.set_hash IS NULL OR ss.shareholders_count <> bac.shareholders_count)"
        )
    while time.monotonic() < deadline:
        cp_rows = bench.sql(
            pg,
            "pocoma_read",
            "SELECT coalesce(jsonb_agg(jsonb_build_object('family', crc.family, 'pos', dc.position)), '[]'::jsonb) "
            "FROM v3_read.discovery_checkpoint dc "
            "JOIN v3_read.consumption_routing_contract crc ON dc.consumption_id = crc.consumption_id "
            "WHERE crc.partition_count = 4",
        )
        slots = bench.sql(
            pg,
            "pocoma_read",
            "SELECT jsonb_build_object('total', count(*), 'quarantined', count(*) FILTER (WHERE upper(state) = 'QUARANTINED')) "
            "FROM v3_read.consumption_slot",
        )
        integrity = bench.sql(
            pg,
            "pocoma_read",
            "SELECT jsonb_build_object("
            "'danglingCurrent', (SELECT count(*) FROM v3_read.read_current_pot cp LEFT JOIN v3_read.read_pot_version pv USING (pot_id, pot_version) WHERE pv.pot_id IS NULL),"
            "'balanceMismatch', (SELECT count(*) FROM v3_read.read_pot_version pv LEFT JOIN v3_read.read_pot_balance pb USING (pot_id, pot_version) WHERE (NOT pv.deleted AND pb.pot_id IS NULL) OR (pv.deleted AND pb.pot_id IS NOT NULL)),"
            "'radixMismatch', (SELECT count(*) FROM v3_read.read_pot_version pv LEFT JOIN v3_read.read_pot_version_node sh ON sh.node_hash = pv.shareholders_root LEFT JOIN v3_read.read_pot_version_node ex ON ex.node_hash = pv.expenses_root WHERE (pv.shareholders_root IS NOT NULL AND (sh.node_hash IS NULL OR sh.tree_kind <> 'S' OR sh.entry_count <> pv.shareholders_count)) OR (pv.expenses_root IS NOT NULL AND (ex.node_hash IS NULL OR ex.tree_kind <> 'E' OR ex.entry_count <> pv.expenses_count))),"
            "'missingVis', (SELECT count(*) FROM v3_read.read_current_pot cp LEFT JOIN v3_read.read_pot_visibility_state vs ON cp.pot_id = vs.pot_id AND cp.pot_version <= vs.materialized_version WHERE vs.pot_id IS NULL)"
            + v4_checks
            + ")",
        )
        cp_ok = len(cp_rows) == 28 and all(
            row["pos"] >= expected_write_counters.get(row["family"].removesuffix("_EVENT"), 0)
            for row in cp_rows
        )
        slots_ok = slots["total"] == 0 and slots["quarantined"] == 0
        integrity_ok = all(v == 0 for v in integrity.values())
        if cp_ok and slots_ok and integrity_ok:
            return time.monotonic()
        time.sleep(0.25)
    raise TimeoutError(f"READ rebuild from WRITE did not converge to ReadinessOracle=READY within {timeout_s}s")


def parse_potbalance_metrics(prom_text):
    """Extract PotBalance Micrometer counters and timers."""
    import re
    out = {
        "contentWrite": {},
        "materializePath": {},
        "encodeDuration": {},
        "persistDuration": {},
        "lookupDuration": {},
    }
    for line in prom_text.splitlines():
        if line.startswith("#"):
            continue
        m_cw = re.match(
            r'^pocoma_v3_potbalance_content_write_total\{([^}]+)\}\s+([0-9.eE+-]+)$', line
        )
        if m_cw:
            out["contentWrite"][m_cw.group(1)] = int(float(m_cw.group(2)))
        m_mp = re.match(
            r'^pocoma_v3_potbalance_materialize_path_total\{([^}]+)\}\s+([0-9.eE+-]+)$', line
        )
        if m_mp:
            out["materializePath"][m_mp.group(1)] = int(float(m_mp.group(2)))
        m_enc = re.match(
            r'^pocoma_v3_potbalance_encode_duration_seconds_(count|sum|max)\{([^}]+)\}\s+([0-9.eE+-]+)$', line
        )
        if m_enc:
            stat, labels, val = m_enc.group(1), m_enc.group(2), float(m_enc.group(3))
            out["encodeDuration"].setdefault(labels, {})[stat] = val
        m_per = re.match(
            r'^pocoma_v3_potbalance_persist_duration_seconds_(count|sum|max)\{([^}]+)\}\s+([0-9.eE+-]+)$', line
        )
        if m_per:
            stat, labels, val = m_per.group(1), m_per.group(2), float(m_per.group(3))
            out["persistDuration"].setdefault(labels, {})[stat] = val
        m_lkp = re.match(
            r'^pocoma_v3_potbalance_aggregate_lookup_duration_seconds_(count|sum|max)\{([^}]+)\}\s+([0-9.eE+-]+)$', line
        )
        if m_lkp:
            stat, labels, val = m_lkp.group(1), m_lkp.group(2), float(m_lkp.group(3))
            out["lookupDuration"].setdefault(labels, {})[stat] = val
    return out


def run_non_financial_workload(pg, pot_state, token, binding, worker_metrics_url):
    """Run 50 Situation A + 10 Situation C commands and measure WAL, storage, Micrometer, E2E."""
    pot_id = pot_state["potId"]
    shs = pot_state["shareholders"]
    exp_ids = pot_state.get("expenses", [])
    cur_ver = pot_state["version"]
    write_base = "http://127.0.0.1:28000/api/v3/commands"
    read_base = "http://127.0.0.1:28002/api/v3"

    lsn_before = b1.wal_lsn(pg)
    storage_before = measure_table_storage(pg)
    prom_before = bench.fetch_metrics(worker_metrics_url)

    def submit_and_await(operation, op_payload, expected_ver):
        cmd_body = {
            "operation": operation,
            "potId": pot_id,
            "observedVersion": expected_ver - 1,
            "bindingToken": binding,
            "payload": op_payload,
        }
        t0 = time.monotonic_ns()
        st, adm = oracle.request("POST", write_base, token, cmd_body, key=str(uuid.uuid4()))
        if st != 202:
            raise RuntimeError(f"{operation} admission failed: HTTP {st} {adm}")
        req_id = adm["requestId"]
        deadline = time.monotonic() + 30
        res_ns = pot_ns = bal_ns = None
        while time.monotonic() < deadline:
            if res_ns is None:
                c, body = oracle.request("GET", f"{read_base}/commands/{req_id}/result", token)
                if c == 200:
                    if body.get("outcome") != "COMPLETED" or body.get("resultingPotVersion") != expected_ver:
                        raise RuntimeError(f"{operation} rejected: {body}")
                    res_ns = time.monotonic_ns()
            if pot_ns is None:
                c, _ = oracle.request("GET", f"{read_base}/pots/{pot_id}?minVersion={expected_ver}", token, binding=binding)
                if c == 200:
                    pot_ns = time.monotonic_ns()
            if bal_ns is None:
                c, _ = oracle.request("GET", f"{read_base}/pots/{pot_id}/versions/{expected_ver}/balance", token, binding=binding)
                if c == 200:
                    bal_ns = time.monotonic_ns()
            if res_ns and pot_ns and bal_ns:
                return (bal_ns - t0) / 1e6
            time.sleep(0.01)
        raise TimeoutError(f"Timeout awaiting {operation} v{expected_ver}")

    ops = []
    # 50 Situation A commands: 20 UpdatePotDetails, 15 UpdateShareholderDetails, 10 UpdateExpenseDetails, 5 amount=0
    for i in range(20):
        ops.append(("UpdatePotDetails", {"label": f"NonFin Pot {i}"}))
    for i in range(15):
        ops.append(("UpdateShareholderDetails", {
            "targets": [{"id": shs[i % len(shs)], "name": f"NonFin-SH-{i}", "attachment": "KEEP"}],
        }))
    for i in range(10):
        eid = exp_ids[i % len(exp_ids)]
        ops.append(("UpdateExpenseDetails", {
            "id": eid, "label": f"NonFin-Exp-{i}", "date": "2026-11-20",
        }))
    for i in range(5):
        eid = str(uuid.UUID(int=0xC2B24000000000000000000000000100 + i))
        ops.append(("CreateExpense", {
            "id": eid, "label": f"ZeroExp-{i}", "date": "2026-11-20",
            "amount": {"numerator": "0", "denominator": "1"},
            "payerId": shs[0],
            "participants": [{"id": shs[0], "weight": None}],
        }))

    # 10 Situation C commands: 5 self-paid A->{A} (balance unchanged, aggregate changed), 5 homothetic weights
    for i in range(5):
        eid = str(uuid.UUID(int=0xC2B24000000000000000000000000200 + i))
        s = shs[i % len(shs)]
        ops.append(("CreateExpense", {
            "id": eid, "label": f"SelfPaid-{i}", "date": "2026-11-20",
            "amount": {"numerator": "10", "denominator": "1"},
            "payerId": s,
            "participants": [{"id": s, "weight": None}],
        }))
    for i in range(5):
        factor = i + 2
        ops.append(("UpdateShareholderWeights", {
            "targets": [{"id": sid, "weight": {"numerator": str(factor), "denominator": "1"}} for sid in shs],
        }))

    e2e_ms = []
    for op_name, op_payload in ops:
        cur_ver += 1
        e2e_ms.append(submit_and_await(op_name, op_payload, cur_ver))

    lsn_after = b1.wal_lsn(pg)
    storage_after = measure_table_storage(pg)
    prom_after = bench.fetch_metrics(worker_metrics_url)

    pre_p = b1.mb.parse_prom_all(prom_before)
    post_p = b1.mb.parse_prom_all(prom_after)

    bal_before_bytes = pot_balance_total_bytes(storage_before)
    bal_after_bytes = pot_balance_total_bytes(storage_after)
    pb_before = parse_potbalance_metrics(prom_before)
    pb_after = parse_potbalance_metrics(prom_after)

    def _prom_val(prom_txt, prefix_pattern):
        import re
        for ln in prom_txt.splitlines():
            m = re.match(prefix_pattern, ln)
            if m:
                return round(float(m.group(1)) * 1000.0, 3)
        return None

    return {
        "commandCount": len(ops),
        "walTotalBytes": b1.wal_diff_bytes(pg, lsn_before, lsn_after),
        "walPerVersionBytes": round(b1.wal_diff_bytes(pg, lsn_before, lsn_after) / len(ops), 1),
        "potBalanceStorageDeltaBytes": bal_after_bytes - bal_before_bytes,
        "storageBefore": storage_before,
        "storageAfter": storage_after,
        "e2eLatencyMs": base.stats_summary(e2e_ms),
        "potBalancePaths": b1.mb.prom_paths_delta(pre_p, post_p, "pocoma_v3_potbalance_materialize_path_total"),
        "potBalancePercentiles": {
            "p50Ms": _prom_val(prom_after, r'^pocoma_v3_potbalance_materialize_duration_seconds\{.*quantile="0\.5".*\}\s+([0-9.eE+-]+)$'),
            "p95Ms": _prom_val(prom_after, r'^pocoma_v3_potbalance_materialize_duration_seconds\{.*quantile="0\.95".*\}\s+([0-9.eE+-]+)$'),
            "p99Ms": _prom_val(prom_after, r'^pocoma_v3_potbalance_materialize_duration_seconds\{.*quantile="0\.99".*\}\s+([0-9.eE+-]+)$'),
        },
        "potBalanceMetricsDelta": {
            "contentWriteBefore": pb_before["contentWrite"],
            "contentWriteAfter": pb_after["contentWrite"],
            "materializePathBefore": pb_before["materializePath"],
            "materializePathAfter": pb_after["materializePath"],
            "encodeDurationBefore": pb_before["encodeDuration"],
            "encodeDurationAfter": pb_after["encodeDuration"],
            "persistDurationBefore": pb_before["persistDuration"],
            "persistDurationAfter": pb_after["persistDuration"],
        },
    }


def _setup_container_stack(label, app_dir, cfg, snap_dir, profile_name, out_dir, postgres_image, java_image, is_v4):
    snap_meta, snap_dump = base.verify_snapshot_metadata(Path(snap_dir), profile_name, 2)
    topo = cfg["topology"]
    work = Path(f"/private/tmp/pocoma-c2b24-{label}-{uuid.uuid4().hex[:8]}")
    work.mkdir(mode=0o700, parents=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    project = f"pocoma-c2b24-{label}-{uuid.uuid4().hex[:8]}"
    pg = f"{project}-postgres-1"
    compose_path = work / "compose.json"
    compose_args = ["docker", "compose", "-p", project, "-f", str(compose_path)]

    def dc(*argv, timeout=180):
        with (out_dir / "lifecycle.log").open("a") as log:
            subprocess.run(compose_args + list(argv), check=True, stdout=log, stderr=log, timeout=timeout)

    definition = {
        "services": {
            "postgres": {
                "image": postgres_image,
                "labels": {bench.LABEL: "true"},
                "environment": {
                    "POSTGRES_USER": "pocoma",
                    "POSTGRES_PASSWORD": "pocoma-local-only",
                    "POSTGRES_DB": "pocoma",
                },
                "tmpfs": [f"/var/lib/postgresql/data:size={topo['postgres']['tmpfsSize']}"],
                "mem_limit": topo["postgres"]["memLimit"],
                "cpus": topo["postgres"]["cpus"],
            }
        }
    }
    compose_path.write_text(json.dumps(definition))
    dc("up", "-d", "postgres")
    base.wait_for(lambda: bench.command(["docker", "exec", pg, "pg_isready", "-h", "127.0.0.1", "-U", "pocoma"]) != "")
    ip = next(iter(bench.owned(pg)["NetworkSettings"]["Networks"].values()))["IPAddress"]

    t_res = time.monotonic()
    with open(snap_dump, "rb") as raw_f:
        with gzip.open(raw_f, "rt", encoding="utf-8") as gz:
            sql_text = "\n".join(
                line for line in gz.read().splitlines()
                if line.strip() != "CREATE ROLE pocoma;"
                and not line.strip().startswith("CREATE DATABASE pocoma ")
            )
    res = subprocess.run(
        ["docker", "exec", "-i", pg, "psql", "-v", "ON_ERROR_STOP=1", "-U", "pocoma", "-d", "postgres"],
        input=sql_text.encode("utf-8"), capture_output=True, timeout=300,
    )
    if res.returncode != 0:
        raise RuntimeError(f"Snapshot restore failed: {res.stderr.decode()[:500]}")
    b1.psql_text(pg, "pocoma", "ALTER SYSTEM SET log_line_prefix = '%m [%p] xid=%x state=%e ';", schema="public")
    b1.psql_text(pg, "pocoma", "SELECT pg_reload_conf();", schema="public")
    restore_s = round(time.monotonic() - t_res, 2)

    def reset_read_schema(target_v4):
        b1.psql_text(pg, "pocoma_read", "DROP SCHEMA IF EXISTS v3_read CASCADE;", schema="public")
        b1.psql_text(pg, "pocoma_read", "CREATE SCHEMA v3_read AUTHORIZATION pocoma;", schema="public")
        b1.psql_text(pg, "pocoma_read", "GRANT USAGE ON SCHEMA v3_read TO pocoma_read;", schema="public")
        migs = [
            "app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql",
            "app/infra-postgres-read/src/main/resources/db/read/migration/V2__pot_balance_aggregate.sql",
            "app/infra-postgres-read/src/main/resources/db/read/migration/V3__pot_version_radix16.sql",
        ]
        if target_v4:
            migs.append("app/infra-postgres-read/src/main/resources/db/read/migration/V4__pot_balance_shared_storage.sql")
        src_root = ROOT if target_v4 else Path(app_dir).resolve().parent
        for mig in migs:
            sql = (src_root / mig).read_text(encoding="utf-8")
            sql = (
                sql.replace("${expectedDatabase}", "pocoma_read")
                .replace("${expectedSchema}", "v3_read")
                .replace("${expectedAdmin}", "pocoma")
                .replace("${expectedRuntime}", "pocoma_read")
            )
            subprocess.run(
                ["docker", "exec", "-i", "-e", "PGOPTIONS=-c search_path=v3_read,public", pg,
                 "psql", "-X", "-U", "pocoma", "-d", "pocoma_read", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                input=sql, check=True, capture_output=True, text=True, timeout=60,
            )
        for script_file, variables in (
            ("scripts/v3/bootstrap-l2-routing.sql", {
                "expected_database": "pocoma_read", "expected_schema": "v3_read",
                "expected_admin": "pocoma", "authority": "READ",
            }),
            ("scripts/v3/grant-l2-lifecycle.sql", {
                "expected_database": "pocoma_read", "expected_schema": "v3_read",
                "expected_admin": "pocoma", "runtime_role": "pocoma_read",
                "reactivation_role": "pocoma_react", "identity_authority": "real", "identity_read_role": "pocoma_read",
            }),
            ("scripts/v3/grant-l2-discovery.sql", {
                "expected_database": "pocoma_read", "expected_schema": "v3_read",
                "expected_admin": "pocoma", "runtime_role": "pocoma_read",
                "reactivation_role": "pocoma_react", "identity_authority": "real", "identity_read_role": "pocoma_read",
            }),
            ("scripts/v3/grant-l7-http-read.sql", {
                "read_database": "pocoma_read", "read_schema": "v3_read",
                "query_role": "pocoma_read_http", "status_role": "pocoma_read_status",
            }),
        ):
            vars_args = []
            for k, v in variables.items():
                vars_args.extend(["-v", f"{k}={v}"])
            subprocess.run(
                ["docker", "exec", "-i", "-e", "PGOPTIONS=-c search_path=v3_read,public", pg,
                 "psql", "-X", "-U", "pocoma", "-d", "pocoma_read", "-v", "ON_ERROR_STOP=1", *vars_args, "-f", "-"],
                input=(src_root / script_file).read_text(encoding="utf-8"),
                check=True, capture_output=True, text=True, timeout=60,
            )
        b1.psql_text(
            pg, "pocoma_read",
            "INSERT INTO v3_read.discovery_checkpoint (consumption_id, partition_id, position) "
            "SELECT consumption_id, p, 0 FROM v3_read.consumption_routing_contract "
            "CROSS JOIN generate_series(0, 3) p ON CONFLICT (consumption_id, partition_id) DO NOTHING;",
        )

    reset_read_schema(is_v4)

    subject = snap_meta["tokenSubject"]
    (work / "subjects.json").write_text(json.dumps([subject]))
    issuer_log = (out_dir / "issuer.log").open("w")
    issuer = subprocess.Popen(
        [
            "node", str(ROOT / "scripts/performance/spec09-test-issuer.mjs"),
            "--host", "0.0.0.0", "--port", "28090",
            "--issuer", "http://pocoma-l74a.invalid", "--audience", "pocoma-api",
            "--subjects", str(work / "subjects.json"),
            "--token-file", str(work / "tokens.json"), "--ttl-seconds", "3600",
        ],
        stdout=issuer_log, stderr=issuer_log,
    )
    base.wait_for(lambda: (work / "tokens.json").is_file(), 10)

    resolved_app_dir = Path(app_dir).resolve()
    for name, rcfg in topo["runtimes"].items():
        mod = rcfg["module"]
        jar = resolved_app_dir / mod / "target" / f"pocoma-{mod}-0.0.1-SNAPSHOT-exec.jar"
        if not jar.is_file():
            raise ValueError(f"Missing runtime JAR: {jar}")
        content = (base.PROPERTIES_DIR / f"{name}.properties").read_text()
        content = content.replace("172.28.0.2/32", ip + "/32")
        content += "\npocoma.v3.http.issuer=http://pocoma-l74a.invalid\n"
        content += "pocoma.v3.http.jwk-set-uri=http://host.docker.internal:28090/jwks.json\n"
        prop_file = work / f"{name}.properties"
        prop_file.write_text(content)
        mgmt = rcfg["managementPort"]
        ports = [f"127.0.0.1:{mgmt + 10000}:{mgmt}"]
        if "httpPort" in rcfg:
            hp = rcfg["httpPort"]
            ports.append(f"127.0.0.1:{hp + 19920}:{hp}")
        definition["services"][name] = {
            "image": java_image,
            "labels": {bench.LABEL: "true"},
            "entrypoint": ["java", "-jar", "/app/runtime.jar"],
            "environment": {
                "SPRING_PROFILES_ACTIVE": rcfg["profile"],
                "SPRING_CONFIG_ADDITIONAL_LOCATION": "file:/app/runtime.properties",
                "JAVA_TOOL_OPTIONS": topo["javaOptions"],
            },
            "volumes": [f"{jar}:/app/runtime.jar:ro", f"{prop_file}:/app/runtime.properties:ro"],
            "ports": ports,
            "mem_limit": rcfg["memLimit"],
            "cpus": rcfg["cpus"],
        }
    compose_path.write_text(json.dumps(definition))

    metrics_urls = {
        name: f"http://127.0.0.1:{rcfg['managementPort'] + 10000}"
        for name, rcfg in topo["runtimes"].items()
    }
    return work, project, pg, compose_args, dc, issuer, snap_meta, restore_s, reset_read_schema, metrics_urls


def _timed_worker_rebuild(pg, dc, metrics_urls, write_counters, is_v4):
    """ARB-6 common measurement window: start READ workers -> workers operational -> ReadinessOracle=READY."""
    read_workers = ("results-worker", "business-read-worker")
    t_start = time.monotonic()
    dc("up", "-d", "--no-recreate", *read_workers)
    for name in read_workers:
        url = metrics_urls[name]
        base.wait_for(lambda u=url: bool(bench.fetch_metrics(u)), 90)
    t_workers_operational = time.monotonic()
    t_ready = await_readiness_oracle_ready(pg, write_counters, is_v4=is_v4, timeout_s=900)
    return {
        "bootstrapSeconds": round(t_workers_operational - t_start, 2),
        "effectiveRebuildSeconds": round(t_ready - t_workers_operational, 2),
        "totalWallSeconds": round(t_ready - t_start, 2),
        "elapsedSeconds": round(t_ready - t_workers_operational, 2),
    }


def run_side(label, app_dir, cfg, snap_dir, profile_name, out_dir, postgres_image, java_image,
             is_v4=True, verify_second_rebuild=False, non_financial_workload=False):
    work = None
    compose_args = None
    issuer = None
    try:
        work, _, pg, compose_args, dc, issuer, snap_meta, restore_s, reset_read_schema, metrics_urls = \
            _setup_container_stack(label, app_dir, cfg, snap_dir, profile_name, out_dir, postgres_image, java_image, is_v4)

        # Start non-READ runtimes first so the READ rebuild window (workers up -> operational -> READY) is identical for rebuild1 & rebuild2 and A & B
        non_read_runtimes = [n for n in cfg["topology"]["runtimes"].keys() if n not in ("results-worker", "business-read-worker")]
        if non_read_runtimes:
            dc("up", "-d", "--no-recreate", *non_read_runtimes)
            for n in non_read_runtimes:
                url = metrics_urls[n]
                base.wait_for(lambda u=url: bool(bench.fetch_metrics(u)), 90)

        result = {
            "label": label,
            "profile": profile_name,
            "isV4": is_v4,
            "restoreSeconds": restore_s,
            "snapshotSha256": snap_meta["snapshotSha256"],
        }

        print(f"[{label}] Awaiting READ rebuild #1 from WRITE...")
        rb1 = _timed_worker_rebuild(pg, dc, metrics_urls, snap_meta["writeCounters"], is_v4=is_v4)
        result["rebuild1"] = rb1
        if is_v4:
            digest1 = capture_v4_deterministic_digest(pg)
            result["rebuild1"]["digest"] = digest1
            result["rebuild1"]["f4Balance"] = run_sql_json_diagnostic(pg, F4_BALANCE_SQL)
            result["rebuild1"]["f4Version"] = run_sql_json_diagnostic(pg, F4_VERSION_SQL)
            result["httpPrivileges"] = verify_http_read_privileges(pg)
            print(
                f"[{label}] Rebuild #1 complete: effective={rb1['effectiveRebuildSeconds']}s "
                f"(boot={rb1['bootstrapSeconds']}s, total={rb1['totalWallSeconds']}s, "
                f"balSha={digest1['balanceContents']['sha256'][:12]})."
            )

            if verify_second_rebuild:
                print(f"[{label}] Running deterministic READ rebuild #2...")
                dc("stop", "results-worker", "business-read-worker")
                reset_read_schema(True)
                rb2 = _timed_worker_rebuild(pg, dc, metrics_urls, snap_meta["writeCounters"], is_v4=True)
                digest2 = capture_v4_deterministic_digest(pg)
                deterministic = digest1 == digest2
                result["rebuild2"] = rb2
                result["rebuild2"]["digest"] = digest2
                result["rebuild2"]["deterministicBitForBit"] = deterministic
                result["rebuild2"]["f4Balance"] = run_sql_json_diagnostic(pg, F4_BALANCE_SQL)
                result["rebuild2"]["f4Version"] = run_sql_json_diagnostic(pg, F4_VERSION_SQL)
                print(
                    f"[{label}] Rebuild #2 complete: effective={rb2['effectiveRebuildSeconds']}s "
                    f"(boot={rb2['bootstrapSeconds']}s, total={rb2['totalWallSeconds']}s, deterministic={deterministic})."
                )
                if not deterministic:
                    raise AssertionError(f"Non-deterministic READ rebuild! {digest1} != {digest2}")
        else:
            print(
                f"[{label}] Rebuild #1 complete: effective={rb1['effectiveRebuildSeconds']}s "
                f"(boot={rb1['bootstrapSeconds']}s, total={rb1['totalWallSeconds']}s)."
            )

        result["storagePostRestore"] = measure_table_storage(pg)
        result["metricsPostRestore"] = parse_potbalance_metrics(bench.fetch_metrics(metrics_urls["business-read-worker"]))

        token = next(iter(json.loads((work / "tokens.json").read_text()).values()))
        code, binding_resp = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", token)
        if code != 200 or not binding_resp.get("bindingToken"):
            raise RuntimeError(f"Binding unavailable: HTTP {code}")
        binding = binding_resp["bindingToken"]

        pot_meta = snap_meta["pots"][0]
        pot_id = pot_meta["potId"]
        final_ver = pot_meta["version"]

        print(f"[{label}] Measuring warm HTTP READ latencies (30 iterations each)...")
        read_latencies, golden = b1.measure_http_reads(pot_id, final_ver, token, binding, iterations=30)
        result["readLatencies"] = read_latencies

        pot_state = {
            "potId": pot_id,
            "version": final_ver,
            "shareholders": [s["id"] for s in golden["currentFull"]["shareholders"]],
            "expenses": [e["id"] for e in golden["currentFull"].get("expenses", [])],
        }

        if non_financial_workload:
            print(f"[{label}] Running 60-command non-financial & SitC workload...")
            result["nonFinancialWorkload"] = run_non_financial_workload(
                pg, pot_state, token, binding, metrics_urls["business-read-worker"]
            )
            if is_v4:
                result["f4AfterMutations"] = run_sql_json_diagnostic(pg, F4_BALANCE_SQL)
        else:
            print(f"[{label}] Running 20 mixed mutations (WAL + Micrometer + E2E)...")
            prom_pre = bench.fetch_metrics(metrics_urls["business-read-worker"])
            mixed_res = b1.run_mixed_mutations(
                pg, pot_state, token, binding, metrics_urls["business-read-worker"]
            )
            prom_post = bench.fetch_metrics(metrics_urls["business-read-worker"])
            mixed_res["potBalanceMetricsBefore"] = parse_potbalance_metrics(prom_pre)
            mixed_res["potBalanceMetricsAfter"] = parse_potbalance_metrics(prom_post)
            mixed_res["potBalanceStorageAfter"] = measure_table_storage(pg)
            result["mixedMutations"] = mixed_res
            if is_v4:
                result["f4AfterMutations"] = run_sql_json_diagnostic(pg, F4_BALANCE_SQL)

        return result, golden
    finally:
        if issuer is not None:
            issuer.terminate()
            try:
                issuer.wait(timeout=5)
            except subprocess.TimeoutExpired:
                issuer.kill()
        if compose_args is not None:
            subprocess.run(compose_args + ["down", "-v", "--remove-orphans"], capture_output=True)
        if work is not None:
            shutil.rmtree(work, ignore_errors=True)


def run_single_rebuild_bench(label, app_dir, cfg, snap_dir, profile_name, out_dir, postgres_image, java_image, is_v4):
    """Run one clean isolated READ rebuild from WRITE and return bootstrap, effective, and total timings."""
    work = None
    compose_args = None
    issuer = None
    try:
        work, _, pg, compose_args, dc, issuer, snap_meta, restore_s, _, metrics_urls = \
            _setup_container_stack(label, app_dir, cfg, snap_dir, profile_name, out_dir, postgres_image, java_image, is_v4)

        non_read_runtimes = [n for n in cfg["topology"]["runtimes"].keys() if n not in ("results-worker", "business-read-worker")]
        if non_read_runtimes:
            dc("up", "-d", "--no-recreate", *non_read_runtimes)
            for n in non_read_runtimes:
                url = metrics_urls[n]
                base.wait_for(lambda u=url: bool(bench.fetch_metrics(u)), 90)

        rb = _timed_worker_rebuild(pg, dc, metrics_urls, snap_meta["writeCounters"], is_v4=is_v4)
        rb["label"] = label
        rb["isV4"] = is_v4
        rb["restoreSeconds"] = restore_s
        rb["storagePostRebuild"] = measure_table_storage(pg)
        rb["metricsPostRebuild"] = parse_potbalance_metrics(bench.fetch_metrics(metrics_urls["business-read-worker"]))
        if is_v4:
            rb["digest"] = capture_v4_deterministic_digest(pg)
            rb["f4Balance"] = run_sql_json_diagnostic(pg, F4_BALANCE_SQL)
        print(
            f"[{label}] Rebuild complete: effective={rb['effectiveRebuildSeconds']}s, "
            f"boot={rb['bootstrapSeconds']}s, total={rb['totalWallSeconds']}s"
        )
        return rb
    finally:
        if issuer is not None:
            issuer.terminate()
            try:
                issuer.wait(timeout=5)
            except subprocess.TimeoutExpired:
                issuer.kill()
        if compose_args is not None:
            subprocess.run(compose_args + ["down", "-v", "--remove-orphans"], capture_output=True)
        if work is not None:
            shutil.rmtree(work, ignore_errors=True)


def _summarize_rebuild_series(runs, field):
    vals = [r[field] for r in runs]
    return {
        "n": len(vals),
        "values": vals,
        "min": round(min(vals), 3),
        "mean": round(statistics.mean(vals), 3),
        "median": round(statistics.median(vals), 3),
        "max": round(max(vals), 3),
        "stdev": round(statistics.stdev(vals), 3) if len(vals) > 1 else 0.0,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("ab", "non-financial-ab", "rebuild-ab"), required=True)
    parser.add_argument("--profile", choices=("medium", "large"), required=True)
    parser.add_argument("--snapshot-dir", required=True)
    parser.add_argument("--baseline-app-dir", required=True)
    parser.add_argument("--candidate-app-dir", default=str(ROOT / "app"))
    parser.add_argument("--out", required=True)
    parser.add_argument("--verify-second-rebuild", action="store_true")
    parser.add_argument("--pairs", type=int, default=2, help="Number of interleaved A->B pairs for rebuild-ab (default: 2 => A1,B1,A2,B2)")
    parser.add_argument("--postgres-image", default=b1.DEFAULT_POSTGRES_IMAGE)
    parser.add_argument("--java-image", default=b1.DEFAULT_JAVA_IMAGE)
    args = parser.parse_args()

    cfg = base.load_profiles_config()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    if args.mode == "ab":
        res_a, golden_a = run_side(
            "baseline-a", args.baseline_app_dir, cfg, args.snapshot_dir, args.profile,
            out / "baseline-a", args.postgres_image, args.java_image,
            is_v4=False, verify_second_rebuild=False,
        )
        res_b, golden_b = run_side(
            "candidate-v4", args.candidate_app_dir, cfg, args.snapshot_dir, args.profile,
            out / "candidate-v4", args.postgres_image, args.java_image,
            is_v4=True, verify_second_rebuild=args.verify_second_rebuild,
        )
        equiv = b1.compare_golden(golden_a, golden_b)
        summary = {
            "mode": "ab",
            "profile": args.profile,
            "equivalence": equiv,
            "baselineA": res_a,
            "candidateV4": res_b,
        }
        (out / "b2_qualification_manifest.json").write_text(json.dumps(summary, indent=2) + "\n")
        print(json.dumps({"profile": args.profile, "equivalence": equiv}, indent=2))
        return 0 if equiv["exactMatch"] else 1

    if args.mode == "non-financial-ab":
        res_base, golden_base = run_side(
            "nonfin-baseline-v3", args.baseline_app_dir, cfg, args.snapshot_dir, args.profile,
            out / "nonfin-baseline-v3", args.postgres_image, args.java_image,
            is_v4=False, verify_second_rebuild=False, non_financial_workload=True,
        )
        res_v4, golden_v4 = run_side(
            "nonfin-candidate-v4", args.candidate_app_dir, cfg, args.snapshot_dir, args.profile,
            out / "nonfin-candidate-v4", args.postgres_image, args.java_image,
            is_v4=True, verify_second_rebuild=False, non_financial_workload=True,
        )
        equiv = b1.compare_golden(golden_base, golden_v4)
        summary = {
            "mode": "non-financial-ab",
            "profile": args.profile,
            "equivalence": equiv,
            "baselineV3": res_base,
            "candidateV4": res_v4,
        }
        (out / "b2_non_financial_ab_manifest.json").write_text(json.dumps(summary, indent=2) + "\n")
        print(json.dumps({"profile": args.profile, "equivalence": equiv}, indent=2))
        return 0 if equiv["exactMatch"] else 1

    # ARB-6 interleaved A -> B -> A -> B rebuild campaign
    runs_a = []
    runs_b = []
    sequence = []
    for idx in range(1, args.pairs + 1):
        rb_a = run_single_rebuild_bench(
            f"rebuild-a-{idx}", args.baseline_app_dir, cfg, args.snapshot_dir, args.profile,
            out / f"rebuild-a-{idx}", args.postgres_image, args.java_image, is_v4=False,
        )
        runs_a.append(rb_a)
        sequence.append(rb_a["label"])
        rb_b = run_single_rebuild_bench(
            f"rebuild-b-{idx}", args.candidate_app_dir, cfg, args.snapshot_dir, args.profile,
            out / f"rebuild-b-{idx}", args.postgres_image, args.java_image, is_v4=True,
        )
        runs_b.append(rb_b)
        sequence.append(rb_b["label"])

    # Verify deterministic bit-for-bit digests across all B runs
    deterministic_all = all(r["digest"] == runs_b[0]["digest"] for r in runs_b[1:])
    if not deterministic_all:
        raise AssertionError("Non-deterministic READ rebuild across interleaved B runs!")

    eff_a = _summarize_rebuild_series(runs_a, "effectiveRebuildSeconds")
    eff_b = _summarize_rebuild_series(runs_b, "effectiveRebuildSeconds")
    tot_a = _summarize_rebuild_series(runs_a, "totalWallSeconds")
    tot_b = _summarize_rebuild_series(runs_b, "totalWallSeconds")
    boot_a = _summarize_rebuild_series(runs_a, "bootstrapSeconds")
    boot_b = _summarize_rebuild_series(runs_b, "bootstrapSeconds")

    summary = {
        "mode": "rebuild-ab",
        "profile": args.profile,
        "sequence": sequence,
        "measurementWindow": "ReadinessOracle=READY from workers operational (bootstrap separated)",
        "deterministicAllCandidateRuns": deterministic_all,
        "runsBaselineA": runs_a,
        "runsCandidateV4": runs_b,
        "statistics": {
            "bootstrapSeconds": {"baselineA": boot_a, "candidateV4": boot_b},
            "effectiveRebuildSeconds": {
                "baselineA": eff_a,
                "candidateV4": eff_b,
                "meanRatioBOverA": round(eff_b["mean"] / eff_a["mean"], 4) if eff_a["mean"] > 0 else None,
                "medianRatioBOverA": round(eff_b["median"] / eff_a["median"], 4) if eff_a["median"] > 0 else None,
            },
            "totalWallSeconds": {
                "baselineA": tot_a,
                "candidateV4": tot_b,
                "meanRatioBOverA": round(tot_b["mean"] / tot_a["mean"], 4) if tot_a["mean"] > 0 else None,
                "medianRatioBOverA": round(tot_b["median"] / tot_a["median"], 4) if tot_a["median"] > 0 else None,
            },
        },
    }
    (out / "b2_rebuild_ab_manifest.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary["statistics"], indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
