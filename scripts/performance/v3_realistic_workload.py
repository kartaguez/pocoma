#!/usr/bin/env python3
"""Pocoma V3 — Realistic Business Workload Planner & Scheduler (scripts/performance/v3_realistic_workload.py).

Implements Phase B requirements for L6/I3-R5:
1. Target mix:
   - 88% Expense Creation (CreateExpense)
   - 10% Expense Correction (UpdateExpenseFinancials)
   - 2% Rare lifecycle operations (UpdateShareholderWeights & UpdateShareholderDetails attachment)
2. Strict lifecycle rules:
   - At most one mutating command in-flight per Pot in nominal scenario.
   - Wait for confirmed terminal version before emitting the next command on that Pot.
   - Corrections only target existing, confirmed applied expenses.
   - At most one correction per expense during its lifecycle.
   - At most one default weight update per eligible shareholder.
   - At most one attachment per eligible unattached (FREE) shareholder.
   - No reuse of consumed rare operations.
   - Fair equitable rotation between Pots.
3. Statistical mix tracking:
   - Demanded mix vs Planned mix vs Emitted mix vs Admitted mix vs Applied mix.
   - Absolute and relative deviations and root causes.
4. Offline testability: 100% runnable without PostgreSQL or Keycloak.
"""

from dataclasses import asdict, dataclass, field
import hashlib
import json
import math
import os
from pathlib import Path
import random
import subprocess
import threading
import time
from typing import Any, Dict, List, Optional, Set, Tuple
import urllib.error
import urllib.request
import uuid


@dataclass
class ShareholderInfo:
    id: str
    name: str
    weight_num: int = 1
    weight_den: int = 1
    attached: bool = False  # True if attached to a user
    weight_updated: bool = False  # True if default weight was modified

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "ShareholderInfo":
        return cls(**d)


@dataclass
class ExpenseInfo:
    id: str
    label: str
    date: str
    amount_num: int
    amount_den: int
    payer_id: str
    participants: List[str]
    corrected: bool = False  # True if corrected

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "ExpenseInfo":
        return cls(**d)


@dataclass
class PotState:
    pot_id: str
    label: str
    confirmed_version: int = 0
    shareholders: List[ShareholderInfo] = field(default_factory=list)
    applied_expenses: List[ExpenseInfo] = field(default_factory=list)
    in_flight: bool = False
    in_flight_command_id: Optional[str] = None
    in_flight_operation: Optional[str] = None
    in_flight_observed_version: Optional[int] = None
    in_flight_submitted_at: Optional[float] = None
    last_command_time: float = 0.0

    def to_dict(self) -> Dict[str, Any]:
        return {
            "potId": self.pot_id,
            "label": self.label,
            "confirmedVersion": self.confirmed_version,
            "shareholders": [s.to_dict() for s in self.shareholders],
            "appliedExpenses": [e.to_dict() for e in self.applied_expenses],
            "inFlight": self.in_flight,
            "inFlightCommandId": self.in_flight_command_id,
            "inFlightOperation": self.in_flight_operation,
            "inFlightObservedVersion": self.in_flight_observed_version,
            "inFlightSubmittedAt": self.in_flight_submitted_at,
            "lastCommandTime": self.last_command_time,
        }

    @classmethod
    def from_dict(cls, d: Dict[str, Any]) -> "PotState":
        pot = cls(
            pot_id=d["potId"],
            label=d["label"],
            confirmed_version=d.get("confirmedVersion", 0),
            in_flight=d.get("inFlight", False),
            in_flight_command_id=d.get("inFlightCommandId"),
            in_flight_operation=d.get("inFlightOperation"),
            in_flight_observed_version=d.get("inFlightObservedVersion"),
            in_flight_submitted_at=d.get("inFlightSubmittedAt"),
            last_command_time=d.get("lastCommandTime", 0.0),
        )
        pot.shareholders = [ShareholderInfo.from_dict(s) for s in d.get("shareholders", [])]
        pot.applied_expenses = [ExpenseInfo.from_dict(e) for e in d.get("appliedExpenses", [])]
        return pot


@dataclass
class WorkloadConfig:
    target_rate: float = 50.0  # cmd/s
    duration_sec: float = 120.0  # seconds
    pot_pool_size: int = 250
    creation_ratio: float = 0.88
    correction_ratio: float = 0.10
    rare_ratio: float = 0.02
    random_seed: str = "pocoma-v3-r5-workload"
    shareholders_per_pot: int = 5

    @property
    def total_planned_commands(self) -> int:
        return int(math.ceil(self.target_rate * self.duration_sec))


class RealisticWorkloadScheduler:
    """Orchestrates deterministic realistic business operations across a pool of independent Pots."""

    def __init__(self, config: WorkloadConfig, pots: Optional[List[PotState]] = None):
        self.config = config
        self._lock = threading.Lock()
        self.rng = random.Random(config.random_seed)
        self.pots: List[PotState] = pots or []
        self._pot_index_by_id: Dict[str, int] = {p.pot_id: idx for idx, p in enumerate(self.pots)}

        # Statistical counters
        self.demanded_counts = {
            "CreateExpense": int(round(config.total_planned_commands * config.creation_ratio)),
            "UpdateExpenseFinancials": int(round(config.total_planned_commands * config.correction_ratio)),
            "UpdateShareholderWeights": int(round(config.total_planned_commands * (config.rare_ratio / 2.0))),
            "UpdateShareholderDetails": int(round(config.total_planned_commands * (config.rare_ratio / 2.0))),
        }
        self.planned_counts = {"CreateExpense": 0, "UpdateExpenseFinancials": 0, "UpdateShareholderWeights": 0, "UpdateShareholderDetails": 0}
        self.emitted_counts = {"CreateExpense": 0, "UpdateExpenseFinancials": 0, "UpdateShareholderWeights": 0, "UpdateShareholderDetails": 0}
        self.admitted_counts = {"CreateExpense": 0, "UpdateExpenseFinancials": 0, "UpdateShareholderWeights": 0, "UpdateShareholderDetails": 0}
        self.applied_counts = {"CreateExpense": 0, "UpdateExpenseFinancials": 0, "UpdateShareholderWeights": 0, "UpdateShareholderDetails": 0}
        self.rejected_counts = {"CreateExpense": 0, "UpdateExpenseFinancials": 0, "UpdateShareholderWeights": 0, "UpdateShareholderDetails": 0}

        # Staged pending corrections / rare ops
        self._emitted_command_metadata: Dict[str, Dict[str, Any]] = {}
        self.pot_starvation_count = 0
        self.round_robin_idx = 0

    @property
    def seed_fingerprint(self) -> str:
        s = f"{self.config.random_seed}:{self.config.target_rate}:{self.config.duration_sec}:{self.config.pot_pool_size}"
        return hashlib.sha256(s.encode("utf-8")).hexdigest()

    def set_pots(self, pots: List[PotState]):
        with self._lock:
            self.pots = pots
            self._pot_index_by_id = {p.pot_id: idx for idx, p in enumerate(self.pots)}

    def next_command(self) -> Optional[Dict[str, Any]]:
        """Selects the next eligible business command conforming to 88/10/2 mix and lifecycle constraints."""
        with self._lock:
            if not self.pots:
                return None

            # Find a free Pot using round-robin to ensure fair load distribution
            pot: Optional[PotState] = None
            total_pots = len(self.pots)
            for _ in range(total_pots):
                candidate = self.pots[self.round_robin_idx % total_pots]
                self.round_robin_idx += 1
                if not candidate.in_flight:
                    pot = candidate
                    break

            if not pot:
                self.pot_starvation_count += 1
                return None

            # Determine which operation family to emit based on target ratios and eligibility
            op = self._select_operation_for_pot(pot)

            command = self._build_command(pot, op)
            if not command:
                # Fallback to CreateExpense if selection failed
                op = "CreateExpense"
                command = self._build_command(pot, op)

            # Mark Pot as in-flight
            pot.in_flight = True
            pot.in_flight_operation = op
            pot.in_flight_observed_version = pot.confirmed_version
            pot.in_flight_submitted_at = time.time()
            pot.last_command_time = time.time()

            self.planned_counts[op] += 1
            self.emitted_counts[op] += 1

            return command

    def _select_operation_for_pot(self, pot: PotState) -> str:
        """Chooses an operation family adhering to 88/10/2 target distribution and pot eligibility."""
        total_emitted = sum(self.emitted_counts.values())
        curr_create_ratio = (self.emitted_counts["CreateExpense"] / total_emitted) if total_emitted > 0 else 1.0
        curr_correct_ratio = (self.emitted_counts["UpdateExpenseFinancials"] / total_emitted) if total_emitted > 0 else 0.0
        curr_rare_ratio = ((self.emitted_counts["UpdateShareholderWeights"] + self.emitted_counts["UpdateShareholderDetails"]) / total_emitted) if total_emitted > 0 else 0.0

        # Check eligibility on this Pot
        has_correctable_expense = any(not e.corrected for e in pot.applied_expenses)
        has_weight_updatable_sh = any(not s.weight_updated for s in pot.shareholders)
        has_attachable_sh = any(not s.attached for s in pot.shareholders)

        # Decide operation family:
        # Check rare ops (2%)
        rare_needed = (curr_rare_ratio < self.config.rare_ratio) or (self.rng.random() < self.config.rare_ratio)
        if rare_needed and (has_weight_updatable_sh or has_attachable_sh):
            weight_count = self.emitted_counts["UpdateShareholderWeights"]
            attach_count = self.emitted_counts["UpdateShareholderDetails"]
            if weight_count <= attach_count and has_weight_updatable_sh:
                return "UpdateShareholderWeights"
            elif has_attachable_sh:
                return "UpdateShareholderDetails"
            elif has_weight_updatable_sh:
                return "UpdateShareholderWeights"

        # Check correction (10%)
        correct_needed = (curr_correct_ratio < self.config.correction_ratio) or (self.rng.random() < self.config.correction_ratio)
        if correct_needed and has_correctable_expense:
            return "UpdateExpenseFinancials"

        # Default / majority is CreateExpense (88%)
        return "CreateExpense"

    def _build_command(self, pot: PotState, op: str) -> Dict[str, Any]:
        """Constructs a strictly contract-valid V3 command payload."""
        observed_version = pot.confirmed_version
        payer = self.rng.choice(pot.shareholders) if pot.shareholders else None
        payer_id = payer.id if payer else str(uuid.UUID(int=self.rng.getrandbits(128)))

        if op == "CreateExpense":
            # Generate deterministic UUID from PRNG
            exp_id = str(uuid.UUID(int=self.rng.getrandbits(128)))
            # If this expense ID happens to already exist in known pot expenses, advance PRNG
            known_ids = {e.id for p in self.pots for e in p.applied_expenses}
            while exp_id in known_ids:
                exp_id = str(uuid.UUID(int=self.rng.getrandbits(128)))
            label = f"Exp-{pot.pot_id[:6]}-{len(pot.applied_expenses) + 1}"
            amount_num = self.rng.randint(5, 500)
            participants = [{"id": s.id, "weight": None} for s in pot.shareholders]
            payload = {
                "id": exp_id,
                "label": label,
                "date": "2026-11-08",
                "amount": {"numerator": str(amount_num), "denominator": "1"},
                "payerId": payer_id,
                "participants": participants,
            }
            # Temporarily stash expense metadata for commit
            exp_info = ExpenseInfo(
                id=exp_id, label=label, date="2026-11-08",
                amount_num=amount_num, amount_den=1, payer_id=payer_id,
                participants=[s.id for s in pot.shareholders], corrected=False
            )
            command_envelope = {
                "operation": "CreateExpense",
                "potId": pot.pot_id,
                "observedVersion": observed_version,
                "payload": payload,
                "_meta": {"type": "CreateExpense", "expense": exp_info.to_dict()},
            }
            return command_envelope

        elif op == "UpdateExpenseFinancials":
            eligible = [e for e in pot.applied_expenses if not e.corrected]
            if not eligible:
                return self._build_command(pot, "CreateExpense")
            target_exp = self.rng.choice(eligible)
            new_amount = target_exp.amount_num + self.rng.randint(10, 100)
            participants = [{"id": s.id, "weight": None} for s in pot.shareholders]
            # Optionally vary one participant's weight
            if len(participants) > 1:
                participants[1]["weight"] = {"numerator": "2", "denominator": "1"}

            payload = {
                "id": target_exp.id,
                "amount": {"numerator": str(new_amount), "denominator": "1"},
                "payerId": target_exp.payer_id,
                "participants": participants,
            }
            return {
                "operation": "UpdateExpenseFinancials",
                "potId": pot.pot_id,
                "observedVersion": observed_version,
                "payload": payload,
                "_meta": {"type": "UpdateExpenseFinancials", "expenseId": target_exp.id},
            }

        elif op == "UpdateShareholderWeights":
            eligible = [s for s in pot.shareholders if not s.weight_updated]
            if not eligible:
                return self._build_command(pot, "CreateExpense")
            target_sh = self.rng.choice(eligible)
            payload = {
                "targets": [
                    {
                        "id": target_sh.id,
                        "weight": {"numerator": "2", "denominator": "1"}
                    }
                ]
            }
            return {
                "operation": "UpdateShareholderWeights",
                "potId": pot.pot_id,
                "observedVersion": observed_version,
                "payload": payload,
                "_meta": {"type": "UpdateShareholderWeights", "shareholderId": target_sh.id},
            }

        elif op == "UpdateShareholderDetails":
            eligible = [s for s in pot.shareholders if not s.attached]
            if not eligible:
                return self._build_command(pot, "CreateExpense")
            target_sh = self.rng.choice(eligible)
            payload = {
                "targets": [
                    {
                        "id": target_sh.id,
                        "name": target_sh.name,
                        "attachment": "SELF"
                    }
                ]
            }
            return {
                "operation": "UpdateShareholderDetails",
                "potId": pot.pot_id,
                "observedVersion": observed_version,
                "payload": payload,
                "_meta": {"type": "UpdateShareholderDetails", "shareholderId": target_sh.id},
            }

        return self._build_command(pot, "CreateExpense")

    def record_admission(self, command_id: str, pot_id: str, operation: str, status: int, meta: Optional[Dict] = None):
        """Records HTTP admission outcome and links commandId to in-flight Pot."""
        with self._lock:
            pot_idx = self._pot_index_by_id.get(pot_id)
            if pot_idx is None:
                return
            pot = self.pots[pot_idx]
            pot.in_flight_command_id = command_id

            if meta:
                self._emitted_command_metadata[command_id] = meta

            if status == 202:
                if operation in self.admitted_counts:
                    self.admitted_counts[operation] += 1
            else:
                # Admission failed: immediately release in-flight lock
                pot.in_flight = False
                pot.in_flight_command_id = None
                if operation in self.rejected_counts:
                    self.rejected_counts[operation] += 1

    def on_command_terminal(self, command_id: str, pot_id: str, resulting_version: Optional[int], outcome: str):
        """Advances Pot version and frees in-flight lock upon terminal notification."""
        with self._lock:
            pot_idx = self._pot_index_by_id.get(pot_id)
            if pot_idx is None:
                return
            pot = self.pots[pot_idx]

            meta = self._emitted_command_metadata.pop(command_id, None)
            op = pot.in_flight_operation or (meta.get("type") if meta else None) or "CreateExpense"

            # Release in-flight lock regardless of outcome
            pot.in_flight = False
            pot.in_flight_command_id = None
            pot.in_flight_operation = None

            if outcome in ("CHANGED", "COMPLETED"):
                if resulting_version and resulting_version > pot.confirmed_version:
                    pot.confirmed_version = resulting_version
                else:
                    pot.confirmed_version += 1

                if op in self.applied_counts:
                    self.applied_counts[op] += 1

                # Apply in-memory state mutations
                if meta:
                    mtype = meta.get("type")
                    if mtype == "CreateExpense" and "expense" in meta:
                        exp_data = meta["expense"]
                        if isinstance(exp_data, dict):
                            pot.applied_expenses.append(ExpenseInfo.from_dict(exp_data))
                        elif isinstance(exp_data, ExpenseInfo):
                            pot.applied_expenses.append(exp_data)
                    elif mtype == "UpdateExpenseFinancials" and "expenseId" in meta:
                        for exp in pot.applied_expenses:
                            if exp.id == meta["expenseId"]:
                                exp.corrected = True
                                break
                    elif mtype == "UpdateShareholderWeights" and "shareholderId" in meta:
                        for sh in pot.shareholders:
                            if sh.id == meta["shareholderId"]:
                                sh.weight_updated = True
                                break
                    elif mtype == "UpdateShareholderDetails" and "shareholderId" in meta:
                        for sh in pot.shareholders:
                            if sh.id == meta["shareholderId"]:
                                sh.attached = True
                                break
            else:
                # Terminal REJECTED or failed
                if op in self.rejected_counts:
                    self.rejected_counts[op] += 1

    def get_mix_report(self) -> Dict[str, Any]:
        """Produces exhaustive statistical mix report required by mandate Section 6."""
        with self._lock:
            total_planned = max(1, sum(self.planned_counts.values()))
            total_emitted = max(1, sum(self.emitted_counts.values()))
            total_admitted = max(1, sum(self.admitted_counts.values()))
            total_applied = max(1, sum(self.applied_counts.values()))

            families = ["CreateExpense", "UpdateExpenseFinancials", "UpdateShareholderWeights", "UpdateShareholderDetails"]
            table = []
            for f in families:
                demanded_ratio = (
                    self.config.creation_ratio if f == "CreateExpense"
                    else self.config.correction_ratio if f == "UpdateExpenseFinancials"
                    else self.config.rare_ratio / 2.0
                )
                planned = self.planned_counts[f]
                emitted = self.emitted_counts[f]
                admitted = self.admitted_counts[f]
                applied = self.applied_counts[f]

                planned_ratio = planned / total_planned
                emitted_ratio = emitted / total_emitted
                admitted_ratio = admitted / total_admitted
                applied_ratio = applied / total_applied

                abs_diff = emitted_ratio - demanded_ratio
                rel_diff = (abs_diff / demanded_ratio * 100.0) if demanded_ratio > 0 else 0.0

                table.append({
                    "family": f,
                    "demandedRatio": round(demanded_ratio, 4),
                    "plannedCount": planned,
                    "plannedRatio": round(planned_ratio, 4),
                    "emittedCount": emitted,
                    "emittedRatio": round(emitted_ratio, 4),
                    "admittedCount": admitted,
                    "admittedRatio": round(admitted_ratio, 4),
                    "appliedCount": applied,
                    "appliedRatio": round(applied_ratio, 4),
                    "absoluteDeviation": round(abs_diff, 4),
                    "relativeDeviationPercent": round(rel_diff, 2),
                })

            # Check for causes of deviation
            deviations_causes = []
            if self.pot_starvation_count > 0:
                deviations_causes.append(f"Pot starvation occurred {self.pot_starvation_count} times due to all pots being in-flight.")
            eligible_uncorrected = sum(sum(1 for e in p.applied_expenses if not e.corrected) for p in self.pots)
            eligible_weights = sum(sum(1 for s in p.shareholders if not s.weight_updated) for p in self.pots)
            eligible_free = sum(sum(1 for s in p.shareholders if not s.attached) for p in self.pots)

            return {
                "seed": self.config.random_seed,
                "seedFingerprintSha256": self.seed_fingerprint,
                "potPoolSize": len(self.pots),
                "totalPlanned": sum(self.planned_counts.values()),
                "totalEmitted": sum(self.emitted_counts.values()),
                "totalAdmitted": sum(self.admitted_counts.values()),
                "totalApplied": sum(self.applied_counts.values()),
                "potStarvationCount": self.pot_starvation_count,
                "remainingEligibleEntities": {
                    "uncorrectedExpenses": eligible_uncorrected,
                    "updatableShareholderWeights": eligible_weights,
                    "unattachedShareholders": eligible_free,
                },
                "deviationCauses": deviations_causes,
                "families": table,
            }


def generate_deterministic_fixtures(config: WorkloadConfig) -> List[PotState]:
    """Generates structural Pot representations deterministically offline."""
    rng = random.Random(f"{config.random_seed}:fixtures")
    pots = []
    for pot_idx in range(config.pot_pool_size):
        h = hashlib.sha256(f"pot-{pot_idx}-{rng.randint(0, 10**9)}".encode()).hexdigest()
        pot_id = f"{h[:8]}-{h[8:12]}-4{h[13:16]}-a{h[17:20]}-{h[20:32]}"
        shareholders = []
        for sh_idx in range(config.shareholders_per_pot):
            sh_h = hashlib.sha256(f"sh-{pot_idx}-{sh_idx}-{rng.randint(0, 10**9)}".encode()).hexdigest()
            sh_id = f"{sh_h[:8]}-{sh_h[8:12]}-4{sh_h[13:16]}-a{sh_h[17:20]}-{sh_h[20:32]}"
            # sh_idx 0 is attached to creator (SELF), remaining are unattached (FREE)
            is_creator = (sh_idx == 0)
            shareholders.append(ShareholderInfo(
                id=sh_id,
                name=f"User-{pot_idx}-{sh_idx}",
                weight_num=1,
                weight_den=1,
                attached=is_creator,
                weight_updated=False
            ))
        pots.append(PotState(
            pot_id=pot_id,
            label=f"R5-Pot-{pot_idx}",
            confirmed_version=2,  # CreatePot (v1) + AddShareholders (v2)
            shareholders=shareholders,
            applied_expenses=[]
        ))
    return pots


def save_fixtures_manifest(manifest_path: Path, config: WorkloadConfig, pots: List[PotState]):
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest = {
        "seed": config.random_seed,
        "potPoolSize": len(pots),
        "shareholdersPerPot": config.shareholders_per_pot,
        "createdAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "pots": [p.to_dict() for p in pots]
    }
    canon = json.dumps(manifest, sort_keys=True).encode("utf-8")
    manifest["fingerprintSha256"] = hashlib.sha256(canon).hexdigest()
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2)


def load_fixtures_manifest(manifest_path: Path) -> Tuple[Optional[Dict[str, Any]], List[PotState]]:
    if not manifest_path.exists():
        return None, []
    try:
        with open(manifest_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        pots = [PotState.from_dict(d) for d in data.get("pots", [])]
        return data, pots
    except Exception:
        return None, []


def sync_pot_states_from_db(pots: List[PotState]):
    """Synchronizes confirmed versions, applied expenses, and shareholder statuses from database."""
    if not pots:
        return
    pot_map = {p.pot_id: p for p in pots}
    pot_ids_str = ",".join(f"'{p.pot_id}'" for p in pots)

    postgres_container = os.environ.get("POSTGRES_CONTAINER", "pocoma-functional-postgres-1")
    postgres_user = os.environ.get("POSTGRES_USER", "pocoma")
    postgres_password = os.environ.get("POSTGRES_PASSWORD", "pocoma-local-only")
    write_db = os.environ.get("WRITE_DB", "pocoma_write")

    def run_query(sql: str) -> List[str]:
        cmd = [
            "docker", "exec", "-i", "-e", f"PGPASSWORD={postgres_password}",
            postgres_container, "psql", "-U", postgres_user, "-d", write_db, "-tAc", sql
        ]
        try:
            res = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
            if res.returncode == 0:
                return [line.strip() for line in res.stdout.strip().split("\n") if line.strip()]
        except Exception:
            pass
        return []

    # 1. Sync max version
    sql_ver = f"SELECT pot_id, MAX(version) FROM v3_write.v3_pot_version WHERE pot_id IN ({pot_ids_str}) GROUP BY pot_id;"
    for line in run_query(sql_ver):
        parts = line.split("|")
        if len(parts) == 2 and parts[0] in pot_map:
            try:
                pot_map[parts[0]].confirmed_version = int(parts[1])
            except ValueError:
                pass

    # 2. Sync expenses
    sql_exp = f"""
    SELECT ef.pot_id, ef.object_id, MAX(ef.local_version), ef.payer_id, ef.numerator, ef.denominator
    FROM v3_write.v3_expense_financials ef
    WHERE ef.pot_id IN ({pot_ids_str})
    GROUP BY ef.pot_id, ef.object_id, ef.payer_id, ef.numerator, ef.denominator;
    """
    exp_by_pot: Dict[str, List[ExpenseInfo]] = {p.pot_id: [] for p in pots}
    for line in run_query(sql_exp):
        parts = line.split("|")
        if len(parts) >= 6 and parts[0] in pot_map:
            pid = parts[0]
            eid = parts[1]
            try:
                max_lv = int(parts[2])
                payer = parts[3]
                num = int(parts[4])
                den = int(parts[5])
            except ValueError:
                continue
            exp_info = ExpenseInfo(
                id=eid,
                label=f"synced-{eid[:8]}",
                date="2026-11-08",
                amount_num=num,
                amount_den=den,
                payer_id=payer,
                participants=[s.id for s in pot_map[pid].shareholders],
                corrected=(max_lv > 1)
            )
            exp_by_pot[pid].append(exp_info)

    for pid, exp_list in exp_by_pot.items():
        if exp_list:
            pot_map[pid].applied_expenses = exp_list

    # 3. Sync shareholder attachments
    sql_sh = f"SELECT pot_id, object_id FROM v3_write.v3_shareholder_details WHERE pot_id IN ({pot_ids_str}) AND user_id IS NOT NULL;"
    for line in run_query(sql_sh):
        parts = line.split("|")
        if len(parts) == 2 and parts[0] in pot_map:
            pid = parts[0]
            sid = parts[1]
            for s in pot_map[pid].shareholders:
                if s.id == sid:
                    s.attached = True

    # 4. Sync shareholder weights
    sql_wt = f"SELECT pot_id, object_id FROM v3_write.v3_shareholder_weight WHERE pot_id IN ({pot_ids_str}) GROUP BY pot_id, object_id HAVING COUNT(*) > 1;"
    for line in run_query(sql_wt):
        parts = line.split("|")
        if len(parts) == 2 and parts[0] in pot_map:
            pid = parts[0]
            sid = parts[1]
            for s in pot_map[pid].shareholders:
                if s.id == sid:
                    s.weight_updated = True


def prepare_live_fixtures(token: str, binding_token: str, config: WorkloadConfig,
                          base_url: str = "http://127.0.0.1:8080",
                          manifest_path: Optional[Path] = None,
                          max_workers: int = 4) -> List[PotState]:
    """Ensures deterministic live fixtures exist in the engine, reusing existing manifest if valid."""
    if manifest_path and manifest_path.exists():
        meta, loaded_pots = load_fixtures_manifest(manifest_path)
        if meta and meta.get("seed") == config.random_seed and len(loaded_pots) >= config.pot_pool_size:
            print(f"  ✓ Reusing {len(loaded_pots)} existing fixtures from manifest {manifest_path.name}")
            sync_pot_states_from_db(loaded_pots)
            return loaded_pots[:config.pot_pool_size]

    from concurrent.futures import ThreadPoolExecutor, as_completed

    pots = generate_deterministic_fixtures(config)
    print(f"  ▶ Preparing {len(pots)} live Pots in parallel (workers={max_workers})...")
    start_t = time.time()

    def submit_and_wait(pot: PotState):
        def submit_with_retry(data_bytes: bytes, idem_key: str, max_retries: int = 20) -> str:
            for attempt in range(max_retries):
                try:
                    req = urllib.request.Request(
                        f"{base_url}/api/v3/commands", data=data_bytes,
                        headers={
                            "Authorization": f"Bearer {token}",
                            "Content-Type": "application/json",
                            "Idempotency-Key": idem_key
                        }
                    )
                    with urllib.request.urlopen(req, timeout=10) as resp:
                        if resp.status == 202:
                            r = json.loads(resp.read().decode())
                            return r["requestId"]
                except urllib.error.HTTPError as e:
                    if e.code == 503 and attempt < max_retries - 1:
                        time.sleep(0.04 * (1 + attempt))
                        continue
                    raise
            raise RuntimeError(f"Exceeded admission retries for {idem_key}")

        # 1. CreatePot
        body1 = json.dumps({
            "operation": "CreatePot",
            "potId": pot.pot_id,
            "observedVersion": 0,
            "bindingToken": binding_token,
            "payload": {"label": pot.label}
        }).encode("utf-8")
        cid1 = submit_with_retry(body1, f"prep-create-{pot.pot_id}")

        # Poll result 1
        t_poll_start = time.time()
        while time.time() - t_poll_start < 25.0:
            try:
                with urllib.request.urlopen(
                    urllib.request.Request(f"{base_url}/api/v3/commands/{cid1}/result", headers={"Authorization": f"Bearer {token}"}),
                    timeout=5
                ) as resp:
                    if resp.status == 200:
                        d1 = json.loads(resp.read().decode())
                        if d1.get("outcome") in ("COMPLETED", "CHANGED"):
                            break
            except urllib.error.HTTPError as e:
                if e.code in (404, 503):
                    time.sleep(0.04)
                    continue
                raise
            time.sleep(0.04)

        # 2. AddShareholders (1 SELF, 4 FREE)
        targets = []
        for s in pot.shareholders:
            targets.append({
                "id": s.id,
                "name": s.name,
                "attachment": "SELF" if s.attached else "FREE",
                "weight": {"numerator": str(s.weight_num), "denominator": str(s.weight_den)}
            })

        body2 = json.dumps({
            "operation": "AddShareholders",
            "potId": pot.pot_id,
            "observedVersion": 1,
            "bindingToken": binding_token,
            "payload": {"targets": targets}
        }).encode("utf-8")
        cid2 = submit_with_retry(body2, f"prep-addsh-{pot.pot_id}")

        # Poll result 2
        t_poll_start = time.time()
        while time.time() - t_poll_start < 25.0:
            try:
                with urllib.request.urlopen(
                    urllib.request.Request(f"{base_url}/api/v3/commands/{cid2}/result", headers={"Authorization": f"Bearer {token}"}),
                    timeout=5
                ) as resp:
                    if resp.status == 200:
                        d2 = json.loads(resp.read().decode())
                        if d2.get("outcome") in ("COMPLETED", "CHANGED"):
                            pot.confirmed_version = d2.get("resultingPotVersion", 2)
                            break
            except urllib.error.HTTPError as e:
                if e.code in (404, 503):
                    time.sleep(0.04)
                    continue
                raise
            time.sleep(0.04)

        return pot

    prepared_pots = []
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        futures = {executor.submit(submit_and_wait, p): p for p in pots}
        for f in as_completed(futures):
            prepared_pots.append(f.result())

    # Sort back by original index
    pot_map = {p.pot_id: p for p in prepared_pots}
    ordered_pots = [pot_map[p.pot_id] for p in pots]

    elapsed = time.time() - start_t
    print(f"  ✓ Prepared {len(ordered_pots)} live Pots in {elapsed:.2f}s ({len(ordered_pots)/elapsed:.1f} pots/s)")

    if manifest_path:
        save_fixtures_manifest(manifest_path, config, ordered_pots)
        print(f"  ✓ Saved fixtures manifest to {manifest_path}")

    sync_pot_states_from_db(ordered_pots)
    return ordered_pots
