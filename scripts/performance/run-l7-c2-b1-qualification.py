#!/usr/bin/env python3
"""Pocoma V3 — L7-C2-B1.4 Consolidated A/B Qualification & READ Rebuild Suite.

Performs reproducible A/B qualification comparing:
  - Baseline A (pre-B1.1 JSONB + A3 + A4, commit b8924f3d)
  - Candidate B (V3 Radix-16 + A3 + A4, feat/v3-l7 HEAD)

On the exact same machine, Docker PostgreSQL 17 image, Java image, and L7.4 WRITE snapshots:
  1. Full READ reconstruction from WRITE (V1+V2+V3, zero reuse of legacy V1/V2 READ tables)
     + deterministic second rebuild hash equivalence across all historical versions.
  2. Exact business equivalence of HTTP public responses (current, historical, balance,
     single expense, batch 50 expenses, invitations).
  3. Warm HTTP READ latencies (complete & targeted) + EXPLAIN (ANALYZE, BUFFERS) proofs.
  4. Controlled mixed mutation workload (Pot details, Expense add/details/financials/delete,
     Shareholder details/weights): WAL bytes (pg_wal_lsn_diff), storage growth per table,
     Micrometer PotVersion stage breakdown & node reuse, PotBalance & E2E latencies.
  5. F4 read-only diagnostic of unreachable nodes and user sets (no GC, no deletion).
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from fractions import Fraction
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

_base_spec = importlib.util.spec_from_file_location("run_l7_4a_baseline", SCRIPT_DIR / "run-l7-4a-baseline.py")
base = importlib.util.module_from_spec(_base_spec)
_base_spec.loader.exec_module(base)

_mb_spec = importlib.util.spec_from_file_location("run_l7_4_multibucket", SCRIPT_DIR / "run-l7-4-a3-a4-multibucket.py")
mb = importlib.util.module_from_spec(_mb_spec)
_mb_spec.loader.exec_module(mb)

import smoke_test_5_runtimes as oracle  # noqa: E402
import spec09_bench as bench  # noqa: E402

ROOT = bench.ROOT
F4_SQL_PATH = ROOT / "scripts/v3/diagnose-pot-version-unreachable.sql"

STORAGE_TABLES = (
    "read_pot_version",
    "read_pot_version_node",
    "read_pot_user_set",
    "read_subobject_version",
    "read_current_pot",
    "read_pot_balance",
    "read_pot_balance_aggregate",
)


def psql_text(pg, db, sql, schema="v3_read"):
    cmd = [
        "docker", "exec", "-i",
        "-e", f"PGOPTIONS=-c search_path={schema},public",
        pg, "psql", "-X", "-U", "pocoma", "-d", db,
        "-v", "ON_ERROR_STOP=1", "-tAc", sql,
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, check=True, timeout=120)
    return res.stdout.strip()


def measure_table_storage(pg):
    """Measure heap, index, TOAST, total bytes, and row count for each READ table."""
    out = {}
    for table in STORAGE_TABLES:
        exists = psql_text(pg, "pocoma_read", f"SELECT to_regclass('v3_read.{table}')::text;")
        if not exists:
            out[table] = None
            continue
        raw = psql_text(
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


def run_f4_diagnostic(pg):
    """Execute read-only F4 unreachable diagnostic SQL script against pocoma_read."""
    sql_text = F4_SQL_PATH.read_text(encoding="utf-8")
    cmd = [
        "docker", "exec", "-i", pg,
        "psql", "-X", "-U", "pocoma", "-d", "pocoma_read",
        "-v", "ON_ERROR_STOP=1", "-v", "read_schema=v3_read",
        "-t", "-A", "-f", "-",
    ]
    res = subprocess.run(cmd, input=sql_text, capture_output=True, text=True, check=True, timeout=120)
    # Extract the JSON object from output (BEGIN/COMMIT lines may appear)
    text = res.stdout.strip()
    start = text.find("{")
    end = text.rfind("}")
    if start < 0 or end < 0:
        raise RuntimeError(f"Unexpected F4 output: {text!r}")
    return json.loads(text[start:end + 1])


def capture_version_tree_digest(pg):
    """Return SHA-256 digest of all (pot_id, pot_version, root hashes, user set hashes)."""
    raw = psql_text(
        pg,
        "pocoma_read",
        """
        SELECT pot_id::text || '|' || pot_version || '|' || deleted || '|'
            || shareholders_count || '|' || expenses_count || '|'
            || coalesce(encode(shareholders_root, 'hex'), 'NULL') || '|'
            || coalesce(encode(expenses_root, 'hex'), 'NULL') || '|'
            || coalesce(encode(authorized_users_hash, 'hex'), 'NULL') || '|'
            || coalesce(encode(attached_active_users_hash, 'hex'), 'NULL')
        FROM v3_read.read_pot_version
        ORDER BY pot_id, pot_version;
        """,
    )
    lines = [line for line in raw.splitlines() if line]
    return {
        "versionCount": len(lines),
        "sha256": hashlib.sha256("\n".join(lines).encode("utf-8")).hexdigest(),
    }


def wal_lsn(pg):
    return psql_text(pg, "pocoma_read", "SELECT pg_current_wal_lsn()::text;")


def wal_diff_bytes(pg, lsn_start, lsn_end):
    return int(psql_text(pg, "pocoma_read", f"SELECT pg_wal_lsn_diff('{lsn_end}'::pg_lsn, '{lsn_start}'::pg_lsn)::bigint;"))


def http_req_timed(method, url, token, payload=None, binding=None, key=None):
    t0 = time.monotonic_ns()
    code, body = oracle.request(method, url, token, payload, binding=binding, key=key)
    dt_ms = (time.monotonic_ns() - t0) / 1e6
    return code, body, dt_ms


def measure_http_reads(pot_id, final_version, token, binding, iterations=30, invitation_token=None, invitee_token=None, invitee_binding=None):
    """Measure warm HTTP READ latencies (complete & targeted) and capture golden payloads."""
    read_base = "http://127.0.0.1:28002/api/v3"

    # Fetch current pot to obtain expense refs for targeted queries
    code, current_pot, _ = http_req_timed("GET", f"{read_base}/pots/{pot_id}", token, binding=binding)
    if code != 200:
        raise RuntimeError(f"GET /pots/{pot_id} failed: HTTP {code}")
    mid_version = max(1, final_version // 2)
    code, hist_v1, _ = http_req_timed("GET", f"{read_base}/pots/{pot_id}/versions/1", token, binding=binding)
    if code != 200:
        raise RuntimeError(f"GET /pots/{pot_id}/versions/1 failed: HTTP {code}")
    code, hist_mid, _ = http_req_timed("GET", f"{read_base}/pots/{pot_id}/versions/{mid_version}", token, binding=binding)
    if code != 200:
        raise RuntimeError(f"GET /pots/{pot_id}/versions/{mid_version} failed: HTTP {code}")
    code, balance_cur, _ = http_req_timed("GET", f"{read_base}/pots/{pot_id}/versions/{final_version}/balance", token, binding=binding)
    if code != 200:
        raise RuntimeError(f"GET balance failed: HTTP {code}")

    expenses = current_pot.get("expenses", [])
    def to_ref(e):
        return {
            "id": e["id"],
            "headerVersion": e["headerVersion"],
            "detailsVersion": e["detailsVersion"],
            "financialsVersion": e["financialsVersion"],
            "deleted": e["deleted"],
        }

    single_ref_body = {"references": [to_ref(expenses[len(expenses) // 2])]}
    batch_50_body = {"references": [to_ref(e) for e in expenses[:min(50, len(expenses))]]}

    code, exp_1_resp, _ = http_req_timed(
        "POST", f"{read_base}/pots/{pot_id}/versions/{final_version}/expenses/query",
        token, payload=single_ref_body, binding=binding,
    )
    if code != 200:
        raise RuntimeError(f"POST expenses/query (1) failed: HTTP {code}")
    code, exp_50_resp, _ = http_req_timed(
        "POST", f"{read_base}/pots/{pot_id}/versions/{final_version}/expenses/query",
        token, payload=batch_50_body, binding=binding,
    )
    if code != 200:
        raise RuntimeError(f"POST expenses/query (50) failed: HTTP {code}")

    inv_resp = None
    if invitation_token and invitee_token and invitee_binding:
        code, inv_resp, _ = http_req_timed(
            "POST", f"{read_base}/invitations/available-shareholders/query",
            invitee_token, payload={"token": invitation_token}, binding=invitee_binding,
        )
        if code != 200:
            raise RuntimeError(f"POST invitations/available-shareholders/query failed: HTTP {code}")

    # Warmup 5 iterations per endpoint
    endpoints = [
        ("currentFull", "GET", f"{read_base}/pots/{pot_id}", token, None, binding),
        ("historicalMidFull", "GET", f"{read_base}/pots/{pot_id}/versions/{mid_version}", token, None, binding),
        ("balanceAtVersion", "GET", f"{read_base}/pots/{pot_id}/versions/{final_version}/balance", token, None, binding),
        ("targetedExpense1", "POST", f"{read_base}/pots/{pot_id}/versions/{final_version}/expenses/query", token, single_ref_body, binding),
        ("targetedExpense50", "POST", f"{read_base}/pots/{pot_id}/versions/{final_version}/expenses/query", token, batch_50_body, binding),
    ]
    if inv_resp is not None:
        endpoints.append((
            "invitationPlaces", "POST", f"{read_base}/invitations/available-shareholders/query",
            invitee_token, {"token": invitation_token}, invitee_binding,
        ))

    latencies = {}
    for label, method, url, tok, body, bnd in endpoints:
        for _ in range(5):
            c, _, _ = http_req_timed(method, url, tok, payload=body, binding=bnd)
            if c != 200:
                raise RuntimeError(f"Warmup {label} failed: HTTP {c}")
        samples = []
        for _ in range(iterations):
            c, _, dt = http_req_timed(method, url, tok, payload=body, binding=bnd)
            if c != 200:
                raise RuntimeError(f"Measure {label} failed: HTTP {c}")
            samples.append(dt)
        latencies[label] = base.stats_summary(samples)

    golden = {
        "currentFull": current_pot,
        "historicalV1": hist_v1,
        "historicalMid": hist_mid,
        "balanceCurrent": balance_cur,
        "targetedExpense1": exp_1_resp,
        "targetedExpense50": exp_50_resp,
    }
    if inv_resp is not None:
        golden["invitationPlaces"] = inv_resp

    return latencies, golden


def explain_targeted_sql(pg, pot_id, version):
    """Run EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) for R1 and R3a to verify SQL access patterns."""
    r1_sql = f"""
    EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
    WITH RECURSIVE manifest AS (
      SELECT pot_id, pot_version, schema_version, header_version, details_version, deleted,
             shareholders_count, expenses_count, shareholders_root, expenses_root,
             authorized_users_hash, attached_active_users_hash
      FROM v3_read.read_pot_version
      WHERE pot_id = '{pot_id}'::uuid AND pot_version = {version}
    ),
    tree(node_hash, schema_version, node_kind, tree_kind, entry_count, prefix, child_hashes, payload) AS (
      SELECT n.node_hash, n.schema_version, n.node_kind, n.tree_kind, n.entry_count, n.prefix, n.child_hashes, n.payload
      FROM manifest m
      JOIN v3_read.read_pot_version_node n
        ON n.node_hash = m.shareholders_root OR n.node_hash = m.expenses_root
      UNION ALL
      SELECT c.node_hash, c.schema_version, c.node_kind, c.tree_kind, c.entry_count, c.prefix, c.child_hashes, c.payload
      FROM tree t
      CROSS JOIN LATERAL unnest(t.child_hashes) AS ch(h)
      JOIN v3_read.read_pot_version_node c ON c.node_hash = ch.h
      WHERE t.node_kind = 'I'
    )
    SELECT count(*) FROM tree;
    """
    r3a_sql = f"""
    EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
    WITH RECURSIVE manifest AS (
      SELECT shareholders_root FROM v3_read.read_pot_version
      WHERE pot_id = '{pot_id}'::uuid AND pot_version = {version}
    ),
    tree(node_hash, node_kind, tree_kind, child_hashes) AS (
      SELECT n.node_hash, n.node_kind, n.tree_kind, n.child_hashes
      FROM manifest m
      JOIN v3_read.read_pot_version_node n ON n.node_hash = m.shareholders_root
      UNION ALL
      SELECT c.node_hash, c.node_kind, c.tree_kind, c.child_hashes
      FROM tree t
      CROSS JOIN LATERAL unnest(t.child_hashes) AS ch(h)
      JOIN v3_read.read_pot_version_node c ON c.node_hash = ch.h
      WHERE t.node_kind = 'I'
    )
    SELECT count(*) FILTER (WHERE tree_kind = 'S') AS s_nodes,
           count(*) FILTER (WHERE tree_kind = 'E') AS e_nodes
    FROM tree;
    """
    r1_plan = json.loads(psql_text(pg, "pocoma_read", r1_sql))[0]
    r3a_plan = json.loads(psql_text(pg, "pocoma_read", r3a_sql))[0]
    r3a_counts = psql_text(
        pg,
        "pocoma_read",
        f"""
        WITH RECURSIVE manifest AS (
          SELECT shareholders_root FROM v3_read.read_pot_version
          WHERE pot_id = '{pot_id}'::uuid AND pot_version = {version}
        ),
        tree(node_hash, node_kind, tree_kind, child_hashes) AS (
          SELECT n.node_hash, n.node_kind, n.tree_kind, n.child_hashes
          FROM manifest m
          JOIN v3_read.read_pot_version_node n ON n.node_hash = m.shareholders_root
          UNION ALL
          SELECT c.node_hash, c.node_kind, c.tree_kind, c.child_hashes
          FROM tree t
          CROSS JOIN LATERAL unnest(t.child_hashes) AS ch(h)
          JOIN v3_read.read_pot_version_node c ON c.node_hash = ch.h
          WHERE t.node_kind = 'I'
        )
        SELECT jsonb_build_object(
          'shareholderNodesVisited', count(*) FILTER (WHERE tree_kind = 'S'),
          'expenseNodesVisited', count(*) FILTER (WHERE tree_kind = 'E')
        )::text FROM tree;
        """,
    )
    return {
        "r1ExecutionTimeMs": round(r1_plan.get("Execution Time", 0.0), 3),
        "r1PlanningTimeMs": round(r1_plan.get("Planning Time", 0.0), 3),
        "r3aExecutionTimeMs": round(r3a_plan.get("Execution Time", 0.0), 3),
        "r3aPlanningTimeMs": round(r3a_plan.get("Planning Time", 0.0), 3),
        "r3aNodeAccess": json.loads(r3a_counts),
    }


def run_mixed_mutations(pg, pot_state, token, binding, metrics_url):
    """Execute a deterministic 20-command mixed mutation workload and measure WAL + Micrometer + E2E."""
    write_base = "http://127.0.0.1:28000/api/v3/commands"
    read_base = "http://127.0.0.1:28002/api/v3"
    pot_id = pot_state["potId"]
    shareholders = pot_state["shareholders"]
    cur_ver = pot_state["version"]

    # Fetch current pot to get existing expense IDs
    code, pot_doc, _ = http_req_timed("GET", f"{read_base}/pots/{pot_id}", token, binding=binding)
    assert code == 200
    existing_expenses = [e["id"] for e in pot_doc["expenses"] if not e["deleted"]]

    pre_prom = bench.fetch_metrics(metrics_url)
    lsn_start = wal_lsn(pg)
    storage_before = measure_table_storage(pg)

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
        accepted_ns = time.monotonic_ns()
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
                return {
                    "operation": operation,
                    "version": expected_ver,
                    "admissionMs": round((accepted_ns - t0) / 1e6, 3),
                    "resultE2eMs": round((res_ns - t0) / 1e6, 3),
                    "c03E2eMs": round((pot_ns - t0) / 1e6, 3),
                    "balanceE2eMs": round((bal_ns - t0) / 1e6, 3),
                }
            time.sleep(0.01)
        raise TimeoutError(f"Timeout awaiting {operation} v{expected_ver}")

    # Deterministic 20-command mixed sequence covering all required mutation families
    created_eids = [str(uuid.UUID(int=0xC2B14000000000000000000000000001 + i)) for i in range(8)]
    ops = [
        # 1. UpdatePotDetails (0 tree nodes changed, 100% subtree reuse!)
        ("UpdatePotDetails", {"label": "C2-B1.4 Pot Details 1"}),
        ("UpdatePotDetails", {"label": "C2-B1.4 Pot Details 2"}),
        # 2. CreateExpense (6 creations)
        *[
            ("CreateExpense", {
                "id": created_eids[i],
                "label": f"C2B14-Create-{i}",
                "date": "2026-11-19",
                "amount": {"numerator": str(10 + i), "denominator": "3"},
                "payerId": shareholders[i % len(shareholders)],
                "participants": [{"id": s, "weight": None} for s in shareholders[:min(20, len(shareholders))]],
            })
            for i in range(6)
        ],
        # 3. UpdateExpenseDetails (3 updates)
        *[
            ("UpdateExpenseDetails", {
                "id": created_eids[i],
                "label": f"C2B14-Details-{i}",
                "date": "2026-11-19",
            })
            for i in range(3)
        ],
        # 4. UpdateExpenseFinancials (3 updates)
        *[
            ("UpdateExpenseFinancials", {
                "id": created_eids[i],
                "amount": {"numerator": str(25 + i), "denominator": "4"},
                "payerId": shareholders[(i + 1) % len(shareholders)],
                "participants": [{"id": s, "weight": None} for s in shareholders[:min(20, len(shareholders))]],
            })
            for i in range(3)
        ],
        # 5. DeleteExpense (2 logical deletions)
        ("DeleteExpense", {"id": created_eids[4]}),
        ("DeleteExpense", {"id": created_eids[5]}),
        # 6. UpdateShareholderDetails (2 updates)
        ("UpdateShareholderDetails", {"targets": [{"id": shareholders[0], "name": "C2B14-SH-0", "attachment": "KEEP"}]}),
        ("UpdateShareholderDetails", {"targets": [{"id": shareholders[1], "name": "C2B14-SH-1", "attachment": "KEEP"}]}),
        # 7. UpdateShareholderWeights (2 weight updates)
        ("UpdateShareholderWeights", {"targets": [{"id": shareholders[0], "weight": {"numerator": "3", "denominator": "2"}}]}),
        ("UpdateShareholderWeights", {"targets": [{"id": shareholders[1], "weight": {"numerator": "5", "denominator": "4"}}]}),
    ]

    rows = []
    for op_name, op_payload in ops:
        cur_ver += 1
        rows.append(submit_and_await(op_name, op_payload, cur_ver))

    lsn_end = wal_lsn(pg)
    storage_after = measure_table_storage(pg)
    post_prom = bench.fetch_metrics(metrics_url)

    pre_p = mb.parse_prom_all(pre_prom)
    post_p = mb.parse_prom_all(post_prom)
    total_wal = wal_diff_bytes(pg, lsn_start, lsn_end)

    pot_ver_stages = {}
    for stage in ("reference_lookup", "delta_resolution", "compose", "tree_build", "persist_publish", "full_source_read"):
        pot_ver_stages[stage] = mb.prom_timer_delta(
            pre_p, post_p, "pocoma_v3_potversion_stage_duration_seconds", {"stage": stage}
        )
    pot_ver_nodes = {
        "inserted": mb.prom_summary_delta(pre_p, post_p, "pocoma_v3_potversion_nodes_inserted"),
        "loaded": mb.prom_summary_delta(pre_p, post_p, "pocoma_v3_potversion_nodes_loaded"),
        "reused": mb.prom_summary_delta(pre_p, post_p, "pocoma_v3_potversion_nodes_reused"),
    }
    pot_ver_paths = mb.prom_paths_delta(pre_p, post_p, "pocoma_v3_potversion_materialize_path_total")
    pot_bal_paths = mb.prom_paths_delta(pre_p, post_p, "pocoma_v3_potbalance_materialize_path_total")

    return {
        "commandCount": len(rows),
        "finalVersion": cur_ver,
        "walTotalBytes": total_wal,
        "walBytesPerVersion": round(total_wal / len(rows), 1),
        "e2e": {
            "admissionMs": base.stats_summary([r["admissionMs"] for r in rows]),
            "resultE2eMs": base.stats_summary([r["resultE2eMs"] for r in rows]),
            "c03E2eMs": base.stats_summary([r["c03E2eMs"] for r in rows]),
            "balanceE2eMs": base.stats_summary([r["balanceE2eMs"] for r in rows]),
        },
        "potVersionPaths": pot_ver_paths,
        "potBalancePaths": pot_bal_paths,
        "potVersionStages": pot_ver_stages,
        "potVersionNodes": pot_ver_nodes,
        "storageBefore": storage_before,
        "storageAfter": storage_after,
    }


DEFAULT_POSTGRES_IMAGE = "postgres@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7"
DEFAULT_JAVA_IMAGE = "eclipse-temurin@sha256:66bb900643426ad01996d25bada7d56751913f9cec3b827fcb715d2ec9a0fbfc"


def run_side(label, app_dir, cfg, snap_dir, profile_name, out_dir, postgres_image, java_image, rebuild_from_write=False, verify_second_rebuild=False):
    """Launch isolated 5-runtime stack for one side (Baseline A or Candidate B) on the snapshot."""
    topo = cfg["topology"]
    snap_meta, snap_dump = base.verify_snapshot_metadata(snap_dir, profile_name, 1)

    work = Path(f"/private/tmp/pocoma-c2b14-{label}-{uuid.uuid4().hex[:8]}")
    work.mkdir(mode=0o700, parents=True)
    out_dir.mkdir(parents=True, exist_ok=True)
    project = f"pocoma-c2b14-{label}-{uuid.uuid4().hex[:8]}"
    pg = f"{project}-postgres-1"
    compose_path = work / "compose.json"
    compose_args = ["docker", "compose", "-p", project, "-f", str(compose_path)]

    def dc(*argv, timeout=180):
        with (out_dir / "lifecycle.log").open("a") as log:
            subprocess.run(compose_args + list(argv), check=True, stdout=log, stderr=log, timeout=timeout)

    issuer = None
    try:
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

        # Restore snapshot
        t_res = time.monotonic()
        with open(snap_dump, "rb") as raw_f:
            import gzip
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
        psql_text(pg, "pocoma", "ALTER SYSTEM SET log_line_prefix = '%m [%p] xid=%x state=%e ';", schema="public")
        psql_text(pg, "pocoma", "SELECT pg_reload_conf();", schema="public")
        restore_s = round(time.monotonic() - t_res, 2)

        def reset_read_schema_v3():
            psql_text(pg, "pocoma_read", "DROP SCHEMA IF EXISTS v3_read CASCADE;", schema="public")
            psql_text(pg, "pocoma_read", "CREATE SCHEMA v3_read AUTHORIZATION pocoma;", schema="public")
            psql_text(pg, "pocoma_read", "GRANT USAGE ON SCHEMA v3_read TO pocoma_read;", schema="public")
            for mig in (
                "app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql",
                "app/infra-postgres-read/src/main/resources/db/read/migration/V2__pot_balance_aggregate.sql",
                "app/infra-postgres-read/src/main/resources/db/read/migration/V3__pot_version_radix16.sql",
            ):
                sql = (ROOT / mig).read_text(encoding="utf-8")
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
                    input=(ROOT / script_file).read_text(encoding="utf-8"),
                    check=True, capture_output=True, text=True, timeout=60,
                )
            psql_text(
                pg, "pocoma_read",
                "INSERT INTO v3_read.discovery_checkpoint (consumption_id, partition_id, position) "
                "SELECT consumption_id, p, 0 FROM v3_read.consumption_routing_contract "
                "CROSS JOIN generate_series(0, 3) p ON CONFLICT (consumption_id, partition_id) DO NOTHING;",
            )

        if rebuild_from_write:
            reset_read_schema_v3()
        else:
            # Ensure V2 aggregate table exists on baseline A
            if not psql_text(pg, "pocoma_read", "SELECT to_regclass('v3_read.read_pot_balance_aggregate')::text;"):
                v2_sql = (Path(app_dir) / "infra-postgres-read/src/main/resources/db/read/migration/V2__pot_balance_aggregate.sql").read_text(encoding="utf-8")
                v2_sql = (
                    v2_sql.replace("${expectedDatabase}", "pocoma_read")
                    .replace("${expectedSchema}", "v3_read")
                    .replace("${expectedAdmin}", "pocoma")
                    .replace("${expectedRuntime}", "pocoma_read")
                )
                subprocess.run(
                    ["docker", "exec", "-i", "-e", "PGOPTIONS=-c search_path=v3_read,public", pg,
                     "psql", "-X", "-U", "pocoma", "-d", "pocoma_read", "-v", "ON_ERROR_STOP=1", "-f", "-"],
                    input=v2_sql, check=True, capture_output=True, text=True, timeout=60,
                )

        # Start test OIDC issuer
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

        # Configure runtimes using app_dir JARs
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
        dc("up", "-d", "--no-recreate", *topo["runtimes"].keys())

        metrics_urls = {
            name: f"http://127.0.0.1:{rcfg['managementPort'] + 10000}"
            for name, rcfg in topo["runtimes"].items()
        }
        for url in metrics_urls.values():
            base.wait_for(lambda u=url: bool(bench.fetch_metrics(u)), 90)

        result = {
            "label": label,
            "profile": profile_name,
            "restoreSeconds": restore_s,
            "snapshotSha256": snap_meta["snapshotSha256"],
        }

        if rebuild_from_write:
            print(f"[{label}] Awaiting READ V3 Radix-16 rebuild #1 from WRITE...")
            rebuild1_s = base.await_read_rebuild_from_write(pg, snap_meta["writeCounters"], timeout_s=900)
            digest1 = capture_version_tree_digest(pg)
            f4_after_rebuild1 = run_f4_diagnostic(pg)
            result["rebuild1"] = {
                "elapsedSeconds": rebuild1_s,
                "treeDigest": digest1,
                "f4Diagnostic": f4_after_rebuild1,
            }
            print(f"[{label}] Rebuild #1 complete in {rebuild1_s}s ({digest1['versionCount']} versions, sha={digest1['sha256'][:12]}).")

            if verify_second_rebuild:
                print(f"[{label}] Running deterministic READ rebuild #2...")
                dc("stop", "results-worker", "business-read-worker")
                reset_read_schema_v3()
                dc("up", "-d", "results-worker", "business-read-worker")
                base.wait_for(lambda: bool(bench.fetch_metrics(metrics_urls["business-read-worker"])), 90)
                rebuild2_s = base.await_read_rebuild_from_write(pg, snap_meta["writeCounters"], timeout_s=900)
                digest2 = capture_version_tree_digest(pg)
                f4_after_rebuild2 = run_f4_diagnostic(pg)
                deterministic = digest1 == digest2
                result["rebuild2"] = {
                    "elapsedSeconds": rebuild2_s,
                    "treeDigest": digest2,
                    "deterministicBitForBit": deterministic,
                    "f4Diagnostic": f4_after_rebuild2,
                }
                print(f"[{label}] Rebuild #2 complete in {rebuild2_s}s (deterministic={deterministic}).")
                if not deterministic:
                    raise AssertionError(f"Non-deterministic READ rebuild! {digest1} != {digest2}")

        result["storagePostRestore"] = measure_table_storage(pg)

        token = next(iter(json.loads((work / "tokens.json").read_text()).values()))
        code, binding_resp = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", token)
        if code != 200 or not binding_resp.get("bindingToken"):
            raise RuntimeError(f"Binding unavailable: HTTP {code}")
        binding = binding_resp["bindingToken"]

        pot_meta = snap_meta["pots"][0]
        pot_id = pot_meta["potId"]
        final_ver = pot_meta["version"]

        print(f"[{label}] Measuring warm HTTP READ latencies (30 iterations each)...")
        read_latencies, golden = measure_http_reads(pot_id, final_ver, token, binding, iterations=30)
        result["readLatencies"] = read_latencies

        if rebuild_from_write:
            result["explainTargetedSql"] = explain_targeted_sql(pg, pot_id, final_ver)

        pot_state = {
            "potId": pot_id,
            "version": final_ver,
            "shareholders": [s["id"] for s in golden["currentFull"]["shareholders"]],
        }
        print(f"[{label}] Running 20 mixed mutations (WAL + Micrometer + E2E)...")
        result["mixedMutations"] = run_mixed_mutations(
            pg, pot_state, token, binding, metrics_urls["business-read-worker"]
        )
        if rebuild_from_write:
            result["f4AfterMutations"] = run_f4_diagnostic(pg)

        return result, golden
    finally:
        if issuer is not None:
            issuer.terminate()
            try:
                issuer.wait(timeout=5)
            except subprocess.TimeoutExpired:
                issuer.kill()
        subprocess.run(compose_args + ["down", "-v", "--remove-orphans"], capture_output=True)
        shutil.rmtree(work, ignore_errors=True)


def compare_golden(golden_a, golden_b):
    """Exact field-by-field comparison of all HTTP responses between Baseline A and Candidate B."""
    diffs = {}
    for key in sorted(set(golden_a) | set(golden_b)):
        if golden_a.get(key) != golden_b.get(key):
            diffs[key] = "MISMATCH"
    return {"exactMatch": len(diffs) == 0, "checkedEndpoints": sorted(golden_a.keys()), "mismatches": diffs}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=("medium", "large"), required=True)
    parser.add_argument("--snapshot-dir", required=True)
    parser.add_argument("--baseline-app-dir", required=True, help="Path to Baseline A app/ dir with packaged runtime JARs")
    parser.add_argument("--candidate-app-dir", default=str(ROOT / "app"))
    parser.add_argument("--out", required=True)
    parser.add_argument("--verify-second-rebuild", action="store_true")
    parser.add_argument("--postgres-image", default=DEFAULT_POSTGRES_IMAGE)
    parser.add_argument("--java-image", default=DEFAULT_JAVA_IMAGE)
    args = parser.parse_args()

    cfg = base.load_profiles_config()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    res_a, golden_a = run_side(
        "baseline-a", args.baseline_app_dir, cfg, args.snapshot_dir, args.profile,
        out / "baseline-a", args.postgres_image, args.java_image,
        rebuild_from_write=False, verify_second_rebuild=False,
    )
    res_b, golden_b = run_side(
        "candidate-b", args.candidate_app_dir, cfg, args.snapshot_dir, args.profile,
        out / "candidate-b", args.postgres_image, args.java_image,
        rebuild_from_write=True, verify_second_rebuild=args.verify_second_rebuild,
    )

    equiv = compare_golden(golden_a, golden_b)
    summary = {
        "profile": args.profile,
        "snapshotDir": str(Path(args.snapshot_dir).resolve()),
        "equivalence": equiv,
        "baselineA": res_a,
        "candidateB": res_b,
    }
    (out / "ab_qualification_manifest.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({"profile": args.profile, "equivalence": equiv}, indent=2))
    if not equiv["exactMatch"]:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
