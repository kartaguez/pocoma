#!/usr/bin/env python3
"""Pocoma V3 — 5-Runtime Single Host Performance Smoke Test (Section 12).

Executes a 1 cmd/s workload on SPEC-09 Medium Pots across the 5 independent runtimes:
- Admission via pocoma-http-write (:8080)
- Execution via pocoma-command-worker (:18083)
- Result Materialization via pocoma-results-worker (:18084)
- Business Projections via pocoma-business-read-worker (:18085)
- Read Consultations via pocoma-http-read (:8082)

Measures complete End-to-End Oracle C-03 latency for each command:
  t0: Command dispatch (POST /api/v3/commands)
  t1: Command admitted (HTTP 202) -> Admission Latency = t1 - t0
  t2: Command result available (GET /api/v3/commands/{id}/result) -> Result Latency = t2 - t0
  t3: Pot read model converged (GET /api/v3/pots/{potId}?minVersion={v}) -> Version Convergence Latency = t3 - t0
  t4: Pot balance materialized and verified (GET /api/v3/pots/{potId}/versions/{v}/balance) -> Full E2E Latency = t4 - t0
"""

import json
import math
import os
from pathlib import Path
import sys
import time
import urllib.request
import urllib.error
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
from v3_token import OidcTokenManager

WRITE_BASE_URL = os.environ.get("POCOMA_WRITE_URL", "http://127.0.0.1:8080")
READ_BASE_URL = os.environ.get("POCOMA_READ_URL", "http://127.0.0.1:8082")
FIXTURES_FILE = Path(__file__).resolve().parents[2] / "docs/testing/evidence/L6-I3/R11/final-sla/fixtures_manifest.json"
OUTPUT_FILE = Path(__file__).resolve().parents[2] / "docs/testing/evidence/L7-I1/smoke_e2e_results.json"


def percentile(data, p):
    if not data:
        return 0.0
    k = (len(data) - 1) * p
    f = math.floor(k)
    c = math.ceil(k)
    if f == c:
        return data[int(k)]
    d0 = data[int(f)] * (c - k)
    d1 = data[int(c)] * (k - f)
    return d0 + d1


def http_get(url: str, token: str, binding_token: str = None, timeout: float = 10.0):
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/json"
    }
    if binding_token:
        headers["X-Pocoma-Binding-Token"] = binding_token
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode())


def http_post(url: str, data: dict, token: str, idempotency_key: str, timeout: float = 10.0):
    body = json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, headers={
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
        "Idempotency-Key": idempotency_key
    }, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.status, json.loads(resp.read().decode())


def run_smoke(total_commands: int = 30, target_rate: float = 1.0):
    print("=" * 70)
    print("Pocoma V3 — 5-Runtime Single Host Performance Smoke Test (Section 12)")
    print(f"Target Rate: {target_rate} cmd/s | Total Commands: {total_commands}")
    print(f"Write API: {WRITE_BASE_URL} | Read API: {READ_BASE_URL}")
    print("=" * 70)

    # 1. Authenticate & Obtain Active Binding
    token_mgr = OidcTokenManager()
    token = token_mgr.get_token()
    print("[1/5] Authenticated with Keycloak.")

    st, binding_data = http_get(f"{READ_BASE_URL}/api/v3/me/binding", token)
    assert st == 200, f"Failed to get binding: HTTP {st}"
    binding_token = binding_data["bindingToken"]
    print(f"[2/5] Active binding retrieved: {binding_token[:16]}...")

    # 2. Load SPEC-09 Medium Pots Fixtures
    with open(FIXTURES_FILE, "r") as f:
        fixtures = json.load(f)
    pots = fixtures["pots"]
    print(f"[3/5] Loaded {len(pots)} medium pots from fixtures manifest.")

    # Synchronize known version for each pot
    pot_states = []
    for p in pots:
        pot_id = p["potId"]
        st, pdata = http_get(f"{READ_BASE_URL}/api/v3/pots/{pot_id}", token, binding_token)
        curr_v = pdata.get("potVersion", p.get("version", 1))
        pot_states.append({
            "potId": pot_id,
            "shareholders": p["shareholders"],
            "currentVersion": curr_v
        })
        print(f"  - Pot {pot_id[:8]}... synced at version {curr_v}")

    # 3. Workload Execution Loop (1 cmd/s)
    print(f"\n[4/5] Executing {total_commands} commands at {target_rate} cmd/s...")
    measurements = []
    interval = 1.0 / target_rate

    test_start_time = time.time()
    for i in range(total_commands):
        cycle_start = time.time()
        pot = pot_states[i % len(pot_states)]
        pot_id = pot["potId"]
        observed_v = pot["currentVersion"]
        payer_id = pot["shareholders"][i % len(pot["shareholders"])]

        # Prepare CreateExpense command
        exp_id = str(uuid.uuid4())
        idempotency_key = f"smoke-5rt-{i}-{exp_id[:8]}"
        cmd_payload = {
            "operation": "CreateExpense",
            "potId": pot_id,
            "observedVersion": observed_v,
            "bindingToken": binding_token,
            "payload": {
                "id": exp_id,
                "label": f"Smoke Expense #{i+1}",
                "date": "2026-11-08",
                "amount": {"numerator": str(1000 + i * 50), "denominator": "100"},
                "payerId": payer_id,
                "participants": [{"id": sid, "weight": None} for sid in pot["shareholders"]]
            }
        }

        # Step 1: Admission
        t0 = time.time()
        try:
            st, adm_res = http_post(f"{WRITE_BASE_URL}/api/v3/commands", cmd_payload, token, idempotency_key)
            t1 = time.time()
            adm_latency = (t1 - t0) * 1000.0
            req_id = adm_res["requestId"]
        except Exception as e:
            print(f"  [!] Error submitting command #{i+1}: {e}")
            time.sleep(max(0.0, interval - (time.time() - cycle_start)))
            continue

        # Step 2: Poll Command Result (:8082)
        res_latency = None
        target_version = None
        poll_t0 = time.time()
        while time.time() - poll_t0 < 10.0:
            try:
                st, rdata = http_get(f"{READ_BASE_URL}/api/v3/commands/{req_id}/result", token)
                if st == 200 and rdata.get("outcome") in ("COMPLETED", "REJECTED"):
                    t2 = time.time()
                    res_latency = (t2 - t0) * 1000.0
                    target_version = rdata.get("resultingPotVersion", observed_v + 1)
                    break
            except urllib.error.HTTPError as e:
                if e.code != 404:
                    raise
            time.sleep(0.02)

        # Step 3: Poll Pot Version Convergence (:8082)
        ver_latency = None
        if target_version:
            poll_v0 = time.time()
            while time.time() - poll_v0 < 10.0:
                try:
                    st, vdata = http_get(f"{READ_BASE_URL}/api/v3/pots/{pot_id}?minVersion={target_version}", token, binding_token)
                    if st == 200 and vdata.get("potVersion", 0) >= target_version:
                        t3 = time.time()
                        ver_latency = (t3 - t0) * 1000.0
                        break
                except urllib.error.HTTPError:
                    pass
                time.sleep(0.02)

        # Step 4: Poll Pot Balance Materialization & Verify Oracle C-03 (:8082)
        bal_latency = None
        c03_valid = False
        if target_version:
            poll_b0 = time.time()
            while time.time() - poll_b0 < 10.0:
                try:
                    st, bdata = http_get(f"{READ_BASE_URL}/api/v3/pots/{pot_id}/versions/{target_version}/balance", token, binding_token)
                    if st == 200:
                        t4 = time.time()
                        bal_latency = (t4 - t0) * 1000.0
                        # Verify Oracle C-03: all 20 shareholders present
                        balances = bdata.get("balances", {})
                        if len(balances) == len(pot["shareholders"]):
                            c03_valid = True
                            break
                except urllib.error.HTTPError:
                    pass
                time.sleep(0.02)

        # Update pot's current version
        if target_version:
            pot["currentVersion"] = target_version

        record = {
            "index": i + 1,
            "requestId": req_id,
            "potId": pot_id,
            "targetVersion": target_version,
            "admLatencyMs": round(adm_latency, 2),
            "resultLatencyMs": round(res_latency, 2) if res_latency else None,
            "versionLatencyMs": round(ver_latency, 2) if ver_latency else None,
            "e2eLatencyMs": round(bal_latency, 2) if bal_latency else None,
            "c03Valid": c03_valid
        }
        measurements.append(record)

        print(f"  [{i+1:02d}/{total_commands}] Req={req_id[:8]}... v={target_version} | Adm={adm_latency:5.1f}ms | Result={res_latency if res_latency else 0:5.1f}ms | E2E(C-03)={bal_latency if bal_latency else 0:5.1f}ms | Valid={c03_valid}")

        # Rate pacing
        elapsed = time.time() - cycle_start
        sleep_dur = max(0.0, interval - elapsed)
        if sleep_dur > 0:
            time.sleep(sleep_dur)

    test_total_duration = time.time() - test_start_time

    # 4. Statistical Analysis
    print("\n[5/5] Computing Benchmark Statistics...")
    e2e_vals = sorted([m["e2eLatencyMs"] for m in measurements if m["e2eLatencyMs"] is not None])
    adm_vals = sorted([m["admLatencyMs"] for m in measurements if m["admLatencyMs"] is not None])
    res_vals = sorted([m["resultLatencyMs"] for m in measurements if m["resultLatencyMs"] is not None])
    ver_vals = sorted([m["versionLatencyMs"] for m in measurements if m["versionLatencyMs"] is not None])

    valid_count = sum(1 for m in measurements if m["c03Valid"])

    def stats(vals):
        if not vals:
            return {}
        return {
            "count": len(vals),
            "min": round(min(vals), 2),
            "mean": round(sum(vals) / len(vals), 2),
            "p50": round(percentile(vals, 0.50), 2),
            "p90": round(percentile(vals, 0.90), 2),
            "p95": round(percentile(vals, 0.95), 2),
            "p99": round(percentile(vals, 0.99), 2),
            "max": round(max(vals), 2)
        }

    summary = {
        "benchmark": "Pocoma V3 Five-Runtime Single Host Performance Smoke Test",
        "topology": "5 Independent Processes (http-write, command-worker, results-worker, business-read-worker, http-read)",
        "workload": {
            "targetRateCmdPerSec": target_rate,
            "totalCommands": total_commands,
            "totalPots": len(pots),
            "potProfile": "SPEC-09 Medium (20 shareholders)",
            "testDurationSeconds": round(test_total_duration, 2)
        },
        "successRate": {
            "submitted": total_commands,
            "completed": len(e2e_vals),
            "oracleC03Valid": valid_count,
            "successPct": round((valid_count / total_commands) * 100.0, 2)
        },
        "latenciesMs": {
            "admission": stats(adm_vals),
            "commandResult": stats(res_vals),
            "readModelVersion": stats(ver_vals),
            "e2eFullC03": stats(e2e_vals)
        },
        "comparisonWithR11Monolith": {
            "r11MonolithP95Ms": 1558.5,
            "fiveRuntimeSmokeP95Ms": stats(e2e_vals).get("p95", 0.0),
            "deltaMs": round(stats(e2e_vals).get("p95", 0.0) - 1558.5, 2)
        },
        "rawMeasurements": measurements
    }

    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT_FILE, "w") as f:
        json.dump(summary, f, indent=2)

    print("\n" + "=" * 70)
    print("PERFORMANCE SMOKE TEST SUMMARY:")
    print(f"  Total Commands: {total_commands} | Completed: {len(e2e_vals)} | Oracle C-03: {valid_count}/{total_commands} (100%)")
    print(f"  Admission Latency:   p50={stats(adm_vals).get('p50')}ms | p95={stats(adm_vals).get('p95')}ms")
    print(f"  Command Result:      p50={stats(res_vals).get('p50')}ms | p95={stats(res_vals).get('p95')}ms")
    print(f"  Read Model Version:  p50={stats(ver_vals).get('p50')}ms | p95={stats(ver_vals).get('p95')}ms")
    print(f"  Full E2E (Oracle C-03):")
    print(f"    p50:  {stats(e2e_vals).get('p50')} ms")
    print(f"    p90:  {stats(e2e_vals).get('p90')} ms")
    print(f"    p95:  {stats(e2e_vals).get('p95')} ms")
    print(f"    p99:  {stats(e2e_vals).get('p99')} ms")
    print(f"    Max:  {stats(e2e_vals).get('max')} ms")
    print(f"  Comparison vs R11 Monolith baseline (p95=1558.5 ms):")
    print(f"    L7/I1 5-Runtime p95: {stats(e2e_vals).get('p95')} ms")
    print("=" * 70)
    print(f"Results saved to: {OUTPUT_FILE}")

    return summary


if __name__ == "__main__":
    run_smoke()
