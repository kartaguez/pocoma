#!/usr/bin/env python3
"""Pocoma V3 — Reliable Sequential/Parallel Pot Advancer & Fixture Manifest Generator.

Ensures all 10 pots with 20 shareholders reach >= 202 versions (SPEC-09 Medium Pots).
Saves manifest to docs/testing/evidence/L6-I3/R11/final-sla/fixtures_manifest.json.
"""

import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "scripts" / "performance"))
from v3_token import OidcTokenManager

BASE_URL = os.environ.get("POCOMA_BASE_URL", "http://127.0.0.1:8080")
POSTGRES_CONTAINER = os.environ.get("POSTGRES_CONTAINER", "pocoma-functional-postgres-1")
POSTGRES_USER = os.environ.get("POSTGRES_USER", "pocoma")
POSTGRES_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "pocoma-local-only")
WRITE_DB = os.environ.get("WRITE_DB", "pocoma_write")
MANIFEST_DIR = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3" / "R11" / "final-sla"
TARGET_VERSION = 202


def run_psql(sql: str) -> str:
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        POSTGRES_CONTAINER, "psql", "-U", POSTGRES_USER, "-d", WRITE_DB, "-tAc", sql
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    if res.returncode != 0:
        raise RuntimeError(f"psql failed: {res.stderr}")
    return res.stdout.strip()


def get_token_and_binding(token_mgr):
    token = token_mgr.get_token()
    for _ in range(10):
        try:
            req = urllib.request.Request(f"{BASE_URL}/api/v3/me/binding", headers={"Authorization": f"Bearer {token}"})
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    bt = json.loads(resp.read().decode())["bindingToken"]
                    return token, bt
        except Exception:
            time.sleep(0.1)
    raise RuntimeError("Failed to obtain binding token")


def get_current_versions():
    sql = """
    SELECT p.pot_id, count(DISTINCT s.object_id) as shareholders, coalesce(max(v.version), 0) as max_version
    FROM v3_write.v3_pot_identity p
    JOIN v3_write.v3_shareholder_identity s ON s.pot_id = p.pot_id
    LEFT JOIN v3_write.v3_pot_version v ON v.pot_id = p.pot_id
    GROUP BY p.pot_id
    HAVING count(DISTINCT s.object_id) = 20
    ORDER BY max_version ASC;
    """
    pots = []
    for line in run_psql(sql).split("\n"):
        if line.strip():
            parts = line.strip().split("|")
            pots.append({"potId": parts[0], "shareholders_count": int(parts[1]), "version": int(parts[2])})
    return pots


def advance_one_expense(token, binding_token, pot_id, current_v, sh_ids):
    exp_body = json.dumps({
        "operation": "CreateExpense", "potId": pot_id, "observedVersion": current_v,
        "bindingToken": binding_token,
        "payload": {
            "id": str(uuid.uuid4()), "label": f"adv-{current_v}", "date": "2026-11-08",
            "amount": {"numerator": "100", "denominator": "1"},
            "payerId": sh_ids[0], "participants": [{"id": sid, "weight": None} for sid in sh_ids]
        }
    }).encode()

    req_id = None
    for attempt in range(25):
        try:
            req_cmd = urllib.request.Request(
                f"{BASE_URL}/api/v3/commands", data=exp_body,
                headers={
                    "Authorization": f"Bearer {token}",
                    "Content-Type": "application/json",
                    "Idempotency-Key": f"adv-{current_v}-{pot_id}-{uuid.uuid4()}"
                }
            )
            with urllib.request.urlopen(req_cmd, timeout=10) as resp:
                if resp.status == 202:
                    req_id = json.loads(resp.read().decode())["requestId"]
                    break
        except urllib.error.HTTPError as e:
            if e.code in (503, 409) and attempt < 24:
                time.sleep(0.02 * (1 + attempt))
                continue
            raise

    if not req_id:
        raise RuntimeError("Failed to admit command")

    t0 = time.time()
    while time.time() - t0 < 30.0:
        try:
            req_res = urllib.request.Request(
                f"{BASE_URL}/api/v3/commands/{req_id}/result",
                headers={"Authorization": f"Bearer {token}"}
            )
            with urllib.request.urlopen(req_res, timeout=5) as resp:
                if resp.status == 200:
                    d = json.loads(resp.read().decode())
                    if d.get("outcome") in ("COMPLETED", "CHANGED"):
                        return d["resultingPotVersion"]
                    elif d.get("rejection"):
                        raise RuntimeError(f"Command rejected: {d}")
        except urllib.error.HTTPError as e:
            if e.code != 404:
                pass
        time.sleep(0.01)
    raise TimeoutError(f"Timeout on result {req_id}")


def main():
    token_mgr = OidcTokenManager()
    token, binding_token = get_token_and_binding(token_mgr)

    pots = get_current_versions()
    print(f"Found {len(pots)} pots with 20 shareholders:")
    for p in pots:
        print(f"  {p['potId'][:8]}: v={p['version']}")

    # Pre-fetch shareholders for all pots
    sh_map = {}
    for p in pots:
        pid = p["potId"]
        sh_sql = f"SELECT object_id FROM v3_write.v3_shareholder_identity WHERE pot_id = '{pid}' ORDER BY object_id;"
        sh_map[pid] = [s.strip() for s in run_psql(sh_sql).split("\n") if s.strip()]

    # Main advance loop: loop through pots that need advancement until all >= TARGET_VERSION
    while True:
        pots = get_current_versions()
        needed = [p for p in pots if p["version"] < TARGET_VERSION]
        if not needed:
            print(f"\nAll {len(pots)} pots have reached >= v{TARGET_VERSION}!")
            break

        print(f"\n--- Advance Cycle: {len(needed)} pots still < v{TARGET_VERSION} ---")
        for p in needed:
            pid = p["potId"]
            current_v = p["version"]
            # Refresh token if needed
            token = token_mgr.get_token()
            try:
                new_v = advance_one_expense(token, binding_token, pid, current_v, sh_map[pid])
                print(f"  {pid[:8]}: v{current_v} -> v{new_v}")
            except Exception as e:
                print(f"  {pid[:8]}: error advancing from v{current_v}: {e}")
                time.sleep(0.5)

    # All qualified! Write fixture manifest
    final_pots = get_current_versions()
    qualified = [p for p in final_pots if p["version"] >= TARGET_VERSION]

    manifest_pots = []
    for p in qualified:
        pid = p["potId"]
        manifest_pots.append({
            "potId": pid,
            "shareholders": sh_map[pid],
            "version": p["version"]
        })

    MANIFEST_DIR.mkdir(parents=True, exist_ok=True)
    manifest_file = MANIFEST_DIR / "fixtures_manifest.json"
    manifest_data = {
        "profile": "SPEC-09 Medium (Pots moyens: 20 shareholders, >= 200 initial expenses)",
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "totalPots": len(manifest_pots),
        "pots": manifest_pots
    }

    with open(manifest_file, "w") as f:
        json.dump(manifest_data, f, indent=2)
    print(f"\nFixture manifest written successfully to: {manifest_file}")


if __name__ == "__main__":
    main()
