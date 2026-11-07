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
- Guarded definition of normative historical profiles (10k, 1M, 10M events), strictly disabled by default.
"""

import argparse
from dataclasses import asdict, dataclass
import hashlib
import json
import os
from pathlib import Path
import random
import sys
from typing import Dict, List, Optional

REPO_ROOT = Path(__file__).resolve().parents[2]
SEEDS_DIR = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3" / "seeds"

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
    parser.add_argument("--save", action="store_true", help="Save dataset to docs/testing/evidence/L6-I3/seeds/")
    parser.add_argument("--output", help="Custom output JSON path")

    args = parser.parse_args()

    try:
        dataset = create_seed(
            profile_name=args.profile,
            seed_id=args.seed_id,
            pots_count=args.pots,
            contention=args.contention,
            enable_large_scale=args.enable_large_scale,
        )
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
    print(f"  Contention Mode:     {'ENABLED (single Pot)' if dataset['contention'] else 'DISABLED (independent Pots)'}")
    print("--------------------------------------------------------------------------------")

    if args.save or args.output:
        dest = Path(args.output) if args.output else None
        saved_file = save_seed(dataset, dest)
        print(f"Dataset successfully saved to: {saved_file}")


if __name__ == "__main__":
    main()
