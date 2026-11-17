#!/usr/bin/env python3
"""Pocoma V3 — Internal READ oracle probe (scripts/performance/v3-oracle-probe.py).

Independent, strictly READ-ONLY SQL oracles required by C-03 where HTTP is not a proof:
  * DeletePot(D): CurrentPot(P) -> PotVersion(P,D).payload.deleted = true
    (an HTTP 404 alone is never accepted);
  * Balance(P,V): exact row read_pot_balance(P,V), independent of CurrentPot;
  * Balance(P,D) intentionally absent for the terminal version.

`journey` mode runs one bounded functional journey
CreatePot -> AddShareholders(SELF) -> CreateExpense -> DeletePot and records, for each
step, admission -> terminal result, admission -> Balance(P,V) (SQL) and, for DeletePot,
admission -> first internal observation of the terminal deleted version.

Measurement bias: every SQL probe goes through `docker exec psql` (~tens to hundreds of
ms). Latencies from this probe are UPPER BOUNDS for correctness only, never SLO samples.
SQL sessions are opened with default_transaction_read_only=on. No token is printed.
"""

import argparse
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid

REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_DIR = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3"
POSTGRES_CONTAINER = os.environ.get("POSTGRES_CONTAINER", "pocoma-functional-postgres-1")
POSTGRES_USER = os.environ.get("POSTGRES_USER", "pocoma")
POSTGRES_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "pocoma-local-only")
READ_DB = os.environ.get("READ_DB", "pocoma_read")
BASE_URL = os.environ.get("POCOMA_BASE_URL", "http://127.0.0.1:8080")


def sql_read_only(db, query):
    cmd = ["docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
           "-e", "PGOPTIONS=-c default_transaction_read_only=on",
           POSTGRES_CONTAINER, "psql", "-X", "-U", POSTGRES_USER, "-d", db,
           "-v", "ON_ERROR_STOP=1", "-tAc", query]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
    if res.returncode != 0:
        raise RuntimeError(f"read-only SQL failed: {res.stderr.strip()}")
    return res.stdout.strip()


def _uuid(value):
    return str(uuid.UUID(str(value)))  # strict validation, prevents SQL injection


def current_pot_state(pot_id):
    out = sql_read_only(READ_DB, (
        "select c.pot_version, coalesce((v.payload->>'deleted')::boolean,false) "
        "from v3_read.read_current_pot c join v3_read.read_pot_version v "
        "on v.pot_id=c.pot_id and v.pot_version=c.pot_version "
        f"where c.pot_id='{_uuid(pot_id)}'"))
    if not out:
        return None
    version, deleted = out.split("|")
    return {"currentVersion": int(version), "deleted": deleted == "t"}


def balance_exists(pot_id, version):
    out = sql_read_only(READ_DB, (
        "select count(*) from v3_read.read_pot_balance "
        f"where pot_id='{_uuid(pot_id)}' and pot_version={int(version)}"))
    return out == "1"


# Same contract as V3Oracles.validateDeletePotInternal (scripts/k6/lib/v3-oracles.js).
def validate_deletepot_internal(row, terminal_version, http_status=None):
    errors = []
    if not row:
        errors.append(f"No internal READ observation (HTTP {http_status} is not a proof)")
    else:
        if row.get("currentVersion") != terminal_version:
            errors.append(f"CurrentPot at {row.get('currentVersion')}, expected terminal D={terminal_version}")
        if row.get("deleted") is not True:
            errors.append("PotVersion(P,D).deleted is not true")
    return {"ok": not errors, "errors": errors}


def _journey_module():
    spec = importlib.util.spec_from_file_location("demo_journey", REPO_ROOT / "scripts" / "operations" / "run-demo-journey.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def run_journey(timeout_s=10.0, interval_s=0.025):
    j = _journey_module()
    token = j.get_token("bruno", "bruno-local-only")
    st, _, body = j.http_req("GET", f"{BASE_URL}/api/v3/me/binding", token=token)
    if st != 200:
        raise RuntimeError(f"binding unavailable: HTTP {st}")
    binding = json.loads(body.decode())["bindingToken"]

    pot, share, expense = str(uuid.uuid4()), str(uuid.uuid4()), str(uuid.uuid4())
    frac = lambda n, d: {"numerator": str(n), "denominator": str(d)}
    steps = [
        ("CreatePot", {"label": f"Probe {pot[:8]}"}),
        ("AddShareholders", {"targets": [{"id": share, "name": "Owner", "attachment": "SELF", "weight": frac(1, 1)}]}),
        ("CreateExpense", {"id": expense, "label": "Probe expense", "date": "2026-11-06", "amount": frac(10, 3),
                           "payerId": share, "participants": [{"id": share, "weight": None}]}),
        ("DeletePot", {}),
    ]
    version, records, failures = 0, [], []
    for op, payload in steps:
        cmd = {"operation": op, "potId": pot, "observedVersion": version, "bindingToken": binding, "payload": payload}
        st, _, body = j.http_req("POST", f"{BASE_URL}/api/v3/commands", token=token, body=cmd,
                                 headers={"Idempotency-Key": f"probe-{op}-{pot}"})
        t0 = time.monotonic()
        rec = {"operation": op, "observedVersion": version, "admissionStatus": st}
        if st != 202:
            failures.append(f"{op}: admission refused HTTP {st}")
            records.append(rec)
            break
        request_id = json.loads(body.decode())["requestId"]
        result = None
        while time.monotonic() - t0 < timeout_s:
            rs, _, rb = j.http_req("GET", f"{BASE_URL}/api/v3/commands/{request_id}/result", token=token)
            if rs == 200:
                result = json.loads(rb.decode())
                break
            time.sleep(interval_s)
        rec["terminalMs"] = round((time.monotonic() - t0) * 1000, 1) if result else None
        if not result or result.get("outcome") not in ("CHANGED", "COMPLETED") or \
                not isinstance(result.get("resultingPotVersion"), int) or result["resultingPotVersion"] <= version:
            failures.append(f"{op}: no applied new version ({result})")
            rec["result"] = result
            records.append(rec)
            break
        new_version = result["resultingPotVersion"]
        rec["resultingPotVersion"] = new_version
        if op != "DeletePot":
            observed = None
            while time.monotonic() - t0 < timeout_s:
                if balance_exists(pot, new_version):
                    observed = (time.monotonic() - t0) * 1000
                    break
                time.sleep(interval_s)
            rec["balanceSqlUpperBoundMs"] = round(observed, 1) if observed else None
            if observed is None:
                failures.append(f"{op}: Balance(P,{new_version}) not materialized within {timeout_s}s")
        else:
            row = None
            while time.monotonic() - t0 < timeout_s:
                row = current_pot_state(pot)
                if row and row["currentVersion"] == new_version and row["deleted"]:
                    break
                time.sleep(interval_s)
            http_status, _, _ = j.http_req("GET", f"{BASE_URL}/api/v3/pots/{pot}", token=token,
                                           headers={"X-Pocoma-Binding-Token": binding})
            verdict = validate_deletepot_internal(row, new_version, http_status)
            rec["deleteInternalUpperBoundMs"] = round((time.monotonic() - t0) * 1000, 1) if verdict["ok"] else None
            rec["httpStatusAfterDelete"] = http_status
            rec["balanceAtTerminalPresent"] = balance_exists(pot, new_version)
            rec["balanceAtPreviousPresent"] = balance_exists(pot, version)
            if not verdict["ok"]:
                failures.extend(f"DeletePot: {e}" for e in verdict["errors"])
            if http_status != 404:
                failures.append(f"DeletePot: HTTP {http_status} after deletion (expected 404 by contract)")
            if rec["balanceAtTerminalPresent"]:
                failures.append("DeletePot: Balance(P,D) present although intentionally absent")
            if not rec["balanceAtPreviousPresent"]:
                failures.append(f"DeletePot: Balance(P,{version}) missing for active version V<D")
        version = new_version
        records.append(rec)
    return {"potId": pot, "steps": records, "failures": failures, "ok": not failures,
            "bias": "SQL probe via docker exec; upper bounds only, not SLO samples"}


def main():
    ap = argparse.ArgumentParser(description="Pocoma V3 internal READ oracle probe (read-only)")
    ap.add_argument("--out", help="JSON output path")
    sub = ap.add_subparsers(dest="mode", required=True)
    p_journey = sub.add_parser("journey", help="Bounded CreatePot->AddShareholders->CreateExpense->DeletePot journey")
    p_journey.add_argument("--out", help="JSON output path")
    s = sub.add_parser("deletepot", help="Check internal DeletePot materialization")
    s.add_argument("--pot", required=True)
    s.add_argument("--version", type=int, required=True)
    s.add_argument("--out", help="JSON output path")
    b = sub.add_parser("balance", help="Check exact Balance(P,V) presence")
    b.add_argument("--pot", required=True)
    b.add_argument("--version", type=int, required=True)
    b.add_argument("--out", help="JSON output path")
    args = ap.parse_args()

    if args.mode == "journey":
        report = run_journey()
    elif args.mode == "deletepot":
        report = validate_deletepot_internal(current_pot_state(args.pot), args.version)
    else:
        report = {"ok": balance_exists(args.pot, args.version)}
    text = json.dumps(report, indent=2)
    print(text)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text + "\n", encoding="utf-8")
    sys.exit(0 if report.get("ok") else 1)


if __name__ == "__main__":
    main()
