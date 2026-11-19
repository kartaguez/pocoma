#!/usr/bin/env python3
"""Pocoma V3 — L7.4-A Local Qualification Bench & Pilot Campaign Runner.

Reuses the existing L7.4 disposable 5-runtime bench (spec09_bench,
smoke_test_5_runtimes, v3-seed, spec09-test-issuer.mjs) to measure the
4 processing stages separately on Medium (20/200) and Large (100/2000) Pots.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from fractions import Fraction
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import smoke_test_5_runtimes as oracle
import spec09_bench as bench

ROOT = bench.ROOT
PROFILES_PATH = SCRIPT_DIR / "l7-4a-profiles.json"
PROPERTIES_DIR = ROOT / "docker" / "v3-five-runtimes"


class BenchEnvironmentLimit(Exception):
    """Raised when host/container resources or pre-flight guardrails fail."""


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, SCRIPT_DIR / filename)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def load_profiles_config(path=PROFILES_PATH):
    return json.loads(Path(path).read_text())


def parse_runtime_properties(prop_path):
    props = {}
    for line in Path(prop_path).read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        props[k.strip()] = v.strip()
    return props


def collect_runtime_config_summary(runtimes_cfg):
    summary = {}
    keys_of_interest = (
        "pocoma.v3.http.admission.capacity",
        "pocoma.v3.worker.min-poll",
        "pocoma.v3.worker.max-poll",
        "pocoma.v3.worker.error-poll",
        "pocoma.v3.worker.quantum",
        "pocoma.v3.worker.worker-limit",
        "pocoma.v3.worker.consumption-limits",
        "pocoma.v3.worker.partitions",
        "pocoma.v3.worker.lifecycle.batch-size",
        "pocoma.v3.worker.lifecycle.claim-duration-ms",
        "pocoma.v3.worker.lifecycle.lease-duration-ms",
    )
    for name in runtimes_cfg:
        prop_file = PROPERTIES_DIR / f"{name}.properties"
        parsed = parse_runtime_properties(prop_file)
        summary[name] = {k: parsed[k] for k in keys_of_interest if k in parsed}
        summary[name]["sha256"] = bench.digest(prop_file)
    return summary


def check_port_free(port):
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.3)
        return s.connect_ex(("127.0.0.1", port)) != 0


def preflight_guardrails(work, out, guardrails):
    docker_info_raw = bench.command(["docker", "info", "--format", "{{json .}}"])
    docker_info = json.loads(docker_info_raw)
    if docker_info.get("MemTotal", 0) < guardrails["minDockerMemBytes"]:
        raise BenchEnvironmentLimit(
            f"Docker MemTotal {docker_info.get('MemTotal')} < {guardrails['minDockerMemBytes']}"
        )
    if shutil.disk_usage(work).free < guardrails["minDiskFreeBytes"]:
        raise BenchEnvironmentLimit(
            f"Free disk space {shutil.disk_usage(work).free} < {guardrails['minDiskFreeBytes']}"
        )
    running = bench.command(["docker", "ps", "--format", "{{.Names}}"]).splitlines()
    if any(name.startswith("pocoma-l74a-") or name.startswith("pocoma-spec09-") for name in running):
        raise BenchEnvironmentLimit(f"Concurrent bench container(s) already running: {running}")
    for port in (28000, 28002, 28080, 28082, 28083, 28084, 28085, 28090):
        if not check_port_free(port):
            raise BenchEnvironmentLimit(f"Port {port} is already in use")
    if out.exists() and any(out.iterdir()):
        raise ValueError(f"Output directory {out} must be new or empty")
    return {k: docker_info.get(k) for k in ("Name", "NCPU", "MemTotal", "ServerVersion", "Architecture")}


def wait_for(probe, seconds=120):
    deadline = time.monotonic() + seconds
    while True:
        try:
            if probe():
                return
        except (OSError, ValueError, subprocess.CalledProcessError):
            pass
        if time.monotonic() >= deadline:
            raise TimeoutError("Readiness deadline exceeded")
        time.sleep(0.5)


def stats_summary(values):
    clean = [v for v in values if v is not None]
    if not clean:
        return {"n": 0, "p50": None, "p95": None, "p99": None, "max": None}
    return {
        "n": len(clean),
        "p50": round(bench.quantile(clean, 0.50), 3),
        "p95": round(bench.quantile(clean, 0.95), 3),
        "p99": round(bench.quantile(clean, 0.99), 3),
        "max": round(max(clean), 3),
    }


def _poll_until_200(path, accepted_ns, stop_event, deadline_s, poll_s, token, binding):
    url = "http://127.0.0.1:28002/api/v3" + path
    while time.monotonic_ns() - accepted_ns < deadline_s * 1e9:
        if stop_event.is_set():
            raise ValueError("bench stopped during polling")
        code, result = oracle.request("GET", url, token, binding=binding)
        now_ns = time.monotonic_ns()
        if now_ns - accepted_ns >= deadline_s * 1e9:
            raise TimeoutError(f"observation deadline exceeded for {path}")
        if code == 200:
            return result, now_ns
        if code not in (404, 503):
            raise ValueError(f"unexpected HTTP {code} on {path}")
        time.sleep(poll_s)
    raise TimeoutError(f"observation deadline exceeded for {path}")


@dataclass(frozen=True)
class AttemptContext:
    state: dict
    token: str
    binding: str
    stop: threading.Event
    lock: threading.Lock
    deadline_s: float
    poll_s: float
    max_offer_lag_ms: float
    is_warmup: bool


def execute_attempt(index, scheduled_s, ctx):
    lag_ms = (time.monotonic() - scheduled_s) * 1000.0
    row = {
        "index": index,
        "warmup": ctx.is_warmup,
        "status": "NOT_ATTEMPTED",
        "scheduledNs": round(scheduled_s * 1e9),
        "offerLagMs": round(lag_ms, 3),
    }
    if ctx.stop.is_set():
        return row
    if lag_ms > ctx.max_offer_lag_ms:
        return {**row, "status": "DROPPED_OFFER", "reason": "offer_lag_exceeded"}
    if not ctx.lock.acquire(blocking=False):
        return {**row, "status": "DROPPED_OFFER", "reason": "pot_busy"}
    try:
        expense_id = str(uuid.uuid4())
        idem_key = str(uuid.uuid4())
        payer = ctx.state["shareholders"][index % len(ctx.state["shareholders"])]
        amount = Fraction(index + 1, 3)
        expected = oracle.expected_balances(ctx.state, payer, amount)
        expected_version = ctx.state["version"] + 1
        payload = {
            "operation": "CreateExpense",
            "potId": ctx.state["potId"],
            "observedVersion": ctx.state["version"],
            "bindingToken": ctx.binding,
            "payload": {
                "id": expense_id,
                "label": f"L74A-{index}",
                "date": "2026-11-18",
                "amount": {"numerator": str(index + 1), "denominator": "3"},
                "payerId": payer,
                "participants": [{"id": s, "weight": None} for s in ctx.state["shareholders"]],
            },
        }
        began_ns = time.monotonic_ns()
        row["sentNs"] = began_ns
        status, admitted = oracle.request(
            "POST", "http://127.0.0.1:28000/api/v3/commands", ctx.token, payload, key=idem_key
        )
        accepted_ns = time.monotonic_ns()
        row["acceptedNs"] = accepted_ns
        row["admissionMs"] = round((accepted_ns - began_ns) / 1e6, 3)
        if status != 202:
            raise ValueError(f"admission refused: HTTP {status}")
        request_id = str(uuid.UUID(admitted["requestId"]))
        row["requestId"] = request_id

        with ThreadPoolExecutor(max_workers=3) as poll_pool:
            f_res = poll_pool.submit(
                _poll_until_200,
                f"/commands/{request_id}/result",
                accepted_ns,
                ctx.stop,
                ctx.deadline_s,
                ctx.poll_s,
                ctx.token,
                None,
            )
            f_pot = poll_pool.submit(
                _poll_until_200,
                f"/pots/{ctx.state['potId']}?minVersion={expected_version}",
                accepted_ns,
                ctx.stop,
                ctx.deadline_s,
                ctx.poll_s,
                ctx.token,
                ctx.binding,
            )
            f_bal = poll_pool.submit(
                _poll_until_200,
                f"/pots/{ctx.state['potId']}/versions/{expected_version}/balance",
                accepted_ns,
                ctx.stop,
                ctx.deadline_s,
                ctx.poll_s,
                ctx.token,
                ctx.binding,
            )
            result, result_at_ns = f_res.result()
            pot, pot_at_ns = f_pot.result()
            balance, bal_at_ns = f_bal.result()

        version = oracle.applied_version(result, ctx.state["version"])
        oracle.validate_complete(pot, balance, ctx.state, version, expense_id, expected)
        row.update(
            {
                "status": "OBSERVED",
                "potId": ctx.state["potId"],
                "version": version,
                "resultE2eMs": round((result_at_ns - began_ns) / 1e6, 3),
                "resultAfter202Ms": round((result_at_ns - accepted_ns) / 1e6, 3),
                "c03E2eMs": round((pot_at_ns - began_ns) / 1e6, 3),
                "c03After202Ms": round((pot_at_ns - accepted_ns) / 1e6, 3),
                "balanceE2eMs": round((bal_at_ns - began_ns) / 1e6, 3),
                "balanceAfter202Ms": round((bal_at_ns - accepted_ns) / 1e6, 3),
            }
        )
        ctx.state.update(version=version, expenses=ctx.state["expenses"] + 1, balances=expected)
        return row
    except AssertionError as error:
        row.update(status="INVARIANT_VIOLATION", error=str(error))
        ctx.stop.set()
    except TimeoutError as error:
        row.update(status="CENSORED", error=str(error))
        ctx.stop.set()
    except Exception as error:
        row.update(
            status="INVARIANT_VIOLATION" if isinstance(error, ValueError) else "PROBE_ERROR",
            error=f"{type(error).__name__}: {error}",
        )
        ctx.stop.set()
    finally:
        ctx.lock.release()
    return row


def seed_profile_pots(pg, profile_name, pot_count, seed_id, token, binding, out):
    seed_mod = load_module("l74a_seed", "v3-seed.py")
    seed_mod.POSTGRES_CONTAINER = pg
    orig_await = seed_mod.await_complete_read

    def await_with_drain(ctx_arg, pot_arg, version_arg):
        for attempt in range(180):
            try:
                return orig_await(ctx_arg, pot_arg, version_arg)
            except TimeoutError:
                r = bench.sql(
                    pg,
                    "pocoma_read",
                    "SELECT jsonb_build_object("
                    "'pvMax', (SELECT max(pot_version) FROM v3_read.read_pot_version WHERE pot_id='" + pot_arg["potId"] + "'::uuid),"
                    "'pbMax', (SELECT max(pot_version) FROM v3_read.read_pot_balance WHERE pot_id='" + pot_arg["potId"] + "'::uuid),"
                    "'slots', (SELECT coalesce(jsonb_agg(x),'[]') FROM "
                    "(SELECT consumption_id, state, count(*) n FROM v3_read.consumption_slot GROUP BY 1,2) x))",
                )
                print(f"     [drain wait {attempt+1}/180] pv={r['pvMax']} pb={r['pbMax']} slots={r['slots']}", flush=True)
                if attempt == 179:
                    raise

    seed_mod.await_complete_read = await_with_drain
    data = seed_mod.create_seed(profile_name, seed_id, pots_count=pot_count)
    ctx = seed_mod._MaterializeContext("http://127.0.0.1:28000", "http://127.0.0.1:28002", token, binding)

    t0 = time.monotonic()
    with ThreadPoolExecutor(max_workers=min(4, pot_count)) as pool:
        proofs = list(pool.map(lambda p: seed_mod._materialize_pot(ctx, p), data["pots"]))
    seed_elapsed = round(time.monotonic() - t0, 2)
    data["materialized"] = True
    data["materializationProof"] = proofs
    (out / "seed.json").write_text(json.dumps(data, indent=2))

    states = []
    for pot in data["pots"]:
        code, current = oracle.request(
            "GET", f"http://127.0.0.1:28002/api/v3/pots/{pot['potId']}", token, binding=binding
        )
        if code != 200:
            raise RuntimeError(f"Failed to load seeded pot {pot['potId']}: HTTP {code}")
        states.append(
            oracle.initial_state(
                current,
                {
                    "potId": pot["potId"],
                    "shareholders": [s["id"] for s in pot["shareholders"]],
                    "expenses": len(pot["expenses"]),
                },
            )
        )
    return states, seed_elapsed


def wait_for_drainage(pg, timeout_s=20):
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        w = bench.sql(
            pg,
            "pocoma_write",
            "SELECT jsonb_build_object('pending', (SELECT count(*) FROM v3_write.v3_command WHERE status='PENDING'),"
            " 'slots', (SELECT count(*) FROM v3_write.consumption_slot))",
        )
        r = bench.sql(
            pg,
            "pocoma_read",
            "SELECT jsonb_build_object('slots', (SELECT count(*) FROM v3_read.consumption_slot))",
        )
        if w["pending"] == 0 and w["slots"] == 0 and r["slots"] == 0:
            return
        time.sleep(0.1)
    raise TimeoutError("Drainage deadline exceeded: residual PENDING commands or slots remain")


def query_write_stage_timings(pg, sample_rows):
    observed = [r for r in sample_rows if r.get("status") == "OBSERVED" and r.get("requestId")]
    if not observed:
        return {}
    values = ",".join(f"('{uuid.UUID(r['requestId'])}'::uuid)" for r in observed)
    rows = bench.sql(
        pg,
        "pocoma_write",
        f"""
        WITH ids(request_id) AS (VALUES {values})
        SELECT coalesce(jsonb_agg(x), '[]') FROM (
          SELECT
            q.request_id::text AS request_id,
            e.event_id::text AS pot_event_id,
            c.event_id::text AS completion_event_id,
            e.recorded_at AS pot_event_recorded_at,
            c.recorded_at AS completion_recorded_at
          FROM ids
          JOIN v3_write.v3_command q USING (request_id)
          JOIN v3_write.v3_command_result r USING (request_id)
          JOIN v3_write.v3_pot_event e ON e.request_id = q.request_id AND e.subject_version = r.resulting_pot_version
          JOIN v3_write.v3_command_completion_event c ON c.subject_id = q.request_id
        ) x
        """,
    )
    return {row["request_id"]: row for row in rows}


def parse_handoff_logs(project, out, sample_request_ids, write_timings):
    pot_event_to_req = {v["pot_event_id"]: k for k, v in write_timings.items() if v.get("pot_event_id")}
    comp_event_to_req = {v["completion_event_id"]: k for k, v in write_timings.items() if v.get("completion_event_id")}
    req_set = set(sample_request_ids)

    cmd_metrics = {
        "discoveryToSlotMs": [],
        "slotAcquisitionWaitMs": [],
        "acquisitionToAttemptMs": [],
        "commandProcessingMs": [],
    }
    proj_metrics = {
        "PotVersion": [],
        "PotBalance": [],
        "PotVisibility": [],
        "CommandResult": [],
    }

    for worker in ("command-worker", "business-read-worker", "results-worker"):
        container = f"{project}-{worker}-1"
        dest = out / f"handoffs-{worker}.jsonl"
        res = subprocess.run(
            ["docker", "cp", f"{container}:/tmp/pocoma-stage-handoffs.jsonl", str(dest)],
            capture_output=True,
            text=True,
        )
        if res.returncode != 0 or not dest.exists():
            continue
        for line in dest.read_text().splitlines():
            if not line.strip():
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                continue
            stage = rec.get("stage")
            if worker == "command-worker":
                cid = rec.get("commandId")
                if cid not in req_set:
                    continue
                if stage == "T1":
                    for k in ("discoveryToSlotMs", "slotAcquisitionWaitMs", "acquisitionToAttemptMs"):
                        if rec.get(k) is not None:
                            cmd_metrics[k].append(float(rec[k]))
                elif stage == "T2" and rec.get("t1ToT2Ms") is not None:
                    cmd_metrics["commandProcessingMs"].append(float(rec["t1ToT2Ms"]))
            else:
                eid = rec.get("eventId")
                cons = rec.get("consumption")
                if stage == "T4" and cons in proj_metrics and rec.get("t3ToT4Ms") is not None:
                    if eid in pot_event_to_req or eid in comp_event_to_req:
                        proj_metrics[cons].append(float(rec["t3ToT4Ms"]))

    return {
        "commandWorker": {k: stats_summary(v) for k, v in cmd_metrics.items()},
        "projections": {k: stats_summary(v) for k, v in proj_metrics.items()},
    }


SNAPSHOT_SUBJECT = "l74a-snapshot-user"


def sha256_file(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_snapshot_metadata(snap_dir, expected_profile, min_pot_count):
    meta_path = Path(snap_dir) / "snapshot_meta.json"
    dump_path = Path(snap_dir) / "snapshot.sql.gz"
    if not meta_path.is_file() or not dump_path.is_file():
        raise FileNotFoundError(f"Snapshot files missing in {snap_dir}")
    meta = json.loads(meta_path.read_text())
    if meta.get("profile") != expected_profile:
        raise ValueError(f"Snapshot profile {meta.get('profile')} != {expected_profile}")
    if meta.get("potCount", 0) < min_pot_count:
        raise ValueError(f"Snapshot potCount {meta.get('potCount')} < {min_pot_count}")
    actual_sha = sha256_file(dump_path)
    if actual_sha != meta.get("snapshotSha256"):
        raise ValueError("Snapshot SHA-256 mismatch: corrupted or tampered snapshot")
    return meta, dump_path


def capture_snapshot(pg, dc, topo, camp, prof, states, seed_elapsed, snap_dir, initial_snap, git_sha):
    t0 = time.monotonic()
    bench.owned(pg)
    dc("stop", *topo["runtimes"].keys())

    w_check = bench.sql(
        pg,
        "pocoma_write",
        "SELECT jsonb_build_object("
        "'pending', (SELECT count(*) FROM v3_write.v3_command WHERE status='PENDING'),"
        "'slots', (SELECT count(*) FROM v3_write.consumption_slot),"
        "'leases', (SELECT count(*) FROM v3_write.partition_ownership WHERE owner_token IS NOT NULL AND owned_until > clock_timestamp()))",
    )
    r_check = bench.sql(
        pg,
        "pocoma_read",
        "SELECT jsonb_build_object("
        "'slots', (SELECT count(*) FROM v3_read.consumption_slot),"
        "'maintenance', (SELECT state FROM public.read_maintenance_state),"
        "'leases', (SELECT count(*) FROM v3_read.partition_ownership WHERE owner_token IS NOT NULL AND owned_until > clock_timestamp()))",
    )
    if w_check["pending"] != 0 or w_check["slots"] != 0 or r_check["slots"] != 0:
        raise AssertionError(f"Cannot snapshot with residual work: write={w_check} read={r_check}")
    if r_check["maintenance"] != "READY":
        raise AssertionError(f"READ maintenance not READY: {r_check}")

    snap_dir = Path(snap_dir)
    snap_dir.mkdir(parents=True, exist_ok=True)
    dump_path = snap_dir / "snapshot.sql.gz"
    proc = subprocess.run(
        ["docker", "exec", "-i", pg, "pg_dumpall", "-U", "pocoma"],
        capture_output=True,
        check=True,
        timeout=300,
    )
    with gzip.open(dump_path, "wb", compresslevel=6) as gz:
        gz.write(proc.stdout)

    snap_sha = sha256_file(dump_path)
    snap_elapsed = round(time.monotonic() - t0, 2)
    meta = {
        "createdAt": bench.utc(),
        "gitSha": git_sha,
        "profile": camp["profile"],
        "profileSpec": prof,
        "potCount": len(states),
        "seedId": camp["seedId"],
        "tokenSubject": SNAPSHOT_SUBJECT,
        "seedElapsedSeconds": seed_elapsed,
        "snapshotElapsedSeconds": snap_elapsed,
        "snapshotSha256": snap_sha,
        "snapshotBytes": dump_path.stat().st_size,
        "writeState": w_check,
        "readState": r_check,
        "writeCounters": initial_snap["databases"]["write"]["counters"],
        "writeCheckpointsCount": len(initial_snap["databases"]["write"]["checkpoints"]),
        "readCheckpointsCount": len(initial_snap["databases"]["read"]["checkpoints"]),
        "pots": [
            {
                "potId": s["potId"],
                "version": s["version"],
                "expenses": s["expenses"],
                "shareholdersCount": len(s["shareholders"]),
            }
            for s in states
        ],
    }
    (snap_dir / "snapshot_meta.json").write_text(json.dumps(meta, indent=2) + "\n")
    return meta


def restore_snapshot_into_pg(pg, dump_path, out):
    t0 = time.monotonic()
    bench.owned(pg)
    with gzip.open(dump_path, "rt", encoding="utf-8") as gz:
        sql_text = "\n".join(
            line for line in gz.read().splitlines()
            if line.strip() != "CREATE ROLE pocoma;"
            and not line.strip().startswith("CREATE DATABASE pocoma ")
        )
    res = subprocess.run(
        ["docker", "exec", "-i", pg, "psql", "-v", "ON_ERROR_STOP=1", "-U", "pocoma", "-d", "postgres"],
        input=sql_text.encode("utf-8"),
        capture_output=True,
        timeout=300,
    )
    (out / "restore.log").write_bytes(res.stdout + b"\n" + res.stderr)
    if res.returncode != 0:
        raise RuntimeError(f"Snapshot restore failed with code {res.returncode}")
    subprocess.run(
        [
            "docker", "exec", "-i", pg, "psql", "-v", "ON_ERROR_STOP=1", "-U", "pocoma", "-d", "pocoma",
            "-c", "ALTER SYSTEM SET log_line_prefix = '%m [%p] xid=%x state=%e ';",
            "-c", "SELECT pg_reload_conf();",
        ],
        check=True,
        capture_output=True,
        timeout=30,
    )
    # Check whether the restored READ schema is already on V3 (Radix-16).
    # Legacy V1/V2 snapshots must rebuild READ from authoritative WRITE events (arbitrage F5).
    radix_check = subprocess.run(
        [
            "docker", "exec", "-i", pg, "psql", "-X", "-U", "pocoma", "-d", "pocoma_read",
            "-v", "ON_ERROR_STOP=1", "-tAc",
            "SELECT to_regclass('v3_read.read_pot_version_node')::text;",
        ],
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    needs_rebuild = not bool(radix_check.stdout.strip())
    if needs_rebuild:
        def _psql_read(sql_cmd, schema="v3_read"):
            subprocess.run(
                [
                    "docker", "exec", "-i", "-e", f"PGOPTIONS=-c search_path={schema},public", pg,
                    "psql", "-X", "-U", "pocoma", "-d", "pocoma_read", "-v", "ON_ERROR_STOP=1", "-c", sql_cmd,
                ],
                check=True, capture_output=True, text=True, timeout=60,
            )

        def _psql_read_file(sql_text, schema="v3_read", variables=None):
            vars_args = []
            for k, v in (variables or {}).items():
                vars_args.extend(["-v", f"{k}={v}"])
            subprocess.run(
                [
                    "docker", "exec", "-i", "-e", f"PGOPTIONS=-c search_path={schema},public", pg,
                    "psql", "-X", "-U", "pocoma", "-d", "pocoma_read", "-v", "ON_ERROR_STOP=1", *vars_args, "-f", "-",
                ],
                input=sql_text, check=True, capture_output=True, text=True, timeout=60,
            )

        _psql_read("DROP SCHEMA IF EXISTS v3_read CASCADE;", schema="public")
        _psql_read("CREATE SCHEMA v3_read AUTHORIZATION pocoma;", schema="public")
        _psql_read("GRANT USAGE ON SCHEMA v3_read TO pocoma_read;", schema="public")
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
            _psql_read_file(sql)
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
            _psql_read_file((ROOT / script_file).read_text(encoding="utf-8"), variables=variables)
        _psql_read(
            "INSERT INTO v3_read.discovery_checkpoint (consumption_id, partition_id, position) "
            "SELECT consumption_id, p, 0 FROM v3_read.consumption_routing_contract "
            "CROSS JOIN generate_series(0, 3) p ON CONFLICT (consumption_id, partition_id) DO NOTHING;"
        )
    return round(time.monotonic() - t0, 2), needs_rebuild


def await_read_rebuild_from_write(pg, expected_write_counters, timeout_s=600):
    t0 = time.monotonic()
    deadline = t0 + timeout_s
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
            "'missingVis', (SELECT count(*) FROM v3_read.read_current_pot cp LEFT JOIN v3_read.read_pot_visibility_state vs ON cp.pot_id = vs.pot_id AND cp.pot_version <= vs.materialized_version WHERE vs.pot_id IS NULL))",
        )
        cp_ok = len(cp_rows) == 28 and all(
            row["pos"] >= expected_write_counters.get(row["family"].removesuffix("_EVENT"), 0)
            for row in cp_rows
        )
        slots_ok = slots["total"] == 0 and slots["quarantined"] == 0
        integrity_ok = all(v == 0 for v in integrity.values())
        if cp_ok and slots_ok and integrity_ok:
            return round(time.monotonic() - t0, 2)
        time.sleep(0.5)
    raise TimeoutError(f"READ rebuild from WRITE did not converge within {timeout_s}s")


def verify_restored_state(pg, meta, pot_count, token, out, metrics_urls, rebuilt=False):
    snap = bench.snapshot(pg, metrics_urls, out, "initial")
    bench.validate_progress(snap, snap)
    write_db = snap["databases"]["write"]
    read_db = snap["databases"]["read"]
    if write_db["counters"] != meta["writeCounters"]:
        raise AssertionError("Restored write counters mismatch snapshot metadata")
    if len(write_db["checkpoints"]) != meta["writeCheckpointsCount"]:
        raise AssertionError("Restored write checkpoints count mismatch")
    if rebuilt:
        if len(read_db["checkpoints"]) < meta["readCheckpointsCount"]:
            raise AssertionError("Rebuilt read checkpoints count below snapshot metadata")
    elif len(read_db["checkpoints"]) != meta["readCheckpointsCount"]:
        raise AssertionError("Restored read checkpoints count mismatch")

    code, binding_resp = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", token)
    if code != 200 or not binding_resp.get("bindingToken"):
        raise RuntimeError(f"Restored binding unavailable: HTTP {code}")
    binding = binding_resp["bindingToken"]

    states = []
    for pmeta in meta["pots"][:pot_count]:
        pid = pmeta["potId"]
        code, current = oracle.request("GET", f"http://127.0.0.1:28002/api/v3/pots/{pid}", token, binding=binding)
        if code != 200:
            raise AssertionError(f"Restored pot {pid} unreadable: HTTP {code}")
        st = oracle.initial_state(
            current,
            {
                "potId": pid,
                "shareholders": [s["id"] for s in current["shareholders"]],
                "expenses": pmeta["expenses"],
            },
        )
        if st["version"] != pmeta["version"]:
            raise AssertionError(f"Restored pot {pid} version {st['version']} != {pmeta['version']}")
        states.append(st)
    return snap, binding, states


def _configure_services(args, cfg, work, ip, definition):
    topo = cfg["topology"]
    for name, rcfg in topo["runtimes"].items():
        mod = rcfg["module"]
        jar = ROOT / "app" / mod / "target" / f"pocoma-{mod}-0.0.1-SNAPSHOT-exec.jar"
        if not jar.is_file():
            raise ValueError(f"Package runtime first: {mod} ({jar} missing)")
        content = (PROPERTIES_DIR / f"{name}.properties").read_text()
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
            "image": args.java_image,
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


def run_campaign(args, cfg, campaign_name, out):
    camp = cfg["campaigns"][campaign_name]
    prof = cfg["profiles"][camp["profile"]]
    guardrails = cfg["guardrails"]
    topo = cfg["topology"]
    mode = args.mode
    if mode in ("prepare", "restore-run") and not args.snapshot_dir:
        raise ValueError(f"--snapshot-dir is required for --mode {mode}")
    snap_meta_pre = None
    snap_dump_path = None
    if mode == "restore-run":
        snap_meta_pre, snap_dump_path = verify_snapshot_metadata(
            args.snapshot_dir, camp["profile"], camp["potCount"]
        )

    work = Path(f"/private/tmp/pocoma-l74a-work-{uuid.uuid4().hex[:8]}")
    work.mkdir(mode=0o700, parents=True)
    out.mkdir(parents=True, exist_ok=True)

    project = f"pocoma-l74a-{uuid.uuid4().hex[:8]}"
    pg = f"{project}-postgres-1"
    compose_path = work / "compose.json"
    compose_args = ["docker", "compose", "-p", project, "-f", str(compose_path)]
    pre_prov = bench.provenance()

    manifest = {
        "campaign": campaign_name,
        "mode": mode.upper(),
        "profile": camp["profile"],
        "profileSpec": prof,
        "campaignSpec": camp,
        "startedAt": bench.utc(),
        "project": project,
        "git": {"sha": pre_prov["sha"], "dirty": pre_prov["dirty"]},
        "runtimeConfig": collect_runtime_config_summary(topo["runtimes"]),
        "topology": topo,
        "guardrails": guardrails,
        "verdict": "INCONCLUSIVE",
        "safety": "INCONCLUSIVE",
    }

    def dc(*argv, timeout=120):
        with (out / "lifecycle.log").open("a") as log:
            subprocess.run(compose_args + list(argv), check=True, stdout=log, stderr=log, timeout=timeout)

    issuer = None
    stop = threading.Event()
    collector = None
    collector_errors = []
    resource_samples = []
    all_rows = []

    try:
        manifest["docker"] = preflight_guardrails(work, out, guardrails)
        manifest["toolVersions"] = {
            "python": sys.version.split()[0],
            "node": bench.command(["node", "--version"]),
            "docker": manifest["docker"]["ServerVersion"],
        }
        definition = {
            "services": {
                "postgres": {
                    "image": args.postgres_image,
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
        wait_for(lambda: bench.command(["docker", "exec", pg, "pg_isready", "-h", "127.0.0.1", "-U", "pocoma"]) != "")
        ip = next(iter(bench.owned(pg)["NetworkSettings"]["Networks"].values()))["IPAddress"]

        rebuilt_read = False
        if mode == "restore-run":
            print(f"[{campaign_name}] Restoring snapshot from {args.snapshot_dir}...")
            restore_elapsed, rebuilt_read = restore_snapshot_into_pg(pg, snap_dump_path, out)
            manifest["restore"] = {
                "snapshotDir": str(Path(args.snapshot_dir).resolve()),
                "snapshotSha256": snap_meta_pre["snapshotSha256"],
                "restoreElapsedSeconds": restore_elapsed,
                "prepareSeedElapsedSeconds": snap_meta_pre["seedElapsedSeconds"],
                "readRebuiltFromWrite": rebuilt_read,
            }
            subject = snap_meta_pre["tokenSubject"]
        else:
            with (out / "bootstrap.log").open("w") as log:
                subprocess.run(
                    [sys.executable, "-B", str(ROOT / "scripts/operations/setup-v3-database.py"), "--postgres-container", pg],
                    check=True,
                    stdout=log,
                    stderr=log,
                    timeout=100,
                )
            subject = SNAPSHOT_SUBJECT if mode == "prepare" else f"l74a-{project}"

        (work / "subjects.json").write_text(json.dumps([subject]))
        issuer_log = (out / "issuer.log").open("w")
        issuer = subprocess.Popen(
            [
                "node",
                str(ROOT / "scripts/performance/spec09-test-issuer.mjs"),
                "--host",
                "0.0.0.0",
                "--port",
                "28090",
                "--issuer",
                "http://pocoma-l74a.invalid",
                "--audience",
                "pocoma-api",
                "--subjects",
                str(work / "subjects.json"),
                "--token-file",
                str(work / "tokens.json"),
                "--ttl-seconds",
                "3600",
            ],
            stdout=issuer_log,
            stderr=issuer_log,
        )
        wait_for(lambda: (work / "tokens.json").is_file(), 10)

        _configure_services(args, cfg, work, ip, definition)
        compose_path.write_text(json.dumps(definition))
        dc("up", "-d", "--no-recreate", *topo["runtimes"].keys())

        metrics_urls = {
            name: f"http://127.0.0.1:{rcfg['managementPort'] + 10000}"
            for name, rcfg in topo["runtimes"].items()
        }
        for url in metrics_urls.values():
            wait_for(lambda u=url: bool(bench.fetch_metrics(u)), 90)

        token = next(iter(json.loads((work / "tokens.json").read_text()).values()))
        if mode == "restore-run":
            if rebuilt_read:
                print(f"[{campaign_name}] Legacy V1/V2 READ snapshot detected — rebuilding READ V3 Radix-16 from WRITE...")
                rebuild_elapsed = await_read_rebuild_from_write(pg, snap_meta_pre["writeCounters"], timeout_s=900)
                manifest["restore"]["readRebuildElapsedSeconds"] = rebuild_elapsed
                print(f"[{campaign_name}] READ V3 Radix-16 rebuilt from WRITE in {rebuild_elapsed}s.")
            initial_snap, binding, states = verify_restored_state(
                pg, snap_meta_pre, camp["potCount"], token, out, metrics_urls, rebuilt=rebuilt_read
            )
            os.environ.update(POCOMA_TOKEN=token, POCOMA_BINDING_TOKEN=binding)
            manifest["initialSnapshot"] = initial_snap
        else:
            registration = load_module("l74a_reg", "run-l7-spec09-registration.py")
            identity = registration.observe_one("http://127.0.0.1:28000", "http://127.0.0.1:28002", token, str(uuid.uuid4()))
            if identity["outcome"] != "AUTHORIZED":
                raise RuntimeError(f"Registration failed: {identity}")
            code, binding_resp = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", token)
            if code != 200 or not binding_resp.get("bindingToken"):
                raise RuntimeError(f"Binding unavailable: HTTP {code}")
            binding = binding_resp["bindingToken"]
            os.environ.update(POCOMA_TOKEN=token, POCOMA_BINDING_TOKEN=binding)

            print(f"[{campaign_name}] Seeding {camp['potCount']} {camp['profile']} pots...")
            states, seed_elapsed = seed_profile_pots(
                pg, camp["profile"], camp["potCount"], camp["seedId"], token, binding, out
            )
            wait_for_drainage(pg, timeout_s=300)
            manifest["seed"] = {"seedId": camp["seedId"], "potCount": len(states), "seedElapsedSeconds": seed_elapsed}

            initial_snap = bench.snapshot(pg, metrics_urls, out, "initial")
            bench.validate_progress(initial_snap, initial_snap)
            manifest["initialSnapshot"] = initial_snap

            if mode == "prepare":
                print(f"[{campaign_name}] Capturing PostgreSQL snapshot to {args.snapshot_dir}...")
                snap_meta = capture_snapshot(
                    pg, dc, topo, camp, prof, states, seed_elapsed, args.snapshot_dir, initial_snap, pre_prov["sha"]
                )
                manifest["snapshotMeta"] = snap_meta
                manifest["verdict"] = "PASS"
                manifest["safety"] = "PASS"
                print(json.dumps({"campaign": campaign_name, "mode": "PREPARE", "verdict": "PASS"}))
                return 0

        def collect_loop():
            idx = 0
            while not stop.wait(guardrails["resourceSampleIntervalSeconds"]):
                try:
                    snap = bench.snapshot(pg, metrics_urls, out, f"run-{idx}")
                    idx += 1
                    containers = [f"{project}-{n}-1" for n in ("postgres", *topo["runtimes"])]
                    stats = [
                        json.loads(line)
                        for line in bench.command(
                            ["docker", "stats", "--no-stream", "--format", "{{json .}}", *containers]
                        ).splitlines()
                    ]
                    resource_samples.append({"utc": bench.utc(), "stats": stats, "collectorMs": snap["collectorMs"]})
                    if any(float(row["MemPerc"].rstrip("%")) > guardrails["maxContainerMemPercent"] for row in stats):
                        raise BenchEnvironmentLimit("Container memory safety ceiling exceeded")
                except Exception as exc:
                    collector_errors.append(f"{type(exc).__name__}: {exc}")
                    stop.set()

        collector = threading.Thread(target=collect_loop, daemon=True)
        collector.start()

        total_cmds = camp["warmupCount"] + camp["sampleCount"]
        locks = [threading.Lock() for _ in states]
        rate = camp["rate"]
        deadline_s = camp.get("observationDeadlineMs", guardrails["observationDeadlineMs"]) / 1000.0
        poll_s = guardrails["readPollIntervalMs"] / 1000.0

        print(f"[{campaign_name}] Running {camp['warmupCount']} warmup + {camp['sampleCount']} sample @ {rate} cmd/s...")
        base_t = time.monotonic() + 0.3
        sample_began_ns = None
        sample_ended_ns = None
        all_rows = []
        with ThreadPoolExecutor(max_workers=camp["producers"]) as pool:
            futures = []
            for i in range(total_cmds):
                if stop.is_set():
                    break
                sched = base_t + (i / rate)
                sleep_s = sched - time.monotonic()
                if sleep_s > 0:
                    time.sleep(sleep_s)
                if stop.is_set():
                    break
                if i == camp["warmupCount"]:
                    sample_began_ns = time.monotonic_ns()
                ctx = AttemptContext(
                    states[i % len(states)],
                    token,
                    binding,
                    stop,
                    locks[i % len(states)],
                    deadline_s,
                    poll_s,
                    guardrails["maxOfferLagMs"],
                    is_warmup=(i < camp["warmupCount"]),
                )
                futures.append(pool.submit(execute_attempt, i, sched, ctx))
            for f in futures:
                all_rows.append(f.result())
            sample_ended_ns = time.monotonic_ns()

        stop.set()
        collector.join(20)
        (out / "observations.json").write_text(json.dumps(all_rows, indent=2))
        (out / "resources.json").write_text(json.dumps(resource_samples, indent=2))
        if collector_errors:
            if any("BenchEnvironmentLimit" in e for e in collector_errors):
                raise BenchEnvironmentLimit("; ".join(collector_errors))
            raise RuntimeError("Collector failed: " + "; ".join(collector_errors))
        wait_for_drainage(pg, timeout_s=30)
        final_snap = bench.snapshot(pg, metrics_urls, out, "final")
        bench.validate_progress(initial_snap, final_snap)

        sample_rows = [r for r in all_rows if not r.get("warmup")]
        reconciliation = bench.reconcile(pg, sample_rows)
        write_timings = query_write_stage_timings(pg, sample_rows)
        sample_req_ids = [r["requestId"] for r in sample_rows if r.get("requestId")]
        handoffs = parse_handoff_logs(project, out, sample_req_ids, write_timings)

        restarts = {}
        for name in ("postgres", *topo["runtimes"]):
            info = bench.owned(f"{project}-{name}-1")
            restarts[name] = info.get("RestartCount", 0)

        observed = [r for r in sample_rows if r.get("status") == "OBSERVED"]
        duration_s = (sample_ended_ns - sample_began_ns) / 1e9 if sample_began_ns and sample_ended_ns else 0.0
        observed_rate = len(observed) / duration_s if duration_s > 0 else 0.0

        verdict = "PASS"
        if collector_errors:
            verdict = "ENVIRONMENT_LIMIT" if any("memory" in e.lower() for e in collector_errors) else "MEASUREMENT_INVALID"
        elif any(r.get("status") == "INVARIANT_VIOLATION" for r in all_rows):
            verdict = "PRODUCT_FAILURE"
        elif any(r.get("status") in ("CENSORED", "PROBE_ERROR") for r in all_rows):
            verdict = "PRODUCT_FAILURE"
        elif len(observed) != camp["sampleCount"]:
            verdict = "MEASUREMENT_INVALID"
        elif any(v > 0 for v in restarts.values()):
            verdict = "ENVIRONMENT_LIMIT"

        manifest.update(
            {
                "verdict": verdict,
                "safety": "PASS" if verdict == "PASS" else "FAIL",
                "finalSnapshot": final_snap,
                "reconciliationComplete": reconciliation["complete"],
                "restarts": restarts,
                "collectorErrors": collector_errors,
                "commands": {
                    "warmup": camp["warmupCount"],
                    "plannedSample": camp["sampleCount"],
                    "admittedSample": sum(1 for r in sample_rows if "requestId" in r),
                    "observedSample": len(observed),
                    "droppedOffer": sum(1 for r in sample_rows if r.get("status") == "DROPPED_OFFER"),
                    "censored": sum(1 for r in sample_rows if r.get("status") == "CENSORED"),
                    "invariantViolations": sum(1 for r in all_rows if r.get("status") == "INVARIANT_VIOLATION"),
                    "requestedRate": rate,
                    "observedRate": round(observed_rate, 3),
                    "sampleWindowSeconds": round(duration_s, 3),
                },
                "latenciesMs": {
                    "offerLagMs": stats_summary([r.get("offerLagMs") for r in observed]),
                    "stage1_admissionMs": stats_summary([r.get("admissionMs") for r in observed]),
                    "stage2_preDiscoveryWaitMs": "NOT_MEASURABLE (no created_at on v3_command; cross-JVM http-write->command-worker)",
                    "stage2_commandWorker": handoffs["commandWorker"],
                    "stage3_preDiscoveryWaitMs": "NOT_MEASURABLE (cross-JVM command-worker->read-worker)",
                    "stage3_projections": handoffs["projections"],
                    "stage4_resultE2eMs": stats_summary([r.get("resultE2eMs") for r in observed]),
                    "stage4_c03E2eMs": stats_summary([r.get("c03E2eMs") for r in observed]),
                    "stage4_balanceE2eMs": stats_summary([r.get("balanceE2eMs") for r in observed]),
                },
                "resourceSamplesCount": len(resource_samples),
            }
        )
        (out / "observations.json").write_text(json.dumps(all_rows, indent=2))
        (out / "write_timings.json").write_text(json.dumps(write_timings, indent=2))
        (out / "resources.json").write_text(json.dumps(resource_samples, indent=2))
    except BenchEnvironmentLimit as err:
        manifest["verdict"] = "ENVIRONMENT_LIMIT"
        manifest["error"] = f"{type(err).__name__}: {err}"
    except AssertionError as err:
        manifest["verdict"] = "PRODUCT_FAILURE"
        manifest["safety"] = "FAIL"
        manifest["error"] = f"{type(err).__name__}: {err}"
    except Exception as err:
        manifest["verdict"] = "MEASUREMENT_INVALID" if "collector" in str(err).lower() else "INCONCLUSIVE"
        manifest["error"] = f"{type(err).__name__}: {err}"
    finally:
        stop.set()
        if collector:
            collector.join(10)
        if issuer:
            issuer.terminate()
            try:
                issuer.wait(timeout=5)
            except subprocess.TimeoutExpired:
                issuer.kill()
        try:
            with (out / "runtime.log").open("w") as log:
                subprocess.run(compose_args + ["logs", "--no-color"], stdout=log, stderr=log, timeout=30)
            dc("down", "--volumes", "--remove-orphans")
            manifest["cleanup"] = "REMOVED_OWN_PROJECT"
        except Exception as err:
            manifest["cleanup"] = f"CLEANUP_ERROR: {err}"
        shutil.rmtree(work, ignore_errors=True)
        manifest["collectorErrors"] = collector_errors
        if all_rows and not (out / "observations.json").exists():
            (out / "observations.json").write_text(json.dumps(all_rows, indent=2))
        manifest["finishedAt"] = bench.utc()
        (out / "manifest.json").write_text(json.dumps(manifest, indent=2, allow_nan=False) + "\n")

    print(
        json.dumps(
            {
                "campaign": campaign_name,
                "verdict": manifest["verdict"],
                "safety": manifest["safety"],
                "cleanup": manifest.get("cleanup"),
            }
        )
    )
    return 0 if manifest["verdict"] == "PASS" else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--campaign", required=True, help="Campaign name from l7-4a-profiles.json")
    parser.add_argument("--mode", choices=("full", "prepare", "restore-run"), default="full", help="Execution mode")
    parser.add_argument("--snapshot-dir", type=Path, default=None, help="Snapshot directory for prepare/restore-run")
    parser.add_argument("--output", type=Path, required=True, help="New output directory for evidence")
    parser.add_argument("--java-image", required=True, help="Pinned local Java 21 image sha256")
    parser.add_argument("--postgres-image", required=True, help="Pinned local Postgres 17 image sha256")
    args = parser.parse_args()

    cfg = load_profiles_config()
    if args.campaign not in cfg["campaigns"]:
        raise ValueError(f"Unknown campaign {args.campaign}; choices: {list(cfg['campaigns'])}")
    return run_campaign(args, cfg, args.campaign, args.output.resolve())


if __name__ == "__main__":
    sys.exit(main())
