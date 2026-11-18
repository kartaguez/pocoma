#!/usr/bin/env python3
"""Pocoma V3 — Distributed Metrology & A/B Sequential Burnable Discovery Qualification.

Executes comparative A/B qualification on the 5-runtime topology across database
population profiles (H1..H5) and workload rates (1, 10, 50 cmd/s).

Rules enforced:
- Never subtract System.nanoTime() across distinct JVMs.
- Use intra-JVM monotonic durations for T0a->T1, T1->T2, and T3->T4.
- Use single-host EpochMs for inter-JVM handoffs (T0->T0a, T0->T1, T2->T3, T0->T4).
- Never sum percentiles; compute mean/p50/p90/p95/p99/max per interval over the correlated per-command series.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import contextmanager
import csv
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from typing import Any, Dict, List, Optional
import urllib.error
import urllib.request
import uuid

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts" / "performance"))
from v3_token import OidcTokenManager
import v3_populated_db_generator as db_gen

WRITE_BASE_URL = os.environ.get("POCOMA_WRITE_URL", "http://127.0.0.1:8080")
READ_BASE_URL = os.environ.get("POCOMA_READ_URL", "http://127.0.0.1:8082")

MANAGEMENT_PORTS = {"http-write": 18080, "http-read": 18082,
                    "command-worker": 18083, "results-worker": 18084, "business-read-worker": 18085}
RUNTIME_CONTAINERS = [db_gen.RUNTIME_PREFIX + "pocoma-" + name for name in MANAGEMENT_PORTS]
PROMETHEUS_ENDPOINTS = {
    name: f"http://127.0.0.1:{port + db_gen.MANAGEMENT_PORT_OFFSET}/actuator/prometheus"
    for name, port in MANAGEMENT_PORTS.items()
}

R11_N1_BASELINE_FILE = (
    REPO_ROOT
    / "docs/testing/evidence/L6-I3/R11/final-sla/final-sla-n1-nominal-1-1794164856_decoupled.json"
)
L7_I1_SMOKE_FILE = REPO_ROOT / "docs/testing/evidence/L7-I1/smoke_e2e_results.json"
FIXTURES_FILE = (
    REPO_ROOT / "docs/testing/evidence/L6-I3/R11/final-sla/fixtures_manifest.json"
)
EVIDENCE_DIR = REPO_ROOT / "docs/testing/evidence/L7-I2-sequential-discovery"


def percentile(sorted_vals: List[float], p: float) -> float:
    if not sorted_vals:
        return 0.0
    k = (len(sorted_vals) - 1) * p
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return sorted_vals[int(k)]
    return sorted_vals[int(f)] * (c - k) + sorted_vals[int(c)] * (k - f)


def compute_stats(vals: List[float]) -> Dict[str, Any]:
    clean = sorted(v for v in vals if v is not None and not math.isnan(v))
    if not clean:
        return {"count": 0, "mean": None, "min": None, "p50": None, "p90": None, "p95": None, "p99": None, "max": None}
    return {
        "count": len(clean),
        "mean": round(sum(clean) / len(clean), 2),
        "min": round(clean[0], 2),
        "p50": round(percentile(clean, 0.50), 2),
        "p90": round(percentile(clean, 0.90), 2),
        "p95": round(percentile(clean, 0.95), 2),
        "p99": round(percentile(clean, 0.99), 2),
        "max": round(clean[-1], 2),
    }


def http_get(url: str, token: Optional[str] = None, binding_token: Optional[str] = None, timeout: float = 10.0):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if binding_token:
        headers["X-Pocoma-Binding-Token"] = binding_token
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))


def http_post(url: str, data: dict, token: str, idempotency_key: str, timeout: float = 10.0):
    body = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Idempotency-Key": idempotency_key,
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode("utf-8"))


def clear_container_handoff_logs() -> None:
    for container in RUNTIME_CONTAINERS:
        subprocess.run(
            ["docker", "exec", container, "rm", "-f", "/tmp/pocoma-stage-handoffs.jsonl"],
            capture_output=True,
            text=True,
        )


def _parse_json_lines(raw_text, extra=None):
    items = []
    for line in (raw_text or "").splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        try:
            obj = json.loads(stripped)
            if extra:
                obj.update(extra)
            items.append(obj)
        except Exception:
            continue
    return items


def collect_container_handoff_logs() -> List[Dict[str, Any]]:
    events: List[Dict[str, Any]] = []
    for container in RUNTIME_CONTAINERS:
        res = subprocess.run(
            ["docker", "exec", container, "sh", "-c", "cat /tmp/pocoma-stage-handoffs.jsonl 2>/dev/null || true"],
            capture_output=True, text=True,
        )
        if res.returncode == 0 and res.stdout:
            events.extend(_parse_json_lines(res.stdout, {"runtime": container}))
    return events


def collect_docker_stats() -> List[Dict[str, str]]:
    res = subprocess.run(
        ["docker", "stats", "--no-stream", "--format", "{{json .}}", db_gen.POSTGRES_CONTAINER, *RUNTIME_CONTAINERS],
        capture_output=True, text=True,
    )
    return _parse_json_lines(res.stdout) if res.returncode == 0 else []


_PROMETHEUS_KEYS = (
    "pocoma_v3_discovery_references_total", "pocoma_v3_discovery_duration_seconds",
    "pocoma_v3_attempt_started_total", "pocoma_v3_attempt_outcomes_total",
    "pocoma_v3_quarantine_transitions_total", "pocoma_v3_claim_operations_total",
    "hikaricp_connections_", "pocoma_v3_capacity_used", "pocoma_v3_physical_pending",
)


def _parse_prometheus_line(line):
    if line.startswith("#") or not line.strip() or not any(k in line for k in _PROMETHEUS_KEYS):
        return None
    parts = line.rsplit(" ", 1)
    if len(parts) != 2:
        return None
    try:
        return parts[0], float(parts[1])
    except ValueError:
        return None


def _scrape_single_prometheus(url: str) -> Dict[str, float]:
    metrics: Dict[str, float] = {}
    try:
        with urllib.request.urlopen(urllib.request.Request(url), timeout=5) as resp:
            for line in resp.read().decode("utf-8").splitlines():
                parsed = _parse_prometheus_line(line)
                if parsed:
                    metrics[parsed[0]] = parsed[1]
    except Exception:
        pass
    return metrics


def scrape_prometheus_discovery_metrics() -> Dict[str, Dict[str, float]]:
    return {rt: _scrape_single_prometheus(url) for rt, url in PROMETHEUS_ENDPOINTS.items()}


@contextmanager
def resource_samples(enabled):
    samples = []
    if not enabled:
        yield samples
        return
    stop = threading.Event()

    def sample():
        start = time.monotonic()
        while not stop.is_set():
            samples.append({
                "elapsedSeconds": round(time.monotonic() - start, 3),
                "docker": collect_docker_stats(),
                "prometheus": scrape_prometheus_discovery_metrics(),
            })
            stop.wait(3)

    thread = threading.Thread(target=sample, name="l7-resources", daemon=True)
    thread.start()
    try:
        yield samples
    finally:
        stop.set()
        thread.join(timeout=40)


def capture_pg_explain_and_io() -> Dict[str, Any]:
    counter_pos = int(
        db_gen.run_psql("SELECT position FROM v3_request_counter WHERE family='COMMAND';")
    )
    after_pos = max(0, counter_pos - 32)
    upper_pos = counter_pos

    explain_upper = db_gen.run_psql(
        "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "
        "SELECT position FROM v3_request_counter WHERE family = 'COMMAND';"
    )
    explain_range = db_gen.run_psql(
        f"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) "
        f"SELECT p.position, p.request_id "
        f"FROM v3_request_position p "
        f"WHERE p.family = 'COMMAND' AND p.position > {after_pos} AND p.position <= {upper_pos} "
        f"ORDER BY p.position ASC;"
    )
    io_stats = db_gen.run_psql(
        """
        SELECT json_agg(row_to_json(t)) FROM (
            SELECT s.relname,
                   s.seq_scan, s.seq_tup_read,
                   s.idx_scan, s.idx_tup_fetch,
                   s.n_tup_ins, s.n_tup_upd, s.n_tup_del,
                   io.heap_blks_read, io.heap_blks_hit,
                   io.idx_blks_read, io.idx_blks_hit
            FROM pg_stat_user_tables s
            JOIN pg_statio_user_tables io USING (relid)
            WHERE s.schemaname = 'v3_write'
              AND s.relname IN (
                  'v3_command', 'v3_identity_request',
                  'v3_request_counter', 'v3_request_position',
                  'consumption_slot', 'discovery_checkpoint'
              )
            ORDER BY s.relname
        ) t;
        """
    )
    return {
        "windowTested": {"afterExclusive": after_pos, "upperInclusive": upper_pos},
        "explainUpperPublished": json.loads(explain_upper),
        "explainPublishedRange": json.loads(explain_range),
        "tableIoStats": json.loads(io_stats) if io_stats else [],
    }


def poll_json(url, token, accept, binding_token=None, timeout=60):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            status, data = http_get(url, token, binding_token)
            if status == 200 and accept(data):
                return data
        except urllib.error.HTTPError as error:
            if error.code not in (404, 503):
                raise
        time.sleep(0.02)
    raise TimeoutError(f"Qualification fixture did not converge: {url}")


def fixture_binding(token, initialize):
    url = f"{READ_BASE_URL}/api/v3/me/binding"
    try:
        _, data = http_get(url, token)
        if data.get("active") and data.get("bindingToken"):
            return data["bindingToken"]
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    if not initialize:
        raise ValueError("Active binding required; use --initialize-binding for a disposable stack")
    status, admission = http_post(
        f"{WRITE_BASE_URL}/api/v3/identity/requests", {"operation": "Registration"},
        token, "l7-ab-bootstrap-registration")
    if status != 202:
        raise ValueError(f"Registration expected HTTP 202, received {status}")
    request_id = admission["requestId"]
    poll_json(f"{READ_BASE_URL}/api/v3/identity/requests/{request_id}/result", token,
              lambda data: data.get("outcome") in ("COMPLETED", "CHANGED"))
    return poll_json(url, token, lambda data: data.get("active") and data.get("bindingToken"))["bindingToken"]


def _create_medium_pot(idx, token, binding_token):
    pid = str(uuid.uuid4())
    shs = [str(uuid.uuid4()) for _ in range(20)]
    _, adm1 = http_post(
        f"{WRITE_BASE_URL}/api/v3/commands",
        {"operation": "CreatePot", "potId": pid, "observedVersion": 0, "bindingToken": binding_token, "payload": {"label": f"AB-Pot-{idx}"}},
        token, f"ab-cp-{pid}",
    )
    poll_json(f"{READ_BASE_URL}/api/v3/commands/{adm1['requestId']}/result", token,
              lambda data: data.get("outcome") in ("COMPLETED", "CHANGED"))
    targets = [{"id": sid, "name": f"U{i}", "attachment": "SELF" if i == 0 else "FREE", "weight": {"numerator": "1", "denominator": "1"}}
               for i, sid in enumerate(shs)]
    _, adm2 = http_post(
        f"{WRITE_BASE_URL}/api/v3/commands",
        {"operation": "AddShareholders", "potId": pid, "observedVersion": 1, "bindingToken": binding_token, "payload": {"targets": targets}},
        token, f"ab-as-{pid}",
    )
    poll_json(f"{READ_BASE_URL}/api/v3/commands/{adm2['requestId']}/result", token,
              lambda data: data.get("outcome") in ("COMPLETED", "CHANGED"))
    poll_json(f"{READ_BASE_URL}/api/v3/pots/{pid}?minVersion=2", token,
              lambda data: data.get("potVersion", 0) >= 2, binding_token)
    return {"potId": pid, "shareholders": shs, "currentVersion": 2}


def _fetch_existing_pot(p, token, binding_token):
    try:
        st, pdata = http_get(f"{READ_BASE_URL}/api/v3/pots/{p['potId']}", token, binding_token)
        if st == 200 and pdata.get("potVersion", 1) <= 60:
            return {"potId": p["potId"], "shareholders": p["shareholders"], "currentVersion": pdata.get("potVersion", 1)}
    except Exception:
        pass
    return None


def ensure_pot_pool(token: str, binding_token: str, min_pots: int = 25) -> List[Dict[str, Any]]:
    """Loads existing SPEC-09 20-shareholder pots and creates additional ones if needed."""
    sql = (
        'SELECT json_agg(row_to_json(t)) FROM ('
        'SELECT pot_id AS "potId", json_agg(object_id ORDER BY object_id) AS shareholders '
        'FROM v3_shareholder_identity GROUP BY pot_id HAVING count(*) = 20 ORDER BY pot_id) t;'
    )
    raw = db_gen.run_psql(sql)
    pots = json.loads(raw) if raw and raw != "null" else []
    pot_states = [s for p in pots if (s := _fetch_existing_pot(p, token, binding_token)) is not None]
    needed = max(0, min_pots - len(pot_states))
    if needed > 0:
        print(f"  Creating {needed} additional 20-shareholder pots for concurrent workload...")
        pot_states.extend(_create_medium_pot(idx, token, binding_token) for idx in range(needed))
    return pot_states


def _poll_identity_qualification(req_id, subject, t0):
    deadline = time.time() + 15.0
    terminal_ms = read_result_ms = binding_ms = None
    while time.time() < deadline:
        if terminal_ms is None and db_gen.run_psql(f"SELECT status FROM v3_identity_request WHERE request_id='{req_id}'::uuid;") == "TERMINAL":
            terminal_ms = round((time.time() - t0) * 1000.0, 2)
        if read_result_ms is None and db_gen.run_psql(
            f"SELECT write_outcome FROM read_identity_request_result WHERE request_id='{req_id}'::uuid;",
            db=db_gen.READ_DB, schema=db_gen.READ_SCHEMA,
        ) == "CHANGED":
            read_result_ms = round((time.time() - t0) * 1000.0, 2)
        if binding_ms is None and db_gen.run_psql(
            f"SELECT count(*) FROM read_current_binding WHERE issuer='http://localhost:8081/realms/pocoma' AND subject='{subject}';",
            db=db_gen.READ_DB, schema=db_gen.READ_SCHEMA,
        ) == "1":
            binding_ms = round((time.time() - t0) * 1000.0, 2)
        if all(v is not None for v in (terminal_ms, read_result_ms, binding_ms)):
            break
        time.sleep(0.01)
    return terminal_ms, read_result_ms, binding_ms


def qualify_identity_request(token: str) -> Dict[str, Any]:
    """Submits a real IdentityRequest (RegisterUser) and measures end-to-end completion."""
    subject = f"ab-id-{uuid.uuid4().hex[:10]}"
    req_id = str(uuid.uuid4())
    t0 = time.time()
    snap_sql = (
        "SELECT json_build_object("
        "'counter', (SELECT position FROM v3_request_counter WHERE family='IDENTITY_REQUEST'), "
        "'checkpoints', (SELECT json_agg(position ORDER BY partition_id) FROM discovery_checkpoint WHERE consumption_id='2999e75c-272d-5afe-a966-c9364ef021fe'));"
    )
    before = json.loads(db_gen.run_psql(snap_sql))
    admit_sql = f"""
    WITH next_pos AS (UPDATE v3_request_counter SET position = position + 1 WHERE family = 'IDENTITY_REQUEST' RETURNING position),
    ins AS (
        INSERT INTO v3_identity_request(
            request_id, requester_issuer, requester_subject, operation, binding_token,
            target_issuer, target_subject, admission_context, created_at, status
        ) VALUES (
            '{req_id}'::uuid, 'http://localhost:8081/realms/pocoma', '{subject}', 'REGISTRATION', NULL,
            'http://localhost:8081/realms/pocoma', '{subject}',
            jsonb_build_object(
                'validator', 'ab-qualification-validator', 'contractVersion', 'v1',
                'initiator', jsonb_build_object('issuer', 'http://localhost:8081/realms/pocoma', 'subject', '{subject}'),
                'target', jsonb_build_object('issuer', 'http://localhost:8081/realms/pocoma', 'subject', '{subject}'),
                'intentionFingerprint', '{req_id}', 'grantOrCorrelationReference', 'ab-id-key-{subject}',
                'validatedAt', '2026-11-09T00:00:00Z', 'attestations', '{{}}'::jsonb, 'origin', 'CONTROLLED_INTERNAL'
            ), clock_timestamp(), 'PENDING'
        ) RETURNING request_id
    )
    INSERT INTO v3_request_position(family, position, request_id)
    SELECT 'IDENTITY_REQUEST', next_pos.position, ins.request_id FROM next_pos, ins;
    """
    t_admit_epoch = int(time.time() * 1000)
    db_gen.run_psql(admit_sql)
    terminal_ms, read_result_ms, binding_ms = _poll_identity_qualification(req_id, subject, t0)
    return {
        "requestId": req_id, "admitEpochMs": t_admit_epoch,
        "writeTerminalLatencyMs": terminal_ms, "readResultLatencyMs": read_result_ms, "readBindingLatencyMs": binding_ms,
        "before": before, "after": json.loads(db_gen.run_psql(snap_sql)),
        "qualified": all(v is not None for v in (terminal_ms, read_result_ms, binding_ms)),
    }


def _submit_with_retry(payload, tok, idem_key):
    adm_status = 0
    for attempt in range(15):
        try:
            st, adm_res = http_post(f"{WRITE_BASE_URL}/api/v3/commands", payload, tok, idem_key)
            adm_status = st
            if st == 202:
                return adm_res["requestId"], st
        except urllib.error.HTTPError as e:
            adm_status = e.code
            if e.code in (503, 409) and attempt < 14:
                time.sleep(0.02 * (attempt + 1))
                continue
            break
        except Exception:
            break
    return None, adm_status


def _poll_command_result(req_id, tok, observed_v):
    deadline = time.time() + 30.0
    while time.time() < deadline:
        try:
            st, rdata = http_get(f"{READ_BASE_URL}/api/v3/commands/{req_id}/result", tok)
            if st == 200 and rdata.get("outcome") in ("COMPLETED", "CHANGED"):
                return rdata.get("resultingPotVersion", observed_v + 1), True
        except urllib.error.HTTPError:
            pass
        time.sleep(0.015)
    return observed_v + 1, False


def _verify_c03_ready(pot, pot_id, target_v, auth_pair):
    tok, binding_token = auth_pair
    deadline = time.time() + 30.0
    while time.time() < deadline:
        try:
            st, bdata = http_get(f"{READ_BASE_URL}/api/v3/pots/{pot_id}/versions/{target_v}/balance", tok, binding_token)
            if st == 200 and len(bdata.get("balances", {})) == len(pot["shareholders"]):
                return True, time.time() * 1000.0
        except urllib.error.HTTPError:
            pass
        time.sleep(0.02)
    return False, None


def _execute_one_command(seq_idx, scheduled_time, exec_ctx):
    profile, rate_cmd_s, token_mgr, binding_token, pot_states, pot_locks, dispatched, dispatch_lock = exec_ctx
    now = time.time()
    if scheduled_time > now:
        time.sleep(scheduled_time - now)

    pot_idx = seq_idx % len(pot_states)
    with pot_locks[pot_idx]:
        pot = pot_states[pot_idx]
        pot_id, observed_v = pot["potId"], pot["currentVersion"]
        exp_id = str(uuid.uuid4())
        payload = {
            "operation": "CreateExpense", "potId": pot_id, "observedVersion": observed_v, "bindingToken": binding_token,
            "payload": {
                "id": exp_id, "label": f"AB {profile} r{rate_cmd_s} #{seq_idx}", "date": "2026-11-09",
                "amount": {"numerator": str(1000 + seq_idx * 10), "denominator": "100"},
                "payerId": pot["shareholders"][seq_idx % len(pot["shareholders"])],
                "participants": [{"id": sid, "weight": None} for sid in pot["shareholders"]],
            },
        }
        tok = token_mgr.get_token()
        t_send_epoch = time.time() * 1000.0
        req_id, adm_status = _submit_with_retry(payload, tok, f"ab-{profile}-r{int(rate_cmd_s)}-{seq_idx}-{exp_id[:8]}")
        t_admit_epoch = time.time() * 1000.0
        if not req_id:
            with dispatch_lock:
                dispatched.append({"seq": seq_idx, "admitted": False, "status": adm_status})
            return
        target_v, result_ok = _poll_command_result(req_id, tok, observed_v)
        pot["currentVersion"] = target_v

    c03_ok, t_c03_epoch = _verify_c03_ready(pot, pot_id, target_v, (token_mgr.get_token(), binding_token)) if result_ok else (False, None)
    with dispatch_lock:
        dispatched.append({
            "seq": seq_idx, "admitted": True, "commandId": req_id, "potId": pot_id, "targetVersion": target_v,
            "tSendEpochMs": round(t_send_epoch, 2), "tAdmitEpochMs": round(t_admit_epoch, 2),
            "clientC03EpochMs": round(t_c03_epoch, 2) if t_c03_epoch else None,
            "clientE2eMs": round(t_c03_epoch - t_send_epoch, 2) if t_c03_epoch else None,
            "c03Valid": c03_ok, "resultAvailable": result_ok,
        })


_CMD_STAGE_FIELDS = {
    "T0": ("t0EpochMs",),
    "T0a": ("t0aEpochMs", "partition"),
    "T0b": ("t0bEpochMs", "discoveryToSlotMs"),
    "T0c": ("t0cEpochMs", "slotAcquisitionWaitMs"),
    "T1": ("t1EpochMs", "t0aToT1Ms", "discoveryToSlotMs", "slotAcquisitionWaitMs", "acquisitionToAttemptMs"),
    "LINK": ("potId", "resultingVersion", "potEventId", "completionEventId"),
    "T2": ("t2EpochMs", "t1ToT2Ms", "potId", "resultingVersion", "potEventId", "completionEventId"),
}


def _record_cmd_stage(by_cmd, cid, st, ev):
    rec = by_cmd.setdefault(cid, {"commandId": cid})
    for k in _CMD_STAGE_FIELDS[st]:
        if ev.get(k) is None:
            continue
        if st == "T2" and k in ("t2EpochMs", "t1ToT2Ms"):
            rec[k] = ev.get(k)
        else:
            rec.setdefault(k, ev.get(k))


def _record_event_stage(by_event, st, ev):
    eid, cons = ev.get("eventId"), ev.get("consumption")
    if not eid or not cons:
        return
    ep = by_event.setdefault(eid, {}).setdefault(cons, {})
    if st == "T3":
        ep.setdefault("t3EpochMs", ev.get("t3EpochMs"))
        return
    ep["t4EpochMs"] = ev.get("t4EpochMs")
    if ev.get("t3ToT4Ms") is not None:
        ep["t3ToT4Ms"] = ev.get("t3ToT4Ms")


def _index_handoff_events(raw_events):
    by_cmd: Dict[str, Dict[str, Any]] = {}
    by_event: Dict[str, Dict[str, Dict[str, Any]]] = {}
    for ev in raw_events:
        st, cid = ev.get("stage"), ev.get("commandId")
        if cid and st in _CMD_STAGE_FIELDS:
            _record_cmd_stage(by_cmd, cid, st, ev)
        elif st in ("T3", "T4"):
            _record_event_stage(by_event, st, ev)
    return by_cmd, by_event


def _delta_ms(start, end):
    return float(max(0, end - start)) if (start and end) else None


def _correlate_dispatched(dispatched, by_cmd, by_event):
    correlated_records = []
    series = {k: [] for k in ("t0_t0a", "t0a_t1", "t0_t1", "t1_t2", "t2_t3", "t3_t4", "t0_t4", "t0_c03", "e2e")}
    for d in sorted(dispatched, key=lambda x: x["seq"]):
        if not d.get("admitted"):
            continue
        if d.get("clientE2eMs") is not None:
            series["e2e"].append(d["clientE2eMs"])
        rec = by_cmd.get(d["commandId"], {})
        t0 = rec.get("t0EpochMs") or (int(round(d["tAdmitEpochMs"])) if d.get("tAdmitEpochMs") else None)
        t0a, t1, t2 = rec.get("t0aEpochMs"), rec.get("t1EpochMs"), rec.get("t2EpochMs")
        cr = by_event.get(rec.get("completionEventId"), {}).get("CommandResult", {}) if rec.get("completionEventId") else {}
        pv = by_event.get(rec.get("potEventId"), {}).get("PotVersion", {}) if rec.get("potEventId") else {}
        pb = by_event.get(rec.get("potEventId"), {}).get("PotBalance", {}) if rec.get("potEventId") else {}
        t3_cr, t4_cr = cr.get("t3EpochMs"), cr.get("t4EpochMs")
        t4_c03 = max(pv["t4EpochMs"], pb["t4EpochMs"]) if (pv.get("t4EpochMs") and pb.get("t4EpochMs")) else None
        vals = {
            "t0_t0a": _delta_ms(t0, t0a), "t0a_t1": float(rec["t0aToT1Ms"]) if rec.get("t0aToT1Ms") is not None else None,
            "t0_t1": _delta_ms(t0, t1), "t1_t2": float(rec["t1ToT2Ms"]) if rec.get("t1ToT2Ms") is not None else None,
            "t2_t3": _delta_ms(t2, t3_cr), "t3_t4": float(cr["t3ToT4Ms"]) if cr.get("t3ToT4Ms") is not None else None,
            "t0_t4": _delta_ms(t0, t4_cr), "t0_c03": _delta_ms(t0, t4_c03),
        }
        for k, v in vals.items():
            if v is not None:
                series[k].append(v)
        correlated_records.append({
            "commandId": d["commandId"], "partition": rec.get("partition"), "t0EpochMs": t0, "t0aEpochMs": t0a,
            "t1EpochMs": t1, "t2EpochMs": t2, "t3CommandResultEpochMs": t3_cr, "t4CommandResultEpochMs": t4_cr,
            "t4C03ReadyEpochMs": t4_c03, "t0ToT0aMs": vals["t0_t0a"], "t0aToT1Ms": rec.get("t0aToT1Ms"),
            "t0ToT1Ms": vals["t0_t1"], "t1ToT2Ms": rec.get("t1ToT2Ms"), "t2ToT3CommandResultMs": vals["t2_t3"],
            "t3ToT4CommandResultMs": cr.get("t3ToT4Ms"), "t0ToT4CommandResultMs": vals["t0_t4"],
            "t0ToT4C03ReadMs": vals["t0_c03"], "clientE2eMs": d.get("clientE2eMs"), "c03Valid": d.get("c03Valid"),
        })
    intervals = {
        "T0_to_T0a_waitBeforeDiscoveryMs": compute_stats(series["t0_t0a"]),
        "T0a_to_T1_discoveryToExecutionMs": compute_stats(series["t0a_t1"]),
        "T0_to_T1_admissionToExecutionMs": compute_stats(series["t0_t1"]),
        "T1_to_T2_commandExecutionMs": compute_stats(series["t1_t2"]),
        "T2_to_T3_waitCommandResultMs": compute_stats(series["t2_t3"]),
        "T3_to_T4_processCommandResultMs": compute_stats(series["t3_t4"]),
        "T0_to_T4_commandResultTotalMs": compute_stats(series["t0_t4"]),
        "T0_to_T4_c03ReadReadyTotalMs": compute_stats(series["t0_c03"]),
        "Client_HTTP_E2E_C03_Ms": compute_stats(series["e2e"]),
    }
    return correlated_records, intervals


def run_workload_campaign(profile: str, rate_cmd_s: float, total_commands: int, campaign_ctx) -> Dict[str, Any]:
    token_mgr, binding_token, pot_states, sample_resources = campaign_ctx
    print(f"\n--- Campaign [{profile}] @ {rate_cmd_s} cmd/s ({total_commands} commands) ---")
    clear_container_handoff_logs()
    prom_before, pg_before = scrape_prometheus_discovery_metrics(), capture_pg_explain_and_io()
    num_pots = len(pot_states)
    pot_locks = [threading.Lock() for _ in range(num_pots)]
    dispatched: List[Dict[str, Any]] = []
    exec_ctx = (profile, rate_cmd_s, token_mgr, binding_token, pot_states, pot_locks, dispatched, threading.Lock())
    t_start = time.time()
    with resource_samples(sample_resources) as sampled:
        with ThreadPoolExecutor(max_workers=min(32, num_pots)) as executor:
            for f in as_completed([executor.submit(_execute_one_command, i, t_start + i / rate_cmd_s, exec_ctx) for i in range(total_commands)]):
                f.result()
        workload_duration = time.time() - t_start
    admissions = [d for d in dispatched if d["admitted"]]
    results = sum(d.get("resultAvailable", False) for d in admissions)
    time.sleep(1.0)
    campaign_duration_s = round(time.time() - t_start, 2)
    by_cmd, by_event = _index_handoff_events(collect_container_handoff_logs())
    correlated_records, intervals = _correlate_dispatched(dispatched, by_cmd, by_event)
    print(f"  [{profile} @ {rate_cmd_s} cmd/s] Correlated={len(correlated_records)}/{total_commands} | T0->T4(C03) mean={intervals['T0_to_T4_c03ReadReadyTotalMs']['mean']}ms")
    return {
        "profile": profile, "rateCmdPerSec": rate_cmd_s, "totalCommands": total_commands,
        "campaignDurationSeconds": campaign_duration_s, "workloadDurationSeconds": round(workload_duration, 3),
        "admittedCount": len(admissions), "readResultCount": results,
        "admittedPerSecondOverWorkload": len(admissions) / workload_duration,
        "resultsPerSecondOverWorkload": results / workload_duration,
        "admissionHttpMs": compute_stats([d["tAdmitEpochMs"] - d["tSendEpochMs"] for d in admissions]),
        "dispatchFailures": [d for d in dispatched if not d["admitted"]],
        "correlatedCount": len(correlated_records),
        "oracleC03ValidCount": sum(1 for r in correlated_records if r.get("c03Valid")),
        "intervalsMs": intervals, "identityRequestQualification": qualify_identity_request(token_mgr.get_token()),
        "sqlIntegrityAudit": db_gen.verify_sql_integrity(),
        "postgresExplainAndIoBefore": pg_before, "postgresExplainAndIoAfter": capture_pg_explain_and_io(),
        "prometheusBefore": prom_before, "prometheusAfter": scrape_prometheus_discovery_metrics(),
        "dockerStats": collect_docker_stats(), "workloadResourceSamples": sampled, "records": correlated_records,
    }


_CSV_INTERVAL_KEYS = (
    "T0_to_T0a_waitBeforeDiscoveryMs", "T0a_to_T1_discoveryToExecutionMs",
    "T0_to_T1_admissionToExecutionMs", "T1_to_T2_commandExecutionMs",
    "T2_to_T3_waitCommandResultMs", "T3_to_T4_processCommandResultMs",
    "T0_to_T4_commandResultTotalMs", "T0_to_T4_c03ReadReadyTotalMs",
)


def _write_ab_summary_csv(all_campaigns):
    csv_path = EVIDENCE_DIR / "ab_comparison_summary.csv"
    headers = [
        "profile", "rate_cmd_s", "total_commands", "correlated_count", "c03_valid_count",
        "t0_to_t0a_mean_ms", "t0_to_t0a_p50_ms", "t0_to_t0a_p95_ms",
        "t0a_to_t1_mean_ms", "t0a_to_t1_p50_ms", "t0a_to_t1_p95_ms",
        "t0_to_t1_mean_ms", "t0_to_t1_p50_ms", "t0_to_t1_p95_ms",
        "t1_to_t2_mean_ms", "t1_to_t2_p50_ms", "t1_to_t2_p95_ms",
        "t2_to_t3_cr_mean_ms", "t2_to_t3_cr_p50_ms", "t2_to_t3_cr_p95_ms",
        "t3_to_t4_cr_mean_ms", "t3_to_t4_cr_p50_ms", "t3_to_t4_cr_p95_ms",
        "t0_to_t4_cr_mean_ms", "t0_to_t4_cr_p50_ms", "t0_to_t4_cr_p95_ms",
        "t0_to_t4_c03_mean_ms", "t0_to_t4_c03_p50_ms", "t0_to_t4_c03_p95_ms",
        "client_e2e_c03_p50_ms", "client_e2e_c03_p95_ms",
    ]
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(headers)
        for c in all_campaigns:
            iv = c["intervalsMs"]
            stats_cols = [iv[k][stat] for k in _CSV_INTERVAL_KEYS for stat in ("mean", "p50", "p95")]
            writer.writerow([
                c["profile"], c["rateCmdPerSec"], c["totalCommands"], c["correlatedCount"], c["oracleC03ValidCount"],
                *stats_cols, iv["Client_HTTP_E2E_C03_Ms"]["p50"], iv["Client_HTTP_E2E_C03_Ms"]["p95"],
            ])
    return csv_path


def _parse_ab_args():
    parser = argparse.ArgumentParser(description="Pocoma V3 A/B Sequential Burnable Discovery Qualification")
    parser.add_argument("--profiles", default="H1,H2,H3,H4,H5", help="Comma-separated DB profiles to qualify (default: H1,H2,H3,H4,H5)")
    parser.add_argument("--rates", default="1,10,50", help="Comma-separated rates in cmd/s (default: 1,10,50)")
    parser.add_argument("--output", required=True, type=Path, help="New evidence directory")
    parser.add_argument("--initialize-binding", action="store_true")
    parser.add_argument("--commands", type=int, help="Override sample size for each selected rate")
    parser.add_argument("--sample-resources", action="store_true")
    args = parser.parse_args()
    profiles = [p.strip() for p in args.profiles.split(",") if p.strip()]
    rates = [float(r.strip()) for r in args.rates.split(",") if r.strip()]
    if not profiles or not rates or any(r not in (1, 10, 50) for r in rates):
        parser.error("select known profiles and at least one of 1,10,50 cmd/s")
    if args.commands is not None and args.commands < 1:
        parser.error("--commands must be positive")
    return args, profiles, rates


def _load_merged_campaigns(profile_seeding_reports):
    all_campaigns = []
    for p in ["H1", "H2", "H3", "H4", "H5"]:
        p_file = EVIDENCE_DIR / f"db_profile_{p}.json"
        if p_file.exists() and p not in profile_seeding_reports:
            profile_seeding_reports[p] = json.loads(p_file.read_text(encoding="utf-8"))
        for r in [1, 10, 50]:
            c_file = EVIDENCE_DIR / f"campaign_{p}_rate_{r}.json"
            if c_file.exists():
                all_campaigns.append(json.loads(c_file.read_text(encoding="utf-8")))
    return all_campaigns


def main() -> None:
    args, profiles, rates = _parse_ab_args()
    global EVIDENCE_DIR
    EVIDENCE_DIR = args.output.resolve()
    EVIDENCE_DIR.mkdir(parents=True, exist_ok=False)

    token_mgr = OidcTokenManager()
    binding_token = fixture_binding(token_mgr.get_token(), args.initialize_binding)
    profile_seeding_reports: Dict[str, Any] = {}

    for profile in profiles:
        seed_report = db_gen.apply_profile(profile)
        profile_seeding_reports[profile] = seed_report
        (EVIDENCE_DIR / f"db_profile_{profile}.json").write_text(json.dumps(seed_report, indent=2), encoding="utf-8")
        print("[Init] Ensuring pool of 25 SPEC-09 medium pots (20 shareholders each, version <= 60)...")
        pot_states = ensure_pot_pool(token_mgr.get_token(), binding_token, min_pots=25)
        profile_rates = rates if profile in ("H1", "H3", "H4") else [r for r in rates if r in (1.0, 10.0)]
        for rate in profile_rates:
            total_cmds = args.commands or {1: 30, 10: 60, 50: 100}[rate]
            res = run_workload_campaign(profile, rate, total_cmds, (token_mgr, binding_token, pot_states, args.sample_resources))
            (EVIDENCE_DIR / f"campaign_{profile}_rate_{int(rate)}.json").write_text(json.dumps(res, indent=2), encoding="utf-8")

    all_campaigns = _load_merged_campaigns(profile_seeding_reports)
    csv_path = _write_ab_summary_csv(all_campaigns)

    summary = {
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "baselineR11Monolith1CmdS": {
            "sourceFile": str(R11_N1_BASELINE_FILE.relative_to(REPO_ROOT)),
            "T0_to_T0a_waitBeforeDiscoveryMs": {"mean": 679.83, "p50": 682.36, "p95": 1310.58},
            "T0_to_T1_admissionToExecutionMs": {"mean": 733.02, "p50": 729.43, "p95": 1368.08},
            "T1_to_T2_commandExecutionMs": {"mean": 14.85, "p50": 10.66, "p95": 32.60},
            "T2_to_T3_waitCommandResultMs": {"mean": 101.16, "p50": 100.06, "p95": 175.26},
            "T3_to_T4_processCommandResultMs": {"mean": 1.80, "p50": 1.16, "p95": 3.10},
            "reconstitutedMeanT0ToT4CommandResultMs": 850.83,
            "clientHttpE2eC03P95Ms": 1558.50,
        },
        "baselineL7I1FiveRuntimesUuidWalk1CmdS": {
            "sourceFile": str(L7_I1_SMOKE_FILE.relative_to(REPO_ROOT)),
            "admissionMs": {"p50": 7.5, "p95": 16.4},
            "commandResultMs": {"p50": 375.6, "p95": 934.7},
            "readModelVersionMs": {"p50": 715.0, "p95": 1775.5},
            "clientHttpE2eC03Ms": {"p50": 719.9, "p90": 1217.5, "p95": 1781.1},
        },
        "profileSeeding": profile_seeding_reports,
        "campaigns": [{k: v for k, v in c.items() if k != "records"} for c in all_campaigns],
    }
    (EVIDENCE_DIR / "ab_qualification_summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(f"\n[Done] Wrote summary to {EVIDENCE_DIR / 'ab_qualification_summary.json'} and {csv_path}")


if __name__ == "__main__":
    main()
