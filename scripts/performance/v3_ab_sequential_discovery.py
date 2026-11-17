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


def collect_container_handoff_logs() -> List[Dict[str, Any]]:
    events: List[Dict[str, Any]] = []
    for container in RUNTIME_CONTAINERS:
        res = subprocess.run(
            ["docker", "exec", container, "sh", "-c", "cat /tmp/pocoma-stage-handoffs.jsonl 2>/dev/null || true"],
            capture_output=True,
            text=True,
        )
        if res.returncode == 0 and res.stdout:
            for line in res.stdout.splitlines():
                line = line.strip()
                if not line:
                    continue
                try:
                    ev = json.loads(line)
                    ev["runtime"] = container
                    events.append(ev)
                except Exception:
                    continue
    return events


def collect_docker_stats() -> List[Dict[str, str]]:
    res = subprocess.run(
        [
            "docker", "stats", "--no-stream",
            "--format", "{{json .}}",
            db_gen.POSTGRES_CONTAINER,
            *RUNTIME_CONTAINERS,
        ],
        capture_output=True,
        text=True,
    )
    stats = []
    if res.returncode == 0:
        for line in res.stdout.splitlines():
            if line.strip():
                try:
                    stats.append(json.loads(line))
                except Exception:
                    pass
    return stats


def scrape_prometheus_discovery_metrics() -> Dict[str, Dict[str, float]]:
    out: Dict[str, Dict[str, float]] = {}
    for rt, url in PROMETHEUS_ENDPOINTS.items():
        metrics: Dict[str, float] = {}
        try:
            req = urllib.request.Request(url)
            with urllib.request.urlopen(req, timeout=5) as resp:
                text = resp.read().decode("utf-8")
            for line in text.splitlines():
                if line.startswith("#") or not line.strip():
                    continue
                if any(
                    k in line
                    for k in (
                        "pocoma_v3_discovery_references_total",
                        "pocoma_v3_discovery_duration_seconds",
                        "pocoma_v3_attempt_started_total",
                        "pocoma_v3_attempt_outcomes_total",
                        "pocoma_v3_quarantine_transitions_total",
                        "pocoma_v3_claim_operations_total",
                        "hikaricp_connections_",
                        "pocoma_v3_capacity_used",
                        "pocoma_v3_physical_pending",
                    )
                ):
                    parts = line.rsplit(" ", 1)
                    if len(parts) == 2:
                        try:
                            metrics[parts[0]] = float(parts[1])
                        except ValueError:
                            pass
        except Exception:
            pass
        out[rt] = metrics
    return out


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


def ensure_pot_pool(token: str, binding_token: str, min_pots: int = 25) -> List[Dict[str, Any]]:
    """Loads existing SPEC-09 20-shareholder pots and creates additional ones if needed."""
    sql = """
    SELECT json_agg(row_to_json(t)) FROM (
        SELECT pot_id AS "potId", json_agg(object_id ORDER BY object_id) AS shareholders
        FROM v3_shareholder_identity
        GROUP BY pot_id
        HAVING count(*) = 20
        ORDER BY pot_id
    ) t;
    """
    raw = db_gen.run_psql(sql)
    pots = json.loads(raw) if raw and raw != "null" else []

    pot_states: List[Dict[str, Any]] = []
    for p in pots:
        pid = p["potId"]
        try:
            st, pdata = http_get(f"{READ_BASE_URL}/api/v3/pots/{pid}", token, binding_token)
            if st == 200 and pdata.get("potVersion", 1) <= 60:
                pot_states.append(
                    {
                        "potId": pid,
                        "shareholders": p["shareholders"],
                        "currentVersion": pdata.get("potVersion", 1),
                    }
                )
        except Exception:
            pass

    needed = max(0, min_pots - len(pot_states))
    if needed > 0:
        print(f"  Creating {needed} additional 20-shareholder pots for concurrent workload...")
        for idx in range(needed):
            pid = str(uuid.uuid4())
            shs = [str(uuid.uuid4()) for _ in range(20)]
            _, adm1 = http_post(
                f"{WRITE_BASE_URL}/api/v3/commands",
                {
                    "operation": "CreatePot",
                    "potId": pid,
                    "observedVersion": 0,
                    "bindingToken": binding_token,
                    "payload": {"label": f"AB-Pot-{idx}"},
                },
                token,
                f"ab-cp-{pid}",
            )
            req1 = adm1["requestId"]
            poll_json(f"{READ_BASE_URL}/api/v3/commands/{req1}/result", token,
                      lambda data: data.get("outcome") in ("COMPLETED", "CHANGED"))

            targets = [
                {
                    "id": sid,
                    "name": f"U{i}",
                    "attachment": "SELF" if i == 0 else "FREE",
                    "weight": {"numerator": "1", "denominator": "1"},
                }
                for i, sid in enumerate(shs)
            ]
            _, adm2 = http_post(
                f"{WRITE_BASE_URL}/api/v3/commands",
                {
                    "operation": "AddShareholders",
                    "potId": pid,
                    "observedVersion": 1,
                    "bindingToken": binding_token,
                    "payload": {"targets": targets},
                },
                token,
                f"ab-as-{pid}",
            )
            req2 = adm2["requestId"]
            poll_json(f"{READ_BASE_URL}/api/v3/commands/{req2}/result", token,
                      lambda data: data.get("outcome") in ("COMPLETED", "CHANGED"))
            poll_json(f"{READ_BASE_URL}/api/v3/pots/{pid}?minVersion=2", token,
                      lambda data: data.get("potVersion", 0) >= 2, binding_token)
            pot_states.append({"potId": pid, "shareholders": shs, "currentVersion": 2})

    return pot_states


def qualify_identity_request(token: str) -> Dict[str, Any]:
    """Submits a real IdentityRequest (RegisterUser) and measures end-to-end completion."""
    subject = f"ab-id-{uuid.uuid4().hex[:10]}"
    idem_key = f"ab-id-key-{subject}"
    t0 = time.time()
    # Check if /api/v3/identity/requests is exposed or useJdbc/SQL verification
    sql = f"""
    SELECT json_build_object(
        'counter', (SELECT position FROM v3_request_counter WHERE family='IDENTITY_REQUEST'),
        'checkpoints', (SELECT json_agg(position ORDER BY partition_id) FROM discovery_checkpoint WHERE consumption_id='2999e75c-272d-5afe-a966-c9364ef021fe')
    );
    """
    before = json.loads(db_gen.run_psql(sql))
    req_id = str(uuid.uuid4())
    admit_sql = f"""
    WITH next_pos AS (
        UPDATE v3_request_counter SET position = position + 1
        WHERE family = 'IDENTITY_REQUEST' RETURNING position
    ),
    ins AS (
        INSERT INTO v3_identity_request(
            request_id, requester_issuer, requester_subject,
            operation, binding_token, target_issuer, target_subject,
            admission_context, created_at, status
        ) VALUES (
            '{req_id}'::uuid, 'http://localhost:8081/realms/pocoma', '{subject}',
            'REGISTRATION', NULL, 'http://localhost:8081/realms/pocoma', '{subject}',
            jsonb_build_object(
                'validator', 'ab-qualification-validator',
                'contractVersion', 'v1',
                'initiator', jsonb_build_object('issuer', 'http://localhost:8081/realms/pocoma', 'subject', '{subject}'),
                'target', jsonb_build_object('issuer', 'http://localhost:8081/realms/pocoma', 'subject', '{subject}'),
                'intentionFingerprint', '{req_id}',
                'grantOrCorrelationReference', '{idem_key}',
                'validatedAt', '2026-11-09T00:00:00Z',
                'attestations', '{{}}'::jsonb,
                'origin', 'CONTROLLED_INTERNAL'
            ),
            clock_timestamp(), 'PENDING'
        ) RETURNING request_id
    )
    INSERT INTO v3_request_position(family, position, request_id)
    SELECT 'IDENTITY_REQUEST', next_pos.position, ins.request_id FROM next_pos, ins;
    """
    t_admit_epoch = int(time.time() * 1000)
    db_gen.run_psql(admit_sql)

    # Wait for command-worker to execute and results-worker + business-read-worker to materialize
    deadline = time.time() + 15.0
    terminal_ms = None
    read_result_ms = None
    binding_ms = None
    while time.time() < deadline:
        st_write = db_gen.run_psql(
            f"SELECT status FROM v3_identity_request WHERE request_id='{req_id}'::uuid;"
        )
        if st_write == "TERMINAL" and terminal_ms is None:
            terminal_ms = round((time.time() - t0) * 1000.0, 2)
        st_res = db_gen.run_psql(
            f"SELECT write_outcome FROM read_identity_request_result WHERE request_id='{req_id}'::uuid;",
            db=db_gen.READ_DB,
            schema=db_gen.READ_SCHEMA,
        )
        if st_res == "CHANGED" and read_result_ms is None:
            read_result_ms = round((time.time() - t0) * 1000.0, 2)
        st_bind = db_gen.run_psql(
            f"SELECT count(*) FROM read_current_binding WHERE issuer='http://localhost:8081/realms/pocoma' AND subject='{subject}';",
            db=db_gen.READ_DB,
            schema=db_gen.READ_SCHEMA,
        )
        if st_bind == "1" and binding_ms is None:
            binding_ms = round((time.time() - t0) * 1000.0, 2)
        if terminal_ms is not None and read_result_ms is not None and binding_ms is not None:
            break
        time.sleep(0.01)

    after = json.loads(db_gen.run_psql(sql))
    return {
        "requestId": req_id,
        "admitEpochMs": t_admit_epoch,
        "writeTerminalLatencyMs": terminal_ms,
        "readResultLatencyMs": read_result_ms,
        "readBindingLatencyMs": binding_ms,
        "before": before,
        "after": after,
        "qualified": (terminal_ms is not None and read_result_ms is not None and binding_ms is not None),
    }


def run_workload_campaign(
    profile: str,
    rate_cmd_s: float,
    total_commands: int,
    token_mgr: OidcTokenManager,
    binding_token: str,
    pot_states: List[Dict[str, Any]],
    sample_resources: bool = False,
) -> Dict[str, Any]:
    print(f"\n--- Campaign [{profile}] @ {rate_cmd_s} cmd/s ({total_commands} commands) ---")
    clear_container_handoff_logs()
    prom_before = scrape_prometheus_discovery_metrics()
    pg_before = capture_pg_explain_and_io()

    # Assign each command to a pot in round-robin, pre-computing its expected observedVersion!
    # Because each pot processes its sequence of observedVersion values strictly in order
    # (0 -> 1 -> 2...) and Pot global CAS requires observedVersion == currentVersion at execution time,
    # we assign commands across `len(pot_states)` independent pots, and each pot has a dedicated
    # worker lane that submits the next command on that pot as soon as the previous command on that
    # same pot is admitted & executed (or for 1 cmd/s, 10 cmd/s, 50 cmd/s across 25 pots,
    # each pot only receives 1 command every 25/rate seconds, e.g. at 10 cmd/s -> 1 cmd every 2.5s per pot;
    # at 50 cmd/s -> 1 cmd every 0.5s per pot!).
    num_pots = len(pot_states)
    pot_locks = [threading.Lock() for _ in range(num_pots)]

    dispatched: List[Dict[str, Any]] = []
    dispatch_lock = threading.Lock()

    def execute_one_command(seq_idx: int, scheduled_time: float) -> None:
        now = time.time()
        if scheduled_time > now:
            time.sleep(scheduled_time - now)

        pot_idx = seq_idx % num_pots
        with pot_locks[pot_idx]:
            pot = pot_states[pot_idx]
            pot_id = pot["potId"]
            observed_v = pot["currentVersion"]
            payer_id = pot["shareholders"][seq_idx % len(pot["shareholders"])]
            exp_id = str(uuid.uuid4())
            idem_key = f"ab-{profile}-r{int(rate_cmd_s)}-{seq_idx}-{exp_id[:8]}"
            payload = {
                "operation": "CreateExpense",
                "potId": pot_id,
                "observedVersion": observed_v,
                "bindingToken": binding_token,
                "payload": {
                    "id": exp_id,
                    "label": f"AB {profile} r{rate_cmd_s} #{seq_idx}",
                    "date": "2026-11-09",
                    "amount": {"numerator": str(1000 + seq_idx * 10), "denominator": "100"},
                    "payerId": payer_id,
                    "participants": [{"id": sid, "weight": None} for sid in pot["shareholders"]],
                },
            }

            tok = token_mgr.get_token()
            t_send_epoch = time.time() * 1000.0
            req_id = None
            adm_status = 0
            for attempt in range(15):
                try:
                    st, adm_res = http_post(f"{WRITE_BASE_URL}/api/v3/commands", payload, tok, idem_key)
                    adm_status = st
                    if st == 202:
                        req_id = adm_res["requestId"]
                        break
                except urllib.error.HTTPError as e:
                    adm_status = e.code
                    if e.code in (503, 409) and attempt < 14:
                        time.sleep(0.02 * (attempt + 1))
                        continue
                    break
                except Exception:
                    break

            t_admit_epoch = time.time() * 1000.0
            if not req_id:
                with dispatch_lock:
                    dispatched.append({"seq": seq_idx, "admitted": False, "status": adm_status})
                return

            # Wait for command execution on this pot before sending the next command on the SAME pot
            # so observedVersion is strictly valid for the next command on this pot.
            target_v = observed_v + 1
            deadline = time.time() + 30.0
            result_ok = False
            while time.time() < deadline:
                try:
                    st, rdata = http_get(f"{READ_BASE_URL}/api/v3/commands/{req_id}/result", tok)
                    if st == 200 and rdata.get("outcome") in ("COMPLETED", "CHANGED"):
                        target_v = rdata.get("resultingPotVersion", observed_v + 1)
                        result_ok = True
                        break
                except urllib.error.HTTPError:
                    pass
                time.sleep(0.015)

            pot["currentVersion"] = target_v

        # Outside the per-pot lock, verify Oracle C-03 on HTTP READ
        c03_ok = False
        t_c03_epoch = None
        if result_ok:
            tok = token_mgr.get_token()
            deadline_c03 = time.time() + 30.0
            while time.time() < deadline_c03:
                try:
                    st, bdata = http_get(
                        f"{READ_BASE_URL}/api/v3/pots/{pot_id}/versions/{target_v}/balance",
                        tok,
                        binding_token,
                    )
                    if st == 200 and len(bdata.get("balances", {})) == len(pot["shareholders"]):
                        c03_ok = True
                        t_c03_epoch = time.time() * 1000.0
                        break
                except urllib.error.HTTPError:
                    pass
                time.sleep(0.02)

        with dispatch_lock:
            dispatched.append(
                {
                    "seq": seq_idx,
                    "admitted": True,
                    "commandId": req_id,
                    "potId": pot_id,
                    "targetVersion": target_v,
                    "tSendEpochMs": round(t_send_epoch, 2),
                    "tAdmitEpochMs": round(t_admit_epoch, 2),
                    "clientC03EpochMs": round(t_c03_epoch, 2) if t_c03_epoch else None,
                    "clientE2eMs": round(t_c03_epoch - t_send_epoch, 2) if t_c03_epoch else None,
                    "c03Valid": c03_ok,
                    "resultAvailable": result_ok,
                }
            )

    t_start = time.time()
    interval = 1.0 / rate_cmd_s
    with resource_samples(sample_resources) as sampled:
        with ThreadPoolExecutor(max_workers=min(32, num_pots)) as executor:
            futures = [
                executor.submit(execute_one_command, i, t_start + i * interval)
                for i in range(total_commands)
            ]
            for f in as_completed(futures):
                f.result()
        workload_duration = time.time() - t_start
    admissions = [d for d in dispatched if d["admitted"]]
    results = sum(d.get("resultAvailable", False) for d in admissions)

    # Allow 1s settle for all projection logs to flush
    time.sleep(1.0)
    campaign_duration_s = round(time.time() - t_start, 2)

    docker_stats = collect_docker_stats()
    prom_after = scrape_prometheus_discovery_metrics()
    pg_after = capture_pg_explain_and_io()
    id_qual = qualify_identity_request(token_mgr.get_token())

    # Collect and correlate distributed StageHandoffTracker JSONL logs across all 5 runtimes
    raw_events = collect_container_handoff_logs()
    by_cmd: Dict[str, Dict[str, Any]] = {}
    by_event: Dict[str, Dict[str, Dict[str, Any]]] = {}

    for ev in raw_events:
        st = ev.get("stage")
        cid = ev.get("commandId")
        if cid:
            rec = by_cmd.setdefault(cid, {"commandId": cid})
            if st == "T0":
                rec["t0EpochMs"] = ev.get("t0EpochMs")
            elif st == "T0a":
                rec.setdefault("t0aEpochMs", ev.get("t0aEpochMs"))
                rec.setdefault("partition", ev.get("partition"))
            elif st == "T0b":
                rec.setdefault("t0bEpochMs", ev.get("t0bEpochMs"))
                if ev.get("discoveryToSlotMs") is not None:
                    rec["discoveryToSlotMs"] = ev.get("discoveryToSlotMs")
            elif st == "T0c":
                rec.setdefault("t0cEpochMs", ev.get("t0cEpochMs"))
                if ev.get("slotAcquisitionWaitMs") is not None:
                    rec["slotAcquisitionWaitMs"] = ev.get("slotAcquisitionWaitMs")
            elif st == "T1":
                rec.setdefault("t1EpochMs", ev.get("t1EpochMs"))
                for k in ("t0aToT1Ms", "discoveryToSlotMs", "slotAcquisitionWaitMs", "acquisitionToAttemptMs"):
                    if ev.get(k) is not None:
                        rec[k] = ev.get(k)
            elif st == "LINK":
                for k in ("potId", "resultingVersion", "potEventId", "completionEventId"):
                    if ev.get(k) is not None:
                        rec[k] = ev.get(k)
            elif st == "T2":
                rec["t2EpochMs"] = ev.get("t2EpochMs")
                if ev.get("t1ToT2Ms") is not None:
                    rec["t1ToT2Ms"] = ev.get("t1ToT2Ms")
                for k in ("potId", "resultingVersion", "potEventId", "completionEventId"):
                    if ev.get(k) is not None and k not in rec:
                        rec[k] = ev.get(k)
        elif st in ("T3", "T4"):
            eid = ev.get("eventId")
            cons = ev.get("consumption")
            if eid and cons:
                ep = by_event.setdefault(eid, {}).setdefault(cons, {})
                if st == "T3":
                    ep.setdefault("t3EpochMs", ev.get("t3EpochMs"))
                elif st == "T4":
                    ep["t4EpochMs"] = ev.get("t4EpochMs")
                    if ev.get("t3ToT4Ms") is not None:
                        ep["t3ToT4Ms"] = ev.get("t3ToT4Ms")

    # Correlate per-command series for the commands dispatched in this campaign
    correlated_records: List[Dict[str, Any]] = []
    s_t0_to_t0a: List[float] = []
    s_t0a_to_t1: List[float] = []
    s_t0_to_t1: List[float] = []
    s_t1_to_t2: List[float] = []
    s_t2_to_t3_cr: List[float] = []
    s_t3_to_t4_cr: List[float] = []
    s_t0_to_t4_cr: List[float] = []
    s_t0_to_t4_c03: List[float] = []
    s_client_e2e: List[float] = []

    for d in sorted(dispatched, key=lambda x: x["seq"]):
        if not d.get("admitted"):
            continue
        cid = d["commandId"]
        if d.get("clientE2eMs") is not None:
            s_client_e2e.append(d["clientE2eMs"])
        rec = by_cmd.get(cid, {})
        t0 = rec.get("t0EpochMs") or (int(round(d["tAdmitEpochMs"])) if d.get("tAdmitEpochMs") else None)
        t0a = rec.get("t0aEpochMs")
        t1 = rec.get("t1EpochMs")
        t2 = rec.get("t2EpochMs")
        t0a_to_t1 = rec.get("t0aToT1Ms")
        t1_to_t2 = rec.get("t1ToT2Ms")

        comp_eid = rec.get("completionEventId")
        pot_eid = rec.get("potEventId")

        cr_proj = by_event.get(comp_eid, {}).get("CommandResult", {}) if comp_eid else {}
        pv_proj = by_event.get(pot_eid, {}).get("PotVersion", {}) if pot_eid else {}
        pb_proj = by_event.get(pot_eid, {}).get("PotBalance", {}) if pot_eid else {}

        t3_cr = cr_proj.get("t3EpochMs")
        t4_cr = cr_proj.get("t4EpochMs")
        t3_to_t4_cr = cr_proj.get("t3ToT4Ms")

        t4_pv = pv_proj.get("t4EpochMs")
        t4_pb = pb_proj.get("t4EpochMs")

        t0_to_t0a = float(max(0, t0a - t0)) if (t0 and t0a) else None
        t0_to_t1 = float(max(0, t1 - t0)) if (t0 and t1) else None
        t2_to_t3_cr = float(max(0, t3_cr - t2)) if (t2 and t3_cr) else None
        t0_to_t4_cr = float(max(0, t4_cr - t0)) if (t0 and t4_cr) else None

        t4_c03 = max(t4_pv, t4_pb) if (t4_pv and t4_pb) else None
        t0_to_t4_c03 = float(max(0, t4_c03 - t0)) if (t0 and t4_c03) else None

        if t0_to_t0a is not None:
            s_t0_to_t0a.append(t0_to_t0a)
        if t0a_to_t1 is not None:
            s_t0a_to_t1.append(float(t0a_to_t1))
        if t0_to_t1 is not None:
            s_t0_to_t1.append(t0_to_t1)
        if t1_to_t2 is not None:
            s_t1_to_t2.append(float(t1_to_t2))
        if t2_to_t3_cr is not None:
            s_t2_to_t3_cr.append(t2_to_t3_cr)
        if t3_to_t4_cr is not None:
            s_t3_to_t4_cr.append(float(t3_to_t4_cr))
        if t0_to_t4_cr is not None:
            s_t0_to_t4_cr.append(t0_to_t4_cr)
        if t0_to_t4_c03 is not None:
            s_t0_to_t4_c03.append(t0_to_t4_c03)

        correlated_records.append(
            {
                "commandId": cid,
                "partition": rec.get("partition"),
                "t0EpochMs": t0,
                "t0aEpochMs": t0a,
                "t1EpochMs": t1,
                "t2EpochMs": t2,
                "t3CommandResultEpochMs": t3_cr,
                "t4CommandResultEpochMs": t4_cr,
                "t4C03ReadyEpochMs": t4_c03,
                "t0ToT0aMs": t0_to_t0a,
                "t0aToT1Ms": t0a_to_t1,
                "t0ToT1Ms": t0_to_t1,
                "t1ToT2Ms": t1_to_t2,
                "t2ToT3CommandResultMs": t2_to_t3_cr,
                "t3ToT4CommandResultMs": t3_to_t4_cr,
                "t0ToT4CommandResultMs": t0_to_t4_cr,
                "t0ToT4C03ReadMs": t0_to_t4_c03,
                "clientE2eMs": d.get("clientE2eMs"),
                "c03Valid": d.get("c03Valid"),
            }
        )

    sql_integrity = db_gen.verify_sql_integrity()

    intervals = {
        "T0_to_T0a_waitBeforeDiscoveryMs": compute_stats(s_t0_to_t0a),
        "T0a_to_T1_discoveryToExecutionMs": compute_stats(s_t0a_to_t1),
        "T0_to_T1_admissionToExecutionMs": compute_stats(s_t0_to_t1),
        "T1_to_T2_commandExecutionMs": compute_stats(s_t1_to_t2),
        "T2_to_T3_waitCommandResultMs": compute_stats(s_t2_to_t3_cr),
        "T3_to_T4_processCommandResultMs": compute_stats(s_t3_to_t4_cr),
        "T0_to_T4_commandResultTotalMs": compute_stats(s_t0_to_t4_cr),
        "T0_to_T4_c03ReadReadyTotalMs": compute_stats(s_t0_to_t4_c03),
        "Client_HTTP_E2E_C03_Ms": compute_stats(s_client_e2e),
    }

    print(
        f"  [{profile} @ {rate_cmd_s} cmd/s] Correlated={len(correlated_records)}/{total_commands} | "
        f"T0->T0a mean={intervals['T0_to_T0a_waitBeforeDiscoveryMs']['mean']}ms p50={intervals['T0_to_T0a_waitBeforeDiscoveryMs']['p50']}ms p95={intervals['T0_to_T0a_waitBeforeDiscoveryMs']['p95']}ms | "
        f"T0->T4(CR) mean={intervals['T0_to_T4_commandResultTotalMs']['mean']}ms p95={intervals['T0_to_T4_commandResultTotalMs']['p95']}ms | "
        f"T0->T4(C03) mean={intervals['T0_to_T4_c03ReadReadyTotalMs']['mean']}ms p95={intervals['T0_to_T4_c03ReadReadyTotalMs']['p95']}ms"
    )

    return {
        "profile": profile,
        "rateCmdPerSec": rate_cmd_s,
        "totalCommands": total_commands,
        "campaignDurationSeconds": campaign_duration_s,
        "workloadDurationSeconds": round(workload_duration, 3),
        "admittedCount": len(admissions),
        "readResultCount": results,
        "admittedPerSecondOverWorkload": len(admissions) / workload_duration,
        "resultsPerSecondOverWorkload": results / workload_duration,
        "admissionHttpMs": compute_stats([d["tAdmitEpochMs"] - d["tSendEpochMs"] for d in admissions]),
        "dispatchFailures": [d for d in dispatched if not d["admitted"]],
        "correlatedCount": len(correlated_records),
        "oracleC03ValidCount": sum(1 for r in correlated_records if r.get("c03Valid")),
        "intervalsMs": intervals,
        "identityRequestQualification": id_qual,
        "sqlIntegrityAudit": sql_integrity,
        "postgresExplainAndIoBefore": pg_before,
        "postgresExplainAndIoAfter": pg_after,
        "prometheusBefore": prom_before,
        "prometheusAfter": prom_after,
        "dockerStats": docker_stats,
        "workloadResourceSamples": sampled,
        "records": correlated_records,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Pocoma V3 A/B Sequential Burnable Discovery Qualification")
    parser.add_argument(
        "--profiles",
        default="H1,H2,H3,H4,H5",
        help="Comma-separated DB profiles to qualify (default: H1,H2,H3,H4,H5)",
    )
    parser.add_argument(
        "--rates",
        default="1,10,50",
        help="Comma-separated rates in cmd/s (default: 1,10,50)",
    )
    parser.add_argument("--output", required=True, type=Path, help="New evidence directory")
    parser.add_argument("--initialize-binding", action="store_true")
    parser.add_argument("--commands", type=int, help="Override sample size for each selected rate")
    parser.add_argument("--sample-resources", action="store_true")
    args = parser.parse_args()
    global EVIDENCE_DIR
    EVIDENCE_DIR = args.output.resolve()

    profiles = [p.strip() for p in args.profiles.split(",") if p.strip()]
    rates = [float(r.strip()) for r in args.rates.split(",") if r.strip()]
    if not profiles or not rates or any(r not in (1, 10, 50) for r in rates):
        parser.error("select known profiles and at least one of 1,10,50 cmd/s")
    if args.commands is not None and args.commands < 1:
        parser.error("--commands must be positive")

    EVIDENCE_DIR.mkdir(parents=True, exist_ok=False)

    token_mgr = OidcTokenManager()
    token = token_mgr.get_token()
    binding_token = fixture_binding(token, args.initialize_binding)

    pot_states = None
    all_campaigns: List[Dict[str, Any]] = []
    profile_seeding_reports: Dict[str, Any] = {}

    for profile in profiles:
        seed_report = db_gen.apply_profile(profile)
        profile_seeding_reports[profile] = seed_report
        (EVIDENCE_DIR / f"db_profile_{profile}.json").write_text(
            json.dumps(seed_report, indent=2), encoding="utf-8"
        )
        print("[Init] Ensuring pool of 25 SPEC-09 medium pots (20 shareholders each, version <= 60)...")
        pot_states = ensure_pot_pool(token_mgr.get_token(), binding_token, min_pots=25)
        print(f"[Init] Ready with {len(pot_states)} SPEC-09 medium pots.")

        # For H1 and H3 (R11 quarantine comparison), run all requested rates (1, 10, 50 cmd/s).
        # For H2, H4, H5, run 1 cmd/s and 10 cmd/s (and 50 cmd/s on H4) to keep campaign duration bounded.
        profile_rates = rates if profile in ("H1", "H3", "H4") else [r for r in rates if r in (1.0, 10.0)]
        for rate in profile_rates:
            total_cmds = args.commands or {1: 30, 10: 60, 50: 100}[rate]
            res = run_workload_campaign(
                profile=profile,
                rate_cmd_s=rate,
                total_commands=total_cmds,
                token_mgr=token_mgr,
                binding_token=binding_token,
                pot_states=pot_states,
                sample_resources=args.sample_resources,
            )
            all_campaigns.append(res)
            (EVIDENCE_DIR / f"campaign_{profile}_rate_{int(rate)}.json").write_text(
                json.dumps(res, indent=2), encoding="utf-8"
            )

    # Reload all campaign_*.json and db_profile_*.json so partial profile runs merge cleanly
    all_campaigns = []
    for p in ["H1", "H2", "H3", "H4", "H5"]:
        p_file = EVIDENCE_DIR / f"db_profile_{p}.json"
        if p_file.exists() and p not in profile_seeding_reports:
            profile_seeding_reports[p] = json.loads(p_file.read_text(encoding="utf-8"))
        for r in [1, 10, 50]:
            c_file = EVIDENCE_DIR / f"campaign_{p}_rate_{r}.json"
            if c_file.exists():
                all_campaigns.append(json.loads(c_file.read_text(encoding="utf-8")))

    # Write summary CSV and JSON
    csv_path = EVIDENCE_DIR / "ab_comparison_summary.csv"
    with open(csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(
            [
                "profile",
                "rate_cmd_s",
                "total_commands",
                "correlated_count",
                "c03_valid_count",
                "t0_to_t0a_mean_ms",
                "t0_to_t0a_p50_ms",
                "t0_to_t0a_p95_ms",
                "t0a_to_t1_mean_ms",
                "t0a_to_t1_p50_ms",
                "t0a_to_t1_p95_ms",
                "t0_to_t1_mean_ms",
                "t0_to_t1_p50_ms",
                "t0_to_t1_p95_ms",
                "t1_to_t2_mean_ms",
                "t1_to_t2_p50_ms",
                "t1_to_t2_p95_ms",
                "t2_to_t3_cr_mean_ms",
                "t2_to_t3_cr_p50_ms",
                "t2_to_t3_cr_p95_ms",
                "t3_to_t4_cr_mean_ms",
                "t3_to_t4_cr_p50_ms",
                "t3_to_t4_cr_p95_ms",
                "t0_to_t4_cr_mean_ms",
                "t0_to_t4_cr_p50_ms",
                "t0_to_t4_cr_p95_ms",
                "t0_to_t4_c03_mean_ms",
                "t0_to_t4_c03_p50_ms",
                "t0_to_t4_c03_p95_ms",
                "client_e2e_c03_p50_ms",
                "client_e2e_c03_p95_ms",
            ]
        )
        for c in all_campaigns:
            iv = c["intervalsMs"]
            writer.writerow(
                [
                    c["profile"],
                    c["rateCmdPerSec"],
                    c["totalCommands"],
                    c["correlatedCount"],
                    c["oracleC03ValidCount"],
                    iv["T0_to_T0a_waitBeforeDiscoveryMs"]["mean"],
                    iv["T0_to_T0a_waitBeforeDiscoveryMs"]["p50"],
                    iv["T0_to_T0a_waitBeforeDiscoveryMs"]["p95"],
                    iv["T0a_to_T1_discoveryToExecutionMs"]["mean"],
                    iv["T0a_to_T1_discoveryToExecutionMs"]["p50"],
                    iv["T0a_to_T1_discoveryToExecutionMs"]["p95"],
                    iv["T0_to_T1_admissionToExecutionMs"]["mean"],
                    iv["T0_to_T1_admissionToExecutionMs"]["p50"],
                    iv["T0_to_T1_admissionToExecutionMs"]["p95"],
                    iv["T1_to_T2_commandExecutionMs"]["mean"],
                    iv["T1_to_T2_commandExecutionMs"]["p50"],
                    iv["T1_to_T2_commandExecutionMs"]["p95"],
                    iv["T2_to_T3_waitCommandResultMs"]["mean"],
                    iv["T2_to_T3_waitCommandResultMs"]["p50"],
                    iv["T2_to_T3_waitCommandResultMs"]["p95"],
                    iv["T3_to_T4_processCommandResultMs"]["mean"],
                    iv["T3_to_T4_processCommandResultMs"]["p50"],
                    iv["T3_to_T4_processCommandResultMs"]["p95"],
                    iv["T0_to_T4_commandResultTotalMs"]["mean"],
                    iv["T0_to_T4_commandResultTotalMs"]["p50"],
                    iv["T0_to_T4_commandResultTotalMs"]["p95"],
                    iv["T0_to_T4_c03ReadReadyTotalMs"]["mean"],
                    iv["T0_to_T4_c03ReadReadyTotalMs"]["p50"],
                    iv["T0_to_T4_c03ReadReadyTotalMs"]["p95"],
                    iv["Client_HTTP_E2E_C03_Ms"]["p50"],
                    iv["Client_HTTP_E2E_C03_Ms"]["p95"],
                ]
            )

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
        "campaigns": [
            {k: v for k, v in c.items() if k != "records"}
            for c in all_campaigns
        ],
    }
    (EVIDENCE_DIR / "ab_qualification_summary.json").write_text(
        json.dumps(summary, indent=2), encoding="utf-8"
    )
    print(f"\n[Done] Wrote summary to {EVIDENCE_DIR / 'ab_qualification_summary.json'} and {csv_path}")


if __name__ == "__main__":
    main()
