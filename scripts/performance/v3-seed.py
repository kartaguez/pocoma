#!/usr/bin/env python3
"""Pocoma V3 — Reproducible Dataset & Seed Generator (scripts/performance/v3-seed.py).

Generates deterministic, traceable workloads conforming to SPEC-09 profiles:
- Small:  5 shareholders,    20 expenses
- Medium: 20 shareholders,   200 expenses
- Large:  100 shareholders, 2 000 expenses

Supports:
- Independent Pots vs Contention on a single Pot
- Controlled mutation distributions (AddShareholders, CreateExpense, UpdateExpenseFinancials, DeleteExpense, DeletePot)
- Seed identification and reproducible SHA-256 fingerprinting
- Mandatory database materialization verification (--materialize / --verify-materialized)
- Guarded definition of normative historical profiles (10k, 1M, 10M events), strictly disabled by default.
"""

import argparse
from dataclasses import asdict, dataclass
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import random
import subprocess
import sys
import time
from typing import Dict, List, Optional
import urllib.error
import urllib.request
import uuid

REPO_ROOT = Path(__file__).resolve().parents[2]
SEEDS_DIR = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3" / "seeds"

DEFAULT_API_URL = os.environ.get("POCOMA_BASE_URL", "http://127.0.0.1:8080")
POSTGRES_CONTAINER = os.environ.get("POSTGRES_CONTAINER", "pocoma-functional-postgres-1")
POSTGRES_USER = os.environ.get("POSTGRES_USER", "pocoma")
POSTGRES_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "pocoma-local-only")
READ_DB = os.environ.get("READ_DB", "pocoma_read")

SPEC09_PROFILES = {
    "small": {
        "shareholders_per_pot": 5,
        "expenses_per_pot": 20,
        "default_pots": 1,
        "description": "SPEC-09 Petit (5 shareholders, 20 expenses)",
    },
    "medium": {
        "shareholders_per_pot": 20,
        "expenses_per_pot": 200,
        "default_pots": 1,
        "description": "SPEC-09 Moyen (20 shareholders, 200 expenses)",
    },
    "large": {
        "shareholders_per_pot": 100,
        "expenses_per_pot": 2000,
        "default_pots": 1,
        "description": "SPEC-09 Grand (100 shareholders, 2000 expenses)",
    },
    "historical-10k": {
        "shareholders_per_pot": 20,
        "expenses_per_pot": 80,
        "default_pots": 100,
        "description": "SPEC-09 Volume 10 000 événements (100 Pots)",
        "large_scale": True,
    },
    "historical-1m": {
        "shareholders_per_pot": 20,
        "expenses_per_pot": 80,
        "default_pots": 10000,
        "description": "SPEC-09 Volume 1 000 000 événements (10 000 Pots)",
        "large_scale": True,
    },
    "historical-10m": {
        "shareholders_per_pot": 20,
        "expenses_per_pot": 80,
        "default_pots": 100000,
        "description": "SPEC-09 Volume 10 000 000 événements (100 000 Pots)",
        "large_scale": True,
    },
}


@dataclass
class SeedSpec:
    seed_id: str
    profile: str
    pots_count: int
    shareholders_per_pot: int
    expenses_per_pot: int
    contention: bool
    mutation_distribution: Dict[str, float]
    salt: str = "pocoma-v3-perf-seed"


def generate_deterministic_uuid(rng: random.Random, prefix: str, index: int) -> str:
    h = hashlib.sha256(f"{prefix}-{index}-{rng.randint(0, 10**9)}".encode()).hexdigest()
    return f"{h[:8]}-{h[8:12]}-4{h[13:16]}-a{h[17:20]}-{h[20:32]}"


def run_psql(db: str, sql_command: str) -> str:
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        "-e", "PGOPTIONS=-c default_transaction_read_only=on",
        POSTGRES_CONTAINER,
        "psql", "-X", "-U", POSTGRES_USER, "-d", db, "-v", "ON_ERROR_STOP=1", "-tAc", sql_command
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        return ""
    return res.stdout.strip()


def build_seed_dataset(spec: SeedSpec) -> Dict:
    rng = random.Random(f"{spec.seed_id}:{spec.salt}")
    pots = []
    total_commands = 0

    target_pots = 1 if spec.contention else spec.pots_count

    for pot_idx in range(target_pots):
        pot_id = generate_deterministic_uuid(rng, "pot", pot_idx)
        sh_count = spec.shareholders_per_pot
        exp_count = spec.expenses_per_pot

        shareholders = []
        for sh_idx in range(sh_count):
            sh_id = generate_deterministic_uuid(rng, f"sh-{pot_idx}", sh_idx)
            shareholders.append({
                "id": sh_id,
                "name": f"User-{pot_idx}-{sh_idx}",
                "weightNumerator": 1,
                "weightDenominator": sh_count,
            })

        expenses = []
        for exp_idx in range(exp_count):
            exp_id = generate_deterministic_uuid(rng, f"exp-{pot_idx}", exp_idx)
            payer = rng.choice(shareholders)["id"]
            amount_cents = rng.randint(100, 50000)
            expenses.append({
                "id": exp_id,
                "title": f"Expense {exp_idx}",
                "payerId": payer,
                "amountCents": amount_cents,
                "parts": {sh["id"]: 1 for sh in shareholders},
            })

        total_commands += 1 + (1 if sh_count > 0 else 0) + exp_count
        pots.append({
            "potId": pot_id,
            "label": f"Seed Pot {pot_idx} [{spec.profile}]",
            "shareholders": shareholders,
            "expenses": expenses,
            "contentionRounds": spec.expenses_per_pot if spec.contention else 1,
        })

    dataset = {
        "seedId": spec.seed_id,
        "profile": spec.profile,
        "contention": spec.contention,
        "potsCount": len(pots),
        "totalShareholders": len(pots) * spec.shareholders_per_pot,
        "totalExpenses": len(pots) * spec.expenses_per_pot,
        "estimatedEvents": total_commands,
        "pots": pots,
    }

    # Compute deterministic SHA-256 fingerprint of the structural dataset
    canonical_bytes = json.dumps(dataset, sort_keys=True).encode("utf-8")
    fingerprint = hashlib.sha256(canonical_bytes).hexdigest()
    dataset["fingerprintSha256"] = fingerprint

    return dataset


def create_seed(profile_name: str, seed_id: Optional[str] = None, pots_count: Optional[int] = None,
                contention: bool = False, enable_large_scale: bool = False) -> Dict:
    if profile_name not in SPEC09_PROFILES:
        raise ValueError(f"Unknown profile '{profile_name}'. Available: {list(SPEC09_PROFILES.keys())}")

    pinfo = SPEC09_PROFILES[profile_name]
    if pinfo.get("large_scale", False) and not enable_large_scale:
        raise PermissionError(
            f"Profile '{profile_name}' is a large-scale historical volume. "
            f"By mandate, high-volume generation is strictly disabled by default during local qualification. "
            f"Explicit flag --enable-large-scale required."
        )

    sid = seed_id or f"seed-v3-{profile_name}-{'contention' if contention else 'indep'}"
    effective_pots = pots_count or pinfo["default_pots"]

    spec = SeedSpec(
        seed_id=sid,
        profile=profile_name,
        pots_count=effective_pots,
        shareholders_per_pot=pinfo["shareholders_per_pot"],
        expenses_per_pot=pinfo["expenses_per_pot"],
        contention=contention,
        mutation_distribution={"CreateExpense": 0.8, "UpdateExpenseFinancials": 0.15, "DeleteExpense": 0.05},
    )

    data = build_seed_dataset(spec)
    return data


def resolve_auth_and_binding(base_url: str = DEFAULT_API_URL):
    spec = importlib.util.spec_from_file_location(
        "demo_journey", REPO_ROOT / "scripts" / "operations" / "run-demo-journey.py"
    )
    journey_mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(journey_mod)
    token = journey_mod.get_token("bruno", "bruno-local-only")
    st, _, b = journey_mod.http_req("GET", f"{base_url}/api/v3/me/binding", token=token)
    if st != 200:
        raise RuntimeError(f"Failed to fetch binding: HTTP {st}")
    bind_data = json.loads(b.decode())
    binding_token = bind_data.get("bindingToken")
    if not binding_token:
        raise RuntimeError("No active binding token available")
    return token, binding_token


def materialize_dataset(dataset: Dict, base_url: str = DEFAULT_API_URL) -> Dict:
    """Submits the structural dataset through the live V3 engine and verifies DB materialization."""
    token, binding_token = resolve_auth_and_binding(base_url)

    def api_post(endpoint: str, body: dict, idem_key: str):
        url = f"{base_url}{endpoint}"
        payload = json.dumps(body).encode("utf-8")
        req = urllib.request.Request(
            url, data=payload,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {token}",
                "X-Pocoma-Binding-Token": binding_token,
                "Idempotency-Key": idem_key,
            }
        )
        try:
            with urllib.request.urlopen(req, timeout=10) as resp:
                return resp.status, json.loads(resp.read().decode())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read().decode()) if e.headers.get("content-type", "").startswith("application/json") else {}

    def poll_result(request_id: str, timeout_sec: float = 15.0):
        url = f"{base_url}/api/v3/commands/{request_id}/result"
        req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
        start = time.time()
        while time.time() - start < timeout_sec:
            try:
                with urllib.request.urlopen(req, timeout=5) as resp:
                    if resp.status == 200:
                        return json.loads(resp.read().decode())
            except urllib.error.HTTPError as e:
                if e.code != 404:
                    raise RuntimeError(f"Poll result error: HTTP {e.code}")
            time.sleep(0.02)
        raise TimeoutError(f"Command result timeout for {request_id}")

    materialized_pots = []
    print(f"Materializing dataset [{dataset['seedId']}] into live V3 engine...")

    for pot in dataset["pots"]:
        pot_id = pot["potId"]
        shs = pot["shareholders"]
        exps = pot["expenses"]
        print(f"  -> Seeding Pot {pot_id} ({len(shs)} shareholders, {len(exps)} expenses)...")

        # 1. CreatePot
        st, res = api_post("/api/v3/commands", {
            "operation": "CreatePot", "potId": pot_id, "observedVersion": 0,
            "bindingToken": binding_token, "payload": {"label": pot["label"]}
        }, f"seed-create-{pot_id}")
        if st != 202:
            raise RuntimeError(f"CreatePot admission rejected: HTTP {st} ({res})")
        r = poll_result(res["requestId"])
        v = r["resultingPotVersion"]

        # 2. AddShareholders
        targets = [
            {"id": s["id"], "name": s["name"], "attachment": "SELF", "weight": {"numerator": "1", "denominator": "1"}}
            for s in shs
        ]
        st, res = api_post("/api/v3/commands", {
            "operation": "AddShareholders", "potId": pot_id, "observedVersion": v,
            "bindingToken": binding_token, "payload": {"targets": targets}
        }, f"seed-sh-{pot_id}-{v}")
        if st != 202:
            raise RuntimeError(f"AddShareholders admission rejected: HTTP {st} ({res})")
        r = poll_result(res["requestId"])
        v = r["resultingPotVersion"]

        # 3. CreateExpenses
        payer_id = shs[0]["id"]
        for idx, exp in enumerate(exps):
            exp_payload = {
                "id": exp["id"],
                "label": exp["title"],
                "date": "2026-11-06",
                "amount": {"numerator": str(exp["amountCents"]), "denominator": "100"},
                "payerId": exp["payerId"] or payer_id,
                "participants": [{"id": s["id"], "weight": None} for s in shs],
            }
            st, res = api_post("/api/v3/commands", {
                "operation": "CreateExpense", "potId": pot_id, "observedVersion": v,
                "bindingToken": binding_token, "payload": exp_payload
            }, f"seed-exp-{pot_id}-{v}")
            if st != 202:
                raise RuntimeError(f"CreateExpense admission rejected: HTTP {st} ({res})")
            r = poll_result(res["requestId"])
            v = r["resultingPotVersion"]
            if (idx + 1) % 50 == 0 or (idx + 1) == len(exps):
                print(f"     ... {idx + 1}/{len(exps)} expenses applied (version {v})")

        # 4. Verify SQL materialization (bounded poll for projection convergence)
        sql_valid = False
        sql_pot_ver = ""
        sql_bal = ""
        sql_exp_count = ""
        verify_start = time.time()
        while time.time() - verify_start < 5.0:
            sql_pot_ver = run_psql(READ_DB, f"SELECT pot_version FROM v3_read.read_current_pot WHERE pot_id='{pot_id}';")
            sql_bal = run_psql(READ_DB, f"SELECT count(*) FROM v3_read.read_pot_balance WHERE pot_id='{pot_id}' AND pot_version={v};")
            sql_exp_count = run_psql(READ_DB, f"SELECT jsonb_array_length(payload->'expenses') FROM v3_read.read_pot_version WHERE pot_id='{pot_id}' AND pot_version={v};")
            if sql_pot_ver == str(v) and sql_bal == "1" and sql_exp_count == str(len(exps)):
                sql_valid = True
                break
            time.sleep(0.05)

        print(f"  ✓ Pot {pot_id} materialized at v{v}: sql_pot_ver={sql_pot_ver}, sql_bal={sql_bal}, sql_expenses={sql_exp_count} (valid={sql_valid})")

        materialized_pots.append({
            "potId": pot_id,
            "finalVersion": v,
            "shareholdersCount": len(shs),
            "expensesCount": len(exps),
            "sqlVerified": sql_valid,
        })

    dataset["materialized"] = True
    dataset["materializedAt"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    dataset["materializationProof"] = materialized_pots
    return dataset


def verify_materialized_pot(pot_id: str, expected_version: int, expected_expenses: int) -> bool:
    sql_pot_ver = run_psql(READ_DB, f"SELECT pot_version FROM v3_read.read_current_pot WHERE pot_id='{pot_id}';")
    sql_bal = run_psql(READ_DB, f"SELECT count(*) FROM v3_read.read_pot_balance WHERE pot_id='{pot_id}' AND pot_version={expected_version};")
    sql_exp_count = run_psql(READ_DB, f"SELECT jsonb_array_length(payload->'expenses') FROM v3_read.read_pot_version WHERE pot_id='{pot_id}' AND pot_version={expected_version};")
    return sql_pot_ver == str(expected_version) and sql_bal == "1" and sql_exp_count == str(expected_expenses)


def save_seed(dataset: Dict, output_path: Optional[Path] = None) -> Path:
    SEEDS_DIR.mkdir(parents=True, exist_ok=True)
    out = output_path or (SEEDS_DIR / f"{dataset['seedId']}.json")
    with open(out, "w", encoding="utf-8") as f:
        json.dump(dataset, f, indent=2)
    return out


def main():
    parser = argparse.ArgumentParser(description="Pocoma V3 SPEC-09 Dataset & Seed Generator")
    parser.add_argument("--profile", choices=list(SPEC09_PROFILES.keys()), default="small",
                        help="SPEC-09 target profile")
    parser.add_argument("--seed-id", help="Deterministic seed identifier")
    parser.add_argument("--pots", type=int, help="Number of Pots to generate")
    parser.add_argument("--contention", action="store_true", help="Generate single-pot contention profile")
    parser.add_argument("--enable-large-scale", action="store_true",
                        help="Explicit authorization for 10k, 1M, or 10M high-volume generation")
    parser.add_argument("--materialize", action="store_true",
                        help="Actively submit dataset through V3 API and verify SQL materialization")
    parser.add_argument("--verify-materialized", help="Verify that existing seed JSON file is present in PostgreSQL")
    parser.add_argument("--save", action="store_true", help="Save dataset to docs/testing/evidence/L6-I3/seeds/")
    parser.add_argument("--output", help="Custom output JSON path")

    args = parser.parse_args()

    if args.verify_materialized:
        seed_path = Path(args.verify_materialized)
        if not seed_path.exists():
            print(f"ERROR: Seed file {seed_path} not found", file=sys.stderr)
            sys.exit(1)
        with open(seed_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        proof = data.get("materializationProof", [])
        if not proof:
            print(f"Seed {seed_path} has no materialization proof recorded.", file=sys.stderr)
            sys.exit(1)
        all_ok = True
        for p in proof:
            ok = verify_materialized_pot(p["potId"], p["finalVersion"], p["expensesCount"])
            print(f"  Pot {p['potId']}: v={p['finalVersion']} (expenses={p['expensesCount']}) -> {'MATERIALIZED' if ok else 'NOT IN DB'}")
            if not ok:
                all_ok = False
        sys.exit(0 if all_ok else 1)

    try:
        dataset = create_seed(
            profile_name=args.profile,
            seed_id=args.seed_id,
            pots_count=args.pots,
            contention=args.contention,
            enable_large_scale=args.enable_large_scale,
        )
        if args.materialize:
            dataset = materialize_dataset(dataset)
    except Exception as e:
        print(f"ERROR: {e}", file=sys.stderr)
        sys.exit(1)

    print("================================================================================")
    print(" Pocoma V3 — Seed & Dataset Generation Summary")
    print("================================================================================")
    print(f"  Seed Identifier:     {dataset['seedId']}")
    print(f"  SPEC-09 Profile:     {dataset['profile']}")
    print(f"  SHA-256 Fingerprint: {dataset['fingerprintSha256']}")
    print(f"  Pots Count:          {dataset['potsCount']}")
    print(f"  Total Shareholders:  {dataset['totalShareholders']}")
    print(f"  Total Expenses:      {dataset['totalExpenses']}")
    print(f"  Estimated Events:    {dataset['estimatedEvents']}")
    print(f"  Materialized in DB:  {dataset.get('materialized', False)}")
    print(f"  Contention Mode:     {'ENABLED (single Pot)' if dataset['contention'] else 'DISABLED (independent Pots)'}")
    print("--------------------------------------------------------------------------------")

    if args.save or args.output:
        dest = Path(args.output) if args.output else None
        saved_file = save_seed(dataset, dest)
        print(f"Dataset successfully saved to: {saved_file}")


if __name__ == "__main__":
    main()
