#!/usr/bin/env python3
"""Pocoma V3 — L7-C4 Phase 1 Diagnostic Runner (E2E + Internal Metrics).

Disposable 5-runtime Docker bench reusing run-l7-4a-baseline, spec09_bench,
smoke_test_5_runtimes, and c4_metrics without modifying any production code.
"""

import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from fractions import Fraction
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import shutil
import signal
import subprocess
import sys
import threading
import time
import uuid

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

import c4_metrics as c4m
import smoke_test_5_runtimes as oracle
import spec09_bench as bench

_base_spec = importlib.util.spec_from_file_location("run_l7_4a_baseline", SCRIPT_DIR / "run-l7-4a-baseline.py")
base = importlib.util.module_from_spec(_base_spec)
_base_spec.loader.exec_module(base)

_reg_spec = importlib.util.spec_from_file_location("run_l7_spec09_registration", SCRIPT_DIR / "run-l7-spec09-registration.py")
reg_mod = importlib.util.module_from_spec(_reg_spec)
_reg_spec.loader.exec_module(reg_mod)

ROOT = bench.ROOT
JAVA_IMAGE = "eclipse-temurin@sha256:66bb900643426ad01996d25bada7d56751913f9cec3b827fcb715d2ec9a0fbfc"
POSTGRES_IMAGE = "postgres@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7"
INVITATION_KEY = base64.b64encode(b"pocoma-c4-local-invitation-key-1").decode()


def load_cfg():
    return base.load_profiles_config()


def build_compose_services(cfg, work, ip, extra_props=None):
    topo = cfg["topology"]
    definition = {
        "services": {
            "postgres": {
                "image": POSTGRES_IMAGE,
                "labels": {bench.LABEL: "true"},
                "command": [
                    "postgres",
                    "-c", "shared_preload_libraries=pg_stat_statements",
                    "-c", "pg_stat_statements.track=top",
                    "-c", "log_lock_waits=on",
                    "-c", "deadlock_timeout=5ms",
                ],
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
    for name, rcfg in topo["runtimes"].items():
        mod = rcfg["module"]
        jar = ROOT / "app" / mod / "target" / f"pocoma-{mod}-0.0.1-SNAPSHOT-exec.jar"
        if not jar.is_file():
            raise ValueError(f"Package runtime first: {mod} ({jar} missing)")
        content = (base.PROPERTIES_DIR / f"{name}.properties").read_text()
        content = content.replace("172.28.0.2/32", ip + "/32")
        content += "\npocoma.v3.http.issuer=http://pocoma-l74a.invalid\n"
        content += "pocoma.v3.http.jwk-set-uri=http://host.docker.internal:28090/jwks.json\n"
        if name in ("http-read", "command-worker"):
            content += (
                "\npocoma.v3.invitation.current-key=local\n"
                "pocoma.v3.invitation.key-ids=local\n"
                f"pocoma.v3.invitation.keys.local={INVITATION_KEY}\n"
            )
        if extra_props and name in extra_props:
            content += "\n" + extra_props[name] + "\n"
        prop_file = work / f"{name}.properties"
        prop_file.write_text(content)
        mgmt = rcfg["managementPort"]
        ports = [f"127.0.0.1:{mgmt + 10000}:{mgmt}"]
        if "httpPort" in rcfg:
            hp = rcfg["httpPort"]
            ports.append(f"127.0.0.1:{hp + 19920}:{hp}")
        definition["services"][name] = {
            "image": JAVA_IMAGE,
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
    return definition


def init_pg_stat_statements(pg):
    c4m.pg_exec(pg, "pocoma", "CREATE EXTENSION IF NOT EXISTS pg_stat_statements;")
    c4m.pg_exec(pg, "pocoma", "SELECT pg_stat_statements_reset();")


def create_pots_and_seed(pg, profile_name, pot_count, seed_id, token, binding, out):
    return base.seed_profile_pots(pg, profile_name, pot_count, seed_id, token, binding, out)


def load_states_from_meta(meta, pot_count, token, binding):
    states = []
    for pmeta in meta["pots"][:pot_count]:
        pid = pmeta["potId"]
        code, current = oracle.request("GET", f"http://127.0.0.1:28002/api/v3/pots/{pid}", token, binding=binding)
        if code != 200:
            raise RuntimeError(f"Restored pot {pid} unreadable: HTTP {code}")
        st = oracle.initial_state(
            current,
            {
                "potId": pid,
                "shareholders": [s["id"] for s in current["shareholders"]],
                "expenses": pmeta["expenses"],
            },
        )
        states.append(st)
    return states


def run_open_rate(states, rate, count, warmup, producers, deadline_s=20.0, poll_s=0.025, max_lag_ms=100.0, token=None, binding=None):
    stop = threading.Event()
    locks = [threading.Lock() for _ in states]
    all_rows = []
    base_t = time.monotonic() + 0.2
    sample_began_ns = None
    with ThreadPoolExecutor(max_workers=producers) as pool:
        futures = []
        for i in range(warmup + count):
            sched = base_t + (i / rate)
            sleep_s = sched - time.monotonic()
            if sleep_s > 0:
                time.sleep(sleep_s)
            if i == warmup:
                sample_began_ns = time.monotonic_ns()
            ctx = base.AttemptContext(
                states[i % len(states)],
                token,
                binding,
                stop,
                locks[i % len(states)],
                deadline_s,
                poll_s,
                max_lag_ms,
                is_warmup=(i < warmup),
            )
            futures.append(pool.submit(base.execute_attempt, i, sched, ctx))
        for f in futures:
            all_rows.append(f.result())
    sample_ended_ns = time.monotonic_ns()
    return all_rows, sample_began_ns, sample_ended_ns


def summarize_rows(rows, sample_began_ns, sample_ended_ns):
    sample = [r for r in rows if not r.get("warmup")]
    obs = [r for r in sample if r.get("status") == "OBSERVED"]
    dur = (sample_ended_ns - sample_began_ns) / 1e9 if sample_began_ns and sample_ended_ns else 0.0
    return {
        "planned": len(sample),
        "admitted": sum(1 for r in sample if "requestId" in r),
        "observed": len(obs),
        "droppedOffer": sum(1 for r in sample if r.get("status") == "DROPPED_OFFER"),
        "censored": sum(1 for r in sample if r.get("status") == "CENSORED"),
        "invariantViolations": sum(1 for r in sample if r.get("status") == "INVARIANT_VIOLATION"),
        "windowSeconds": round(dur, 3),
        "observedRate": round(len(obs) / dur, 3) if dur > 0 else 0.0,
        "offerLagMs": base.stats_summary([r.get("offerLagMs") for r in obs]),
        "admissionMs": base.stats_summary([r.get("admissionMs") for r in obs]),
        "resultE2eMs": base.stats_summary([r.get("resultE2eMs") for r in obs]),
        "c03E2eMs": base.stats_summary([r.get("c03E2eMs") for r in obs]),
        "balanceE2eMs": base.stats_summary([r.get("balanceE2eMs") for r in obs]),
    }


def poll_until_200_safe(path, token, binding, timeout_s=30.0, poll_s=0.025):
    url = "http://127.0.0.1:28002/api/v3" + path
    t0 = time.monotonic_ns()
    deadline = t0 + timeout_s * 1e9
    while time.monotonic_ns() < deadline:
        code, data = oracle.request("GET", url, token, binding=binding)
        if code == 200:
            return data, time.monotonic_ns()
        if code not in (404, 503):
            raise RuntimeError(f"Unexpected HTTP {code} on {path}")
        time.sleep(poll_s)
    raise TimeoutError(f"Timeout polling {path}")


def run_registration_stream(count, rate, tokens_list, stop_event=None):
    rows = []
    base_t = time.monotonic() + 0.1
    for i in range(count):
        if stop_event and stop_event.is_set():
            break
        sched = base_t + (i / rate)
        sleep_s = sched - time.monotonic()
        if sleep_s > 0:
            time.sleep(sleep_s)
        tok = tokens_list[i % len(tokens_list)]
        key = str(uuid.uuid4())
        t0 = time.monotonic_ns()
        code, admitted = oracle.request(
            "POST", "http://127.0.0.1:28000/api/v3/identity/requests",
            tok, {"operation": "Registration"}, key=key,
        )
        t_adm = time.monotonic_ns()
        if code != 202:
            rows.append({"index": i, "status": f"HTTP_{code}"})
            continue
        req_id = admitted["requestId"]
        try:
            res, t_res = poll_until_200_safe(f"/identity/requests/{req_id}/result", tok, None, timeout_s=30.0)
            # Poll /me/binding then /me/pots?limit=1
            deadline = time.monotonic_ns() + 30e9
            binding_tok = None
            t_pots = None
            while time.monotonic_ns() < deadline:
                bcode, bdata = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", tok)
                if bcode == 200 and bdata.get("active") and bdata.get("bindingToken"):
                    binding_tok = bdata["bindingToken"]
                    pcode, pdata = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/pots?limit=1", tok, binding=binding_tok)
                    if pcode == 200:
                        t_pots = time.monotonic_ns()
                        break
                time.sleep(0.025)
            if not t_pots:
                rows.append({"index": i, "status": "CENSORED", "requestId": req_id})
            else:
                rows.append({
                    "index": i,
                    "status": "OBSERVED",
                    "requestId": req_id,
                    "admissionMs": round((t_adm - t0) / 1e6, 3),
                    "resultE2eMs": round((t_res - t0) / 1e6, 3),
                    "usableBindingE2eMs": round((t_pots - t0) / 1e6, 3),
                })
        except Exception as exc:
            rows.append({"index": i, "status": "ERROR", "error": str(exc), "requestId": req_id})
    return rows


def run_delete_scenario(pg, states, token, binding):
    rows = []
    for i, st in enumerate(states[:10]):
        # First 1 CreateExpense, then 1 DeletePot
        ctx_create = base.AttemptContext(st, token, binding, threading.Event(), threading.Lock(), 20.0, 0.025, 100.0, False)
        r_create = base.execute_attempt(i, time.monotonic(), ctx_create)
        rows.append({"phase": "create", **r_create})
        if r_create.get("status") != "OBSERVED":
            continue
        pid = st["potId"]
        obs_ver = st["version"]
        key = str(uuid.uuid4())
        t0 = time.monotonic_ns()
        code, adm = oracle.request(
            "POST", "http://127.0.0.1:28000/api/v3/commands", token,
            {"operation": "DeletePot", "potId": pid, "observedVersion": obs_ver, "bindingToken": binding, "payload": {}},
            key=key,
        )
        t_adm = time.monotonic_ns()
        if code != 202:
            rows.append({"phase": "delete", "status": f"HTTP_{code}", "potId": pid})
            continue
        req_id = adm["requestId"]
        res, t_res = poll_until_200_safe(f"/commands/{req_id}/result", token, None, timeout_s=20.0)
        d_ver = res["resultingPotVersion"]
        # Internal READ landmark: CurrentPot(P) -> PotVersion(P, D).deleted = true
        t_del = None
        deadline = time.monotonic_ns() + 20e9
        while time.monotonic_ns() < deadline:
            chk = c4m.pg_sql(
                pg, "pocoma_read",
                f"""SELECT jsonb_build_object(
                    'cpVer', (SELECT pot_version FROM v3_read.read_current_pot WHERE pot_id='{pid}'::uuid),
                    'deleted', (SELECT deleted FROM v3_read.read_pot_version WHERE pot_id='{pid}'::uuid AND pot_version={d_ver}),
                    'balCount', (SELECT count(*) FROM v3_read.read_pot_balance WHERE pot_id='{pid}'::uuid AND pot_version={d_ver}),
                    'availHist', (SELECT count(*) FROM v3_read.read_availability_event WHERE family='POT_VERSION_AVAILABLE' AND projection_key='{pid}'::uuid AND logical_version={d_ver}),
                    'availCur', (SELECT logical_version FROM v3_read.read_current_availability WHERE family='CURRENT_POT_CHANGED' AND projection_key='{pid}'::uuid)
                )""",
            )
            if chk.get("cpVer") == d_ver and chk.get("deleted") is True and chk.get("availHist") == 1 and chk.get("availCur") == d_ver:
                t_del = time.monotonic_ns()
                if chk.get("balCount") != 0:
                    raise AssertionError(f"Unexpected balance at terminal version D for {pid}: {chk}")
                break
            time.sleep(0.025)
        http_code, _ = oracle.request("GET", f"http://127.0.0.1:28002/api/v3/pots/{pid}", token, binding=binding)
        rows.append({
            "phase": "delete",
            "status": "OBSERVED" if t_del else "CENSORED",
            "potId": pid,
            "requestId": req_id,
            "deletedVersion": d_ver,
            "httpStatusAfterDelete": http_code,
            "admissionMs": round((t_adm - t0) / 1e6, 3),
            "resultE2eMs": round((t_res - t0) / 1e6, 3),
            "deleteInternalC03Ms": round((t_del - t0) / 1e6, 3) if t_del else None,
        })
    return rows


def run_contention_1pot(pg, state, producers=6, per_producer=20):
    pid = state["potId"]
    token = os.environ["POCOMA_TOKEN"]
    binding = os.environ["POCOMA_BINDING_TOKEN"]
    admitted_ids = []
    lock = threading.Lock()

    def worker(wid):
        for idx in range(per_producer):
            # Read current version (or use stale/latest observed) to provoke realistic CAS races
            code, cur = oracle.request("GET", f"http://127.0.0.1:28002/api/v3/pots/{pid}", token, binding=binding)
            obs_v = cur.get("potVersion", state["version"]) if code == 200 else state["version"]
            payer = state["shareholders"][(wid + idx) % len(state["shareholders"])]
            payload = {
                "operation": "CreateExpense",
                "potId": pid,
                "observedVersion": obs_v,
                "bindingToken": binding,
                "payload": {
                    "id": str(uuid.uuid4()),
                    "label": f"CONT-{wid}-{idx}",
                    "date": "2026-11-21",
                    "amount": {"numerator": "1", "denominator": "2"},
                    "payerId": payer,
                    "participants": [{"id": s, "weight": None} for s in state["shareholders"]],
                },
            }
            sc, adm = oracle.request("POST", "http://127.0.0.1:28000/api/v3/commands", token, payload, key=str(uuid.uuid4()))
            if sc == 202 and adm.get("requestId"):
                with lock:
                    admitted_ids.append(adm["requestId"])
            time.sleep(0.02)

    with ThreadPoolExecutor(max_workers=producers) as pool:
        futs = [pool.submit(worker, w) for w in range(producers)]
        for f in futs:
            f.result()

    drainage_err = None
    try:
        base.wait_for_drainage(pg, timeout_s=60)
    except TimeoutError as exc:
        drainage_err = {
            "error": str(exc),
            "writePending": c4m.pg_sql(pg, "pocoma_write", "SELECT count(*) FROM v3_write.v3_command WHERE status='PENDING'"),
            "writeSlots": c4m.pg_sql(pg, "pocoma_write", "SELECT coalesce(jsonb_agg(jsonb_build_object('c', consumption_id, 'n', cnt)), '[]'::jsonb) FROM (SELECT consumption_id, count(*) cnt FROM v3_write.consumption_slot GROUP BY 1) x"),
            "readSlots": c4m.pg_sql(pg, "pocoma_read", "SELECT coalesce(jsonb_agg(jsonb_build_object('c', consumption_id, 'n', cnt)), '[]'::jsonb) FROM (SELECT consumption_id, count(*) cnt FROM v3_read.consumption_slot GROUP BY 1) x"),
        }
    # Reconcile outcomes in WRITE and READ
    vals = ",".join(f"('{uuid.UUID(r)}'::uuid)" for r in admitted_ids)
    w_res = c4m.pg_sql(
        pg, "pocoma_write",
        f"""WITH ids(request_id) AS (VALUES {vals})
        SELECT jsonb_build_object(
          'admitted', (SELECT count(*) FROM ids),
          'terminal', (SELECT count(*) FROM ids JOIN v3_write.v3_command c USING(request_id) WHERE c.status='TERMINAL'),
          'changed', (SELECT count(*) FROM ids JOIN v3_write.v3_command_result r USING(request_id) WHERE r.outcome='CHANGED'),
          'rejected', (SELECT count(*) FROM ids JOIN v3_write.v3_command_result r USING(request_id) WHERE r.outcome='REJECTED'),
          'slots', (SELECT count(*) FROM v3_write.consumption_slot)
        )""",
    )
    r_res = c4m.pg_sql(
        pg, "pocoma_read",
        f"""WITH ids(request_id) AS (VALUES {vals})
        SELECT jsonb_build_object(
          'results', (SELECT count(*) FROM ids JOIN v3_read.read_command_result r USING(request_id)),
          'availValid', v3_read.read_availability_valid(),
          'slots', (SELECT count(*) FROM v3_read.consumption_slot)
        )""",
    )
    return {"admittedCount": len(admitted_ids), "write": w_res, "read": r_res, "drainageTimeout": drainage_err}


def run_backlog_10k(pg, dc, states, token, binding, fresh_tokens, out):
    # Create 10,000 CreatePot commands (observedVersion=0, all CHANGED) -> 10,000 POT_EVENTs + 10,000 COMMAND_COMPLETION_EVENTs
    # Note: we create 10,000 requests targeting our 10-50 medium pots? Wait: CreateExpense with observedVersion requires
    # either 10,000 pots OR pipelined/CAS-rejected commands. Using 100 medium pots with 100 commands each at observedVersion=V
    # would have 99 CAS rejections per pot; still exercises 10,000 Command executions + 10,000 CommandResults!
    # Even better: let's seed 100 medium pots? Seeding 100 medium pots (202 cmds each = 20,200 cmds) takes ~150s.
    # Let's check how fast seeding 100 medium pots is, or use 50 medium pots with 200 commands each, OR 100 medium pots with 100 commands each.
    # Actually, let's check: if 1 pot has 100 commands at observedVersion=V, 1 is CHANGED and 99 are REJECTED (so only 100 POT_EVENTs, not 10,000!).
    # For a true 10,000-command backlog where ALL 10,000 apply and produce 10,000 POT_EVENTs (40,000 READ events), we want distinct pots OR sequential versions!
    # Wait: if we stop workers, we can admit 10,000 commands across 100 medium pots? No, observedVersion is checked against current pot version at execution time.
    # Unless we seed 10,000 pots (too slow on Mac) OR we admit 10,000 commands across 50 pots where 50 are CHANGED and 9,950 are REJECTED?
    # Wait: let's check what happens if we admit 10,000 CreatePot commands! CreatePot has observedVersion=0 and ALWAYS succeeds (CHANGED), creating 10,000 Pots + 10,000 POT_EVENTs + 10,000 COMMAND_COMPLETION_EVENTs = 40,000 READ consumptions!
    # Let's verify CreatePot! CreatePot produces PotVersion, PotBalance, PotVisibility, CommandResult for ALL 10,000 commands!
    print("[C4-BACKLOG-10K] Stopping workers and admitting 10,000 CreatePot commands (all CHANGED)...")
    dc("stop", "command-worker", "results-worker", "business-read-worker")
    admitted_ids = []
    t_adm_start = time.monotonic()

    def admit_create_pot(idx):
        pot_id = str(uuid.uuid4())
        payload = {
            "operation": "CreatePot",
            "potId": pot_id,
            "observedVersion": 0,
            "bindingToken": binding,
            "payload": {"label": f"Backlog-Pot-{idx}"},
        }
        sc, adm = oracle.request("POST", "http://127.0.0.1:28000/api/v3/commands", token, payload, key=str(uuid.uuid4()))
        if sc != 202 or not adm.get("requestId"):
            raise RuntimeError(f"Admission failed at {idx}: HTTP {sc}")
        return adm["requestId"]

    with ThreadPoolExecutor(max_workers=8) as pool:
        admitted_ids = list(pool.map(admit_create_pot, range(10_000)))
    adm_elapsed = round(time.monotonic() - t_adm_start, 2)

    w_before = c4m.pg_sql(pg, "pocoma_write", "SELECT jsonb_build_object('pending', (SELECT count(*) FROM v3_write.v3_command WHERE status='PENDING'), 'slots', (SELECT count(*) FROM v3_write.consumption_slot))")
    print(f"[C4-BACKLOG-10K] Admitted {len(admitted_ids)} CreatePot in {adm_elapsed}s. WRITE={w_before}. Starting workers + concurrent 1 cmd/s + 0.2 Reg/s...")

    # Start workers and concurrent 1 cmd/s on existing medium pots + 0.2 Reg/s
    t_drain_start = time.monotonic()
    dc("start", "command-worker", "results-worker", "business-read-worker")
    # Wait for actuators
    for port in (28083, 28084, 28085):
        base.wait_for(lambda u=f"http://127.0.0.1:{port}": bool(bench.fetch_metrics(u)), 60)

    stop_conc = threading.Event()
    timeline = []

    def monitor_backlog():
        while not stop_conc.is_set():
            try:
                w = c4m.pg_sql(pg, "pocoma_write", "SELECT jsonb_build_object('pending', (SELECT count(*) FROM v3_write.v3_command WHERE status='PENDING'), 'slots', (SELECT count(*) FROM v3_write.consumption_slot))")
                r = c4m.pg_sql(pg, "pocoma_read", "SELECT jsonb_build_object('slots', (SELECT count(*) FROM v3_read.consumption_slot), 'cp', (SELECT coalesce(jsonb_object_agg(family, min_pos), '{}'::jsonb) FROM (SELECT crc.family, min(dc.position) AS min_pos FROM v3_read.discovery_checkpoint dc JOIN v3_read.consumption_routing_contract crc USING(consumption_id) GROUP BY crc.family) x))")
                timeline.append({"t": round(time.monotonic() - t_drain_start, 2), "write": w, "read": r})
                if w["pending"] == 0 and w["slots"] == 0 and r["slots"] == 0:
                    # Check checkpoints caught up to write counters
                    wc = c4m.pg_sql(pg, "pocoma_write", "SELECT jsonb_object_agg(family, position) FROM v3_write.v3_event_counter")
                    cp_ok = r["cp"].get("POT_EVENT", 0) >= wc.get("POT", 0) and r["cp"].get("COMMAND_COMPLETION_EVENT", 0) >= wc.get("COMMAND_COMPLETION", 0)
                    if cp_ok:
                        break
            except Exception:
                pass
            time.sleep(2.0)

    mon_thread = threading.Thread(target=monitor_backlog, daemon=True)
    mon_thread.start()

    # Concurrent 1 cmd/s on states for 60s (or until drained) + 12 Registrations at 0.2/s
    with ThreadPoolExecutor(max_workers=3) as pool:
        f_med = pool.submit(run_open_rate, states, 1.0, 60, 0, 2, 120.0, 0.05, 200.0, token, binding)
        f_reg = pool.submit(run_registration_stream, 12, 0.2, fresh_tokens, None)
        mon_thread.join(timeout=420)
        stop_conc.set()
        med_rows, m_began, m_ended = f_med.result()
        reg_rows = f_reg.result()

    base.wait_for_drainage(pg, timeout_s=120)
    drain_seconds = round(time.monotonic() - t_drain_start, 2)
    return {
        "admissionSeconds": adm_elapsed,
        "drainSeconds": drain_seconds,
        "timeline": timeline,
        "concurrentCommands": summarize_rows(med_rows, m_began, m_ended),
        "concurrentRegistrations": {
            "count": len(reg_rows),
            "observed": sum(1 for r in reg_rows if r.get("status") == "OBSERVED"),
            "usableBindingE2eMs": base.stats_summary([r.get("usableBindingE2eMs") for r in reg_rows if r.get("status") == "OBSERVED"]),
        },
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scenario", required=True, choices=[
        "c1-idle", "c1-med-1", "c1-large-1", "c1-id-1", "c1-delete-1",
        "c2-med-10", "c2-ramp", "c2-med-50", "c2-contention-1pot",
        "c3-int-d5-vs-med", "c3-int-pot-vs-id", "c3-int-inv-vs-pot",
        "c4-backlog-10k", "c4-backlog-read-only",
    ])
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--snapshot-dir", type=Path, default=None)
    parser.add_argument("--save-v5-snapshot", type=Path, default=None)
    parser.add_argument("--pot-count", type=int, default=None)
    args = parser.parse_args()

    cfg = load_cfg()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    work = Path(f"/private/tmp/pocoma-c4-work-{uuid.uuid4().hex[:8]}")
    work.mkdir(mode=0o700, parents=True)
    project = f"pocoma-c4-{uuid.uuid4().hex[:8]}"
    pg = f"{project}-postgres-1"
    compose_path = work / "compose.json"
    compose_args = ["docker", "compose", "-p", project, "-f", str(compose_path)]
    issuer = None

    def dc(*argv, timeout=180):
        with (out / "lifecycle.log").open("a") as log:
            subprocess.run(compose_args + list(argv), check=True, stdout=log, stderr=log, timeout=timeout)

    pre_prov = bench.provenance()
    receipt = {
        "scenario": args.scenario,
        "startedAt": bench.utc(),
        "project": project,
        "git": {"sha": pre_prov["sha"], "dirty": pre_prov["dirty"]},
        "topology": cfg["topology"],
        "verdict": "INCONCLUSIVE",
    }

    try:
        receipt["docker"] = base.preflight_guardrails(work, out, cfg["guardrails"])
        # Start postgres
        pg_only = {
            "services": {
                "postgres": build_compose_services(cfg, work, "127.0.0.1")["services"]["postgres"]
            }
        }
        compose_path.write_text(json.dumps(pg_only))
        dc("up", "-d", "postgres")
        base.wait_for(lambda: bench.command(["docker", "exec", pg, "pg_isready", "-h", "127.0.0.1", "-U", "pocoma"]) != "")
        ip = next(iter(bench.owned(pg)["NetworkSettings"]["Networks"].values()))["IPAddress"]

        rebuilt_read = False
        snap_meta = None
        if args.snapshot_dir:
            snap_meta, dump_path = base.verify_snapshot_metadata(args.snapshot_dir, "large" if ("large" in args.scenario or "d5" in args.scenario) else "medium", 1)
            restore_elapsed, rebuilt_read = base.restore_snapshot_into_pg(pg, dump_path, out)
            receipt["restore"] = {"snapshotDir": str(args.snapshot_dir), "restoreElapsedSeconds": restore_elapsed, "rebuiltRead": rebuilt_read}
            main_subject = snap_meta["tokenSubject"]
        else:
            with (out / "bootstrap.log").open("w") as log:
                subprocess.run([sys.executable, "-B", str(ROOT / "scripts/operations/setup-v3-database.py"), "--postgres-container", pg], check=True, stdout=log, stderr=log, timeout=100)
            main_subject = base.SNAPSHOT_SUBJECT

        init_pg_stat_statements(pg)
        fresh_subjects = [f"c4-fresh-{uuid.uuid4().hex[:6]}-{i}" for i in range(80)]
        (work / "subjects.json").write_text(json.dumps([main_subject, *fresh_subjects]))
        issuer_log = (out / "issuer.log").open("w")
        issuer = subprocess.Popen(
            [
                "node", str(ROOT / "scripts/performance/spec09-test-issuer.mjs"),
                "--host", "0.0.0.0", "--port", "28090",
                "--issuer", "http://pocoma-l74a.invalid", "--audience", "pocoma-api",
                "--subjects", str(work / "subjects.json"), "--token-file", str(work / "tokens.json"),
                "--ttl-seconds", "3600",
            ],
            stdout=issuer_log, stderr=issuer_log,
        )
        base.wait_for(lambda: (work / "tokens.json").is_file(), 10)
        tokens_map = json.loads((work / "tokens.json").read_text())
        token = tokens_map[main_subject]
        fresh_tokens = [tokens_map[s] for s in fresh_subjects]

        definition = build_compose_services(cfg, work, ip)
        compose_path.write_text(json.dumps(definition))
        dc("up", "-d", "--no-recreate", *cfg["topology"]["runtimes"].keys())

        metrics_urls = {name: f"http://127.0.0.1:{rcfg['managementPort'] + 10000}" for name, rcfg in cfg["topology"]["runtimes"].items()}
        for name, url in metrics_urls.items():
            try:
                base.wait_for(lambda u=url: bool(bench.fetch_metrics(u)), 150)
            except TimeoutError:
                raise TimeoutError(f"Readiness deadline exceeded for {name} ({url})")

        if args.snapshot_dir:
            if rebuilt_read:
                rebuild_s = base.await_read_rebuild_from_write(pg, snap_meta["writeCounters"], timeout_s=900)
                receipt["restore"]["rebuildSeconds"] = rebuild_s
                if args.save_v5_snapshot:
                    base.capture_snapshot(pg, dc, cfg["topology"], {"profile": snap_meta["profile"], "seedId": snap_meta["seedId"]}, snap_meta["profileSpec"], load_states_from_meta(snap_meta, len(snap_meta["pots"]), token, oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", token)[1]["bindingToken"]), snap_meta["seedElapsedSeconds"], args.save_v5_snapshot, bench.snapshot(pg, metrics_urls, out, "pre-snap"), pre_prov["sha"])
                    dc("up", "-d", *cfg["topology"]["runtimes"].keys())
                    for url in metrics_urls.values():
                        base.wait_for(lambda u=url: bool(bench.fetch_metrics(u)), 90)
            _, b_resp = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", token)
            binding = b_resp["bindingToken"]
            os.environ.update(POCOMA_TOKEN=token, POCOMA_BINDING_TOKEN=binding)
            p_cnt = args.pot_count or len(snap_meta["pots"])
            states = load_states_from_meta(snap_meta, p_cnt, token, binding)
        else:
            reg_res = reg_mod.observe_one("http://127.0.0.1:28000", "http://127.0.0.1:28002", token, str(uuid.uuid4()))
            if reg_res["outcome"] != "AUTHORIZED":
                raise RuntimeError(f"Initial registration failed: {reg_res}")
            _, b_resp = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", token)
            binding = b_resp["bindingToken"]
            os.environ.update(POCOMA_TOKEN=token, POCOMA_BINDING_TOKEN=binding)
            need_pots = args.pot_count or (
                50 if args.scenario in ("c2-med-10", "c2-ramp", "c2-med-50", "c3-int-pot-vs-id")
                else 10 if args.scenario in ("c1-med-1", "c1-delete-1", "c3-int-inv-vs-pot", "c4-backlog-10k", "c4-backlog-read-only")
                else 2 if args.scenario in ("c1-idle", "c1-id-1", "c2-contention-1pot")
                else 5
            )
            states, seed_s = create_pots_and_seed(pg, "medium", need_pots, f"c4-{args.scenario}", token, binding, out)
            base.wait_for_drainage(pg, timeout_s=300)
            receipt["seed"] = {"potCount": len(states), "seedSeconds": seed_s}
            if args.save_v5_snapshot:
                init_snap = bench.snapshot(pg, metrics_urls, out, "pre-snap")
                base.capture_snapshot(pg, dc, cfg["topology"], {"profile": "medium", "seedId": f"c4-{args.scenario}"}, cfg["profiles"]["medium"], states, seed_s, args.save_v5_snapshot, init_snap, pre_prov["sha"])
                dc("up", "-d", *cfg["topology"]["runtimes"].keys())
                for url in metrics_urls.values():
                    base.wait_for(lambda u=url: bool(bench.fetch_metrics(u)), 90)

        # Reset pg_stat_statements right before measured window
        try:
            c4m.pg_exec(pg, "pocoma", "SELECT pg_stat_statements_reset();")
        except Exception:
            pass
        pre_proms = {r: c4m.scrape_prom(u) for r, u in metrics_urls.items()}
        pre_cgroup = c4m.capture_cluster_cgroup(project)
        pre_pg = c4m.pg_capture_window(pg)

        # Execute scenario
        sc = args.scenario
        if sc == "c1-idle":
            time.sleep(60.0)
            rows, b_ns, e_ns = run_open_rate(states, 0.33, 10, 0, 2, 20.0, 0.025, 100.0, token, binding)
            receipt["results"] = summarize_rows(rows, b_ns, e_ns)
        elif sc == "c1-med-1":
            rows, b_ns, e_ns = run_open_rate(states, 1.0, 60, 10, 4, 15.0, 0.025, 100.0, token, binding)
            receipt["results"] = summarize_rows(rows, b_ns, e_ns)
        elif sc == "c1-large-1":
            rows, b_ns, e_ns = run_open_rate(states, 1.0, 30, 5, 2, 30.0, 0.025, 100.0, token, binding)
            receipt["results"] = summarize_rows(rows, b_ns, e_ns)
        elif sc == "c1-id-1":
            reg_rows = run_registration_stream(45, 1.0, fresh_tokens[:45])
            obs = [r for r in reg_rows if r.get("status") == "OBSERVED"]
            receipt["results"] = {
                "planned": 45, "observed": len(obs),
                "admissionMs": base.stats_summary([r["admissionMs"] for r in obs]),
                "resultE2eMs": base.stats_summary([r["resultE2eMs"] for r in obs]),
                "usableBindingE2eMs": base.stats_summary([r["usableBindingE2eMs"] for r in obs]),
            }
        elif sc == "c1-delete-1":
            del_rows = run_delete_scenario(pg, states, token, binding)
            d_obs = [r for r in del_rows if r.get("phase") == "delete" and r.get("status") == "OBSERVED"]
            receipt["results"] = {
                "deletedCount": len(d_obs),
                "http404Count": sum(1 for r in d_obs if r.get("httpStatusAfterDelete") == 404),
                "resultE2eMs": base.stats_summary([r["resultE2eMs"] for r in d_obs]),
                "deleteInternalC03Ms": base.stats_summary([r["deleteInternalC03Ms"] for r in d_obs]),
            }
        elif sc == "c2-med-10":
            rows, b_ns, e_ns = run_open_rate(states, 10.0, 200, 30, 12, 20.0, 0.025, 100.0, token, binding)
            receipt["results"] = summarize_rows(rows, b_ns, e_ns)
        elif sc == "c2-ramp":
            ramp_res = []
            for rate in (1.0, 5.0, 10.0, 20.0, 25.0, 35.0, 50.0):
                cnt = int(rate * 15)
                rows, b_ns, e_ns = run_open_rate(states, rate, cnt, 0, min(32, max(4, int(rate))), 30.0, 0.025, 150.0, token, binding)
                base.wait_for_drainage(pg, timeout_s=60)
                ramp_res.append({"targetRate": rate, **summarize_rows(rows, b_ns, e_ns)})
            receipt["results"] = {"steps": ramp_res}
        elif sc == "c2-med-50":
            rows, b_ns, e_ns = run_open_rate(states, 50.0, 500, 50, 32, 30.0, 0.025, 200.0, token, binding)
            t_drain = time.monotonic()
            base.wait_for_drainage(pg, timeout_s=120)
            receipt["results"] = {**summarize_rows(rows, b_ns, e_ns), "postBurstDrainSeconds": round(time.monotonic() - t_drain, 2)}
        elif sc == "c2-contention-1pot":
            receipt["results"] = run_contention_1pot(pg, states[0], producers=6, per_producer=20)
        elif sc == "c3-int-d5-vs-med":
            med_states, _ = create_pots_and_seed(pg, "medium", 2, "c4-d5-med-probe", token, binding, out)
            base.wait_for_drainage(pg, timeout_s=120)
            # Re-reset pg_stat_statements and baselines after creating the light probe pots
            try:
                c4m.pg_exec(pg, "pocoma", "SELECT pg_stat_statements_reset();")
            except Exception:
                pass
            pre_proms = {r: c4m.scrape_prom(u) for r, u in metrics_urls.items()}
            pre_cgroup = c4m.capture_cluster_cgroup(project)
            pre_pg = c4m.pg_capture_window(pg)
            stop_bg = threading.Event()
            with ThreadPoolExecutor(max_workers=3) as pool:
                f_heavy = pool.submit(run_open_rate, [states[0]], 3.0, 45, 0, 4, 45.0, 0.0, 300.0, token, binding)
                time.sleep(1.0)
                f_med = pool.submit(run_open_rate, med_states, 1.0, 20, 0, 2, 20.0, 0.025, 100.0, token, binding)
                f_id = pool.submit(run_registration_stream, 15, 0.5, fresh_tokens[:15], stop_bg)
                med_rows, mb_ns, me_ns = f_med.result()
                id_rows = f_id.result()
                heavy_rows, hb_ns, he_ns = f_heavy.result()
            obs_id = [r for r in id_rows if r.get("status") == "OBSERVED"]
            receipt["results"] = {
                "heavyLargePot": summarize_rows(heavy_rows, hb_ns, he_ns),
                "mediumProbe": summarize_rows(med_rows, mb_ns, me_ns),
                "identityProbe": {
                    "planned": 15, "observed": len(obs_id),
                    "resultE2eMs": base.stats_summary([r["resultE2eMs"] for r in obs_id]),
                    "usableBindingE2eMs": base.stats_summary([r["usableBindingE2eMs"] for r in obs_id]),
                },
            }
        elif sc == "c3-int-pot-vs-id":
            stop_bg = threading.Event()
            with ThreadPoolExecutor(max_workers=2) as pool:
                f_bg = pool.submit(run_open_rate, states, 20.0, 500, 20, 20, 30.0, 0.025, 150.0, token, binding)
                time.sleep(2.0)
                f_id = pool.submit(run_registration_stream, 20, 1.0, fresh_tokens[:20], stop_bg)
                id_rows = f_id.result()
                bg_rows, b_ns, e_ns = f_bg.result()
            obs = [r for r in id_rows if r.get("status") == "OBSERVED"]
            receipt["results"] = {
                "potBackground": summarize_rows(bg_rows, b_ns, e_ns),
                "identityProbe": {
                    "planned": 20, "observed": len(obs),
                    "resultE2eMs": base.stats_summary([r["resultE2eMs"] for r in obs]),
                    "usableBindingE2eMs": base.stats_summary([r["usableBindingE2eMs"] for r in obs]),
                },
            }
        elif sc == "c3-int-inv-vs-pot":
            inv_rows = []
            def inv_stream():
                for i in range(150):
                    st = states[i % len(states)]
                    t0 = time.monotonic_ns()
                    code, adm = oracle.request(
                        "POST", "http://127.0.0.1:28000/api/v3/commands", token,
                        {"operation": "CreateInvitation", "potId": st["potId"], "observedVersion": 0, "bindingToken": binding, "payload": {"expiresAt": "2026-12-31T23:59:59Z"}},
                        key=str(uuid.uuid4()),
                    )
                    inv_rows.append({"i": i, "code": code, "ms": round((time.monotonic_ns() - t0) / 1e6, 3)})
                    time.sleep(0.1)
            with ThreadPoolExecutor(max_workers=2) as pool:
                f_inv = pool.submit(inv_stream)
                f_pot = pool.submit(run_open_rate, states[:5], 5.0, 75, 10, 5, 20.0, 0.025, 100.0, token, binding)
                f_inv.result()
                pot_rows, b_ns, e_ns = f_pot.result()
            receipt["results"] = {
                "potProbe": summarize_rows(pot_rows, b_ns, e_ns),
                "invitationsSent": len(inv_rows),
                "invitationsAdmitted": sum(1 for r in inv_rows if r["code"] == 202),
            }
        elif sc == "c4-backlog-10k":
            receipt["results"] = run_backlog_10k(pg, dc, states, token, binding, fresh_tokens, out)
        elif sc == "c4-backlog-read-only":
            dc("stop", "business-read-worker")
            rows_bg, _, _ = run_open_rate(states[:8], 20.0, 300, 0, 10, 30.0, 0.05, 150.0, token, binding)
            # Wait for WRITE and results-worker to finish
            base.wait_for(lambda: c4m.pg_sql(pg, "pocoma_write", "SELECT count(*) FROM v3_write.v3_command WHERE status='PENDING'") == 0, 60)
            t_rec = time.monotonic()
            dc("start", "business-read-worker")
            base.wait_for(lambda: bool(bench.fetch_metrics("http://127.0.0.1:28085")), 60)
            rows_conc, b_ns, e_ns = run_open_rate(states[8:], 1.0, 30, 0, 2, 60.0, 0.025, 100.0, token, binding)
            base.wait_for_drainage(pg, timeout_s=120)
            receipt["results"] = {
                "backlogCommands": 300,
                "readRecoverySeconds": round(time.monotonic() - t_rec, 2),
                "concurrentCommands": summarize_rows(rows_conc, b_ns, e_ns),
            }

        if not (sc == "c2-contention-1pot" and receipt.get("results", {}).get("drainageTimeout")):
            base.wait_for_drainage(pg, timeout_s=60)
        post_pg = c4m.pg_capture_window(pg)
        post_cgroup = c4m.capture_cluster_cgroup(project)
        post_proms = {r: c4m.scrape_prom(u) for r, u in metrics_urls.items()}

        receipt["cgroupDelta"] = c4m.diff_cluster_cgroup(pre_cgroup, post_cgroup)
        receipt["micrometerDelta"] = c4m.extract_runtime_deltas(pre_proms, post_proms)
        receipt["pgStatementsRead"] = c4m.pg_top_statements(pg, "pocoma_read", 15)
        receipt["pgStatementsWrite"] = c4m.pg_top_statements(pg, "pocoma_write", 15)
        receipt["pgWalBytes"] = {
            db: int(c4m.pg_sql(pg, db, f"SELECT pg_wal_lsn_diff('{post_pg[db]['lsn']}'::pg_lsn, '{pre_pg[db]['lsn']}'::pg_lsn)::bigint"))
            for db in ("pocoma_write", "pocoma_read")
        }
        receipt["pgTxDelta"] = {
            db: {
                "xact_commit": post_pg[db]["db"]["xact_commit"] - pre_pg[db]["db"]["xact_commit"],
                "xact_rollback": post_pg[db]["db"]["xact_rollback"] - pre_pg[db]["db"]["xact_rollback"],
                "deadlocks": post_pg[db]["db"]["deadlocks"] - pre_pg[db]["db"]["deadlocks"],
            }
            for db in ("pocoma_write", "pocoma_read")
        }
        receipt["availabilityValid"] = base.read_availability_snapshot(pg)["valid"]
        receipt["verdict"] = "PASS" if receipt["availabilityValid"] else "FAIL"
    except Exception as exc:
        receipt["verdict"] = "FAIL"
        receipt["error"] = f"{type(exc).__name__}: {exc}"
        try:
            with (out / "containers.log").open("w") as clog:
                subprocess.run(compose_args + ["logs", "--no-color", "--tail=200"], stdout=clog, stderr=clog, timeout=20)
        except Exception:
            pass
    finally:
        if issuer:
            issuer.terminate()
            try:
                issuer.wait(timeout=5)
            except subprocess.TimeoutExpired:
                issuer.kill()
        try:
            dc("down", "--volumes", "--remove-orphans")
            receipt["cleanup"] = "REMOVED_OWN_PROJECT"
        except Exception as err:
            receipt["cleanup"] = f"CLEANUP_ERROR: {err}"
        shutil.rmtree(work, ignore_errors=True)
        receipt["finishedAt"] = bench.utc()
        (out / "receipt.json").write_text(json.dumps(receipt, indent=2, allow_nan=False) + "\n")

    print(json.dumps({"scenario": args.scenario, "verdict": receipt["verdict"], "cleanup": receipt.get("cleanup")}))
    return 0 if receipt["verdict"] == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
