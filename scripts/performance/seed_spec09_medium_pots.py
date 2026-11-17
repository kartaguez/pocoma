#!/usr/bin/env python3
"""Pocoma V3 — SPEC-09 Medium Pots Seeder & Fixture Manifest Generator.

Prepares N independent Pots moyens strictly conforming to SPEC-09:
- 20 shareholders per pot (1 SELF, 19 FREE)
- >= 200 initial expenses (version >= 202)
- Independent, materialized in PostgreSQL write & read schemas.
- Saves manifest to docs/testing/evidence/L6-I3/R11/final-sla/fixtures_manifest.json.
"""

from concurrent.futures import ThreadPoolExecutor, as_completed
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


def run_psql(sql: str) -> str:
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        POSTGRES_CONTAINER, "psql", "-U", POSTGRES_USER, "-d", WRITE_DB, "-tAc", sql
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
    if res.returncode != 0:
        raise RuntimeError(f"psql failed: {res.stderr}")
    return res.stdout.strip()


class PotSeeder:
    def __init__(self, token: str, binding_token: str):
        self.token = token
        self.binding_token = binding_token

    def submit_and_wait(self, body: bytes, idem_key: str, timeout_sec: float = 15.0) -> dict:
        req_id = None
        for attempt in range(25):
            try:
                req = urllib.request.Request(
                    f"{BASE_URL}/api/v3/commands", data=body,
                    headers={
                        "Authorization": f"Bearer {self.token}",
                        "Content-Type": "application/json",
                        "Idempotency-Key": f"{idem_key}-{uuid.uuid4()}"
                    }
                )
                with urllib.request.urlopen(req, timeout=10) as resp:
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

        req_res = urllib.request.Request(
            f"{BASE_URL}/api/v3/commands/{req_id}/result",
            headers={"Authorization": f"Bearer {self.token}"}
        )
        t_start = time.time()
        while time.time() - t_start < timeout_sec:
            try:
                with urllib.request.urlopen(req_res, timeout=5) as resp:
                    if resp.status == 200:
                        data = json.loads(resp.read().decode())
                        if data.get("outcome") in ("COMPLETED", "CHANGED"):
                            return data
                        elif data.get("rejection"):
                            raise RuntimeError(f"Command rejected: {data}")
            except urllib.error.HTTPError as e:
                if e.code != 404:
                    raise
            time.sleep(0.01)
        raise TimeoutError(f"Timeout polling result for {req_id}")

    def create_pot_with_20_shareholders(self, label: str) -> tuple:
        pot_id = str(uuid.uuid4())
        shs = [str(uuid.uuid4()) for _ in range(20)]

        # 1. CreatePot
        b1 = json.dumps({
            "operation": "CreatePot", "potId": pot_id, "observedVersion": 0,
            "bindingToken": self.binding_token, "payload": {"label": label}
        }).encode()
        r1 = self.submit_and_wait(b1, f"cp-{pot_id}")
        v = r1["resultingPotVersion"]

        # 2. AddShareholders
        targets = [
            {"id": sid, "name": f"U{i}", "attachment": "SELF" if i == 0 else "FREE",
             "weight": {"numerator": "1", "denominator": "1"}}
            for i, sid in enumerate(shs)
        ]
        b2 = json.dumps({
            "operation": "AddShareholders", "potId": pot_id, "observedVersion": v,
            "bindingToken": self.binding_token, "payload": {"targets": targets}
        }).encode()
        r2 = self.submit_and_wait(b2, f"as-{pot_id}")
        v = r2["resultingPotVersion"]
        return pot_id, shs, v

    def advance_pot_to_version(self, pot_id: str, shs: list, current_v: int, target_v: int = 202):
        v = current_v
        payer = shs[0]
        while v < target_v:
            exp_body = json.dumps({
                "operation": "CreateExpense", "potId": pot_id, "observedVersion": v,
                "bindingToken": self.binding_token,
                "payload": {
                    "id": str(uuid.uuid4()), "label": f"init-exp-{v}", "date": "2026-11-08",
                    "amount": {"numerator": "100", "denominator": "1"},
                    "payerId": payer, "participants": [{"id": sid, "weight": None} for sid in shs]
                }
            }).encode()
            r = self.submit_and_wait(exp_body, f"exp-{v}-{pot_id}")
            v = r["resultingPotVersion"]
        return v


def main():
    token_mgr = OidcTokenManager()
    token = token_mgr.get_token()

    req = urllib.request.Request(f"{BASE_URL}/api/v3/me/binding", headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req) as resp:
        binding_token = json.loads(resp.read().decode())["bindingToken"]

    seeder = PotSeeder(token, binding_token)

    # 1. Discover existing pots with 20 shareholders
    sql_existing = """
    SELECT p.pot_id, count(DISTINCT s.object_id) as shareholders, coalesce(max(v.version), 0) as max_version
    FROM v3_write.v3_pot_identity p
    JOIN v3_write.v3_shareholder_identity s ON s.pot_id = p.pot_id
    LEFT JOIN v3_write.v3_pot_version v ON v.pot_id = p.pot_id
    GROUP BY p.pot_id
    HAVING count(DISTINCT s.object_id) = 20
    ORDER BY max_version DESC;
    """
    lines = run_psql(sql_existing).split("\n")
    existing_pots = []
    for line in lines:
        if line.strip():
            parts = line.strip().split("|")
            pid, sh_count, max_v = parts[0], int(parts[1]), int(parts[2])
            # Fetch shareholder IDs
            sh_sql = f"SELECT object_id FROM v3_write.v3_shareholder_identity WHERE pot_id = '{pid}' ORDER BY object_id;"
            sh_ids = [s.strip() for s in run_psql(sh_sql).split("\n") if s.strip()]
            existing_pots.append({"potId": pid, "shareholders": sh_ids, "version": max_v})

    print(f"Found {len(existing_pots)} existing pots with 20 shareholders.")

    TARGET_POTS = 10
    TARGET_VERSION = 202

    # Advance existing pots in parallel
    pots_to_advance = [p for p in existing_pots if p["version"] < TARGET_VERSION]
    print(f"Pots needing advancement to v{TARGET_VERSION}: {len(pots_to_advance)}")

    def advance_worker(pot):
        t0 = time.time()
        start_v = pot["version"]
        final_v = seeder.advance_pot_to_version(pot["potId"], pot["shareholders"], start_v, TARGET_VERSION)
        pot["version"] = final_v
        print(f"  Advanced {pot['potId'][:8]} from v{start_v} to v{final_v} in {time.time()-t0:.1f}s")
        return pot

    with ThreadPoolExecutor(max_workers=min(8, max(1, len(pots_to_advance)))) as executor:
        futures = [executor.submit(advance_worker, p) for p in pots_to_advance]
        for f in as_completed(futures):
            f.result()

    qualified_pots = [p for p in existing_pots if p["version"] >= TARGET_VERSION]
    print(f"Qualified existing pots (>= v{TARGET_VERSION}): {len(qualified_pots)}")

    # Create new pots if needed
    needed_new = TARGET_POTS - len(qualified_pots)
    if needed_new > 0:
        print(f"Creating {needed_new} new pots with 20 shareholders and 200 expenses...")
        def create_worker(idx):
            t0 = time.time()
            pid, shs, v = seeder.create_pot_with_20_shareholders(f"spec09-med-{idx}")
            final_v = seeder.advance_pot_to_version(pid, shs, v, TARGET_VERSION)
            print(f"  Created new pot {pid[:8]} with v{final_v} in {time.time()-t0:.1f}s")
            return {"potId": pid, "shareholders": shs, "version": final_v}

        with ThreadPoolExecutor(max_workers=min(4, needed_new)) as executor:
            futures = [executor.submit(create_worker, i) for i in range(needed_new)]
            for f in as_completed(futures):
                qualified_pots.append(f.result())

    print(f"\nFinal Qualified Pot Pool Size: {len(qualified_pots)}")

    # Verify and write manifest
    MANIFEST_DIR.mkdir(parents=True, exist_ok=True)
    manifest_file = MANIFEST_DIR / "fixtures_manifest.json"

    manifest_data = {
        "profile": "SPEC-09 Medium (Pots moyens: 20 shareholders, >= 200 initial expenses)",
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "totalPots": len(qualified_pots),
        "pots": qualified_pots
    }

    with open(manifest_file, "w") as f:
        json.dump(manifest_data, f, indent=2)
    print(f"Wrote fixture manifest to {manifest_file}")


if __name__ == "__main__":
    main()
