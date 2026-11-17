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

    def _choose_rare_operation(self, pot: PotState) -> Optional[str]:
        has_weight = any(not s.weight_updated for s in pot.shareholders)
        has_attach = any(not s.attached for s in pot.shareholders)
        if not (has_weight or has_attach):
            return None
        w_cnt = self.emitted_counts["UpdateShareholderWeights"]
        a_cnt = self.emitted_counts["UpdateShareholderDetails"]
        if (w_cnt <= a_cnt and has_weight) or not has_attach:
            return "UpdateShareholderWeights"
        return "UpdateShareholderDetails"

    def _select_operation_for_pot(self, pot: PotState) -> str:
        """Chooses an operation family adhering to 88/10/2 target distribution and pot eligibility."""
        total = sum(self.emitted_counts.values())
        curr_correct = (self.emitted_counts["UpdateExpenseFinancials"] / total) if total > 0 else 0.0
        rare_sum = self.emitted_counts["UpdateShareholderWeights"] + self.emitted_counts["UpdateShareholderDetails"]
        curr_rare = (rare_sum / total) if total > 0 else 0.0
        if (curr_rare < self.config.rare_ratio) or (self.rng.random() < self.config.rare_ratio):
            rare_op = self._choose_rare_operation(pot)
            if rare_op:
                return rare_op
        has_correctable = any(not e.corrected for e in pot.applied_expenses)
        if ((curr_correct < self.config.correction_ratio) or (self.rng.random() < self.config.correction_ratio)) and has_correctable:
            return "UpdateExpenseFinancials"
        return "CreateExpense"

    def _build_create_expense(self, pot: PotState) -> Dict[str, Any]:
        payer = self.rng.choice(pot.shareholders) if pot.shareholders else None
        payer_id = payer.id if payer else str(uuid.UUID(int=self.rng.getrandbits(128)))
        known_ids = {e.id for p in self.pots for e in p.applied_expenses}
        exp_id = str(uuid.UUID(int=self.rng.getrandbits(128)))
        while exp_id in known_ids:
            exp_id = str(uuid.UUID(int=self.rng.getrandbits(128)))
        label = f"Exp-{pot.pot_id[:6]}-{len(pot.applied_expenses) + 1}"
        amount_num = self.rng.randint(5, 500)
        exp_info = ExpenseInfo(
            id=exp_id, label=label, date="2026-11-08", amount_num=amount_num, amount_den=1,
            payer_id=payer_id, participants=[s.id for s in pot.shareholders], corrected=False,
        )
        return {
            "operation": "CreateExpense", "potId": pot.pot_id, "observedVersion": pot.confirmed_version,
            "payload": {
                "id": exp_id, "label": label, "date": "2026-11-08",
                "amount": {"numerator": str(amount_num), "denominator": "1"}, "payerId": payer_id,
                "participants": [{"id": s.id, "weight": None} for s in pot.shareholders],
            },
            "_meta": {"type": "CreateExpense", "expense": exp_info.to_dict()},
        }

    def _build_update_expense(self, pot: PotState) -> Dict[str, Any]:
        eligible = [e for e in pot.applied_expenses if not e.corrected]
        if not eligible:
            return self._build_create_expense(pot)
        target_exp = self.rng.choice(eligible)
        participants = [{"id": s.id, "weight": None} for s in pot.shareholders]
        if len(participants) > 1:
            participants[1]["weight"] = {"numerator": "2", "denominator": "1"}
        return {
            "operation": "UpdateExpenseFinancials", "potId": pot.pot_id, "observedVersion": pot.confirmed_version,
            "payload": {
                "id": target_exp.id,
                "amount": {"numerator": str(target_exp.amount_num + self.rng.randint(10, 100)), "denominator": "1"},
                "payerId": target_exp.payer_id, "participants": participants,
            },
            "_meta": {"type": "UpdateExpenseFinancials", "expenseId": target_exp.id},
        }

    def _build_update_shareholder(self, pot: PotState, op: str) -> Dict[str, Any]:
        if op == "UpdateShareholderWeights":
            eligible = [s for s in pot.shareholders if not s.weight_updated]
            target_fn = lambda s: {"id": s.id, "weight": {"numerator": "2", "denominator": "1"}}
        else:
            eligible = [s for s in pot.shareholders if not s.attached]
            target_fn = lambda s: {"id": s.id, "name": s.name, "attachment": "SELF"}
        if not eligible:
            return self._build_create_expense(pot)
        target_sh = self.rng.choice(eligible)
        return {
            "operation": op, "potId": pot.pot_id, "observedVersion": pot.confirmed_version,
            "payload": {"targets": [target_fn(target_sh)]},
            "_meta": {"type": op, "shareholderId": target_sh.id},
        }

    def _build_command(self, pot: PotState, op: str) -> Dict[str, Any]:
        """Constructs a strictly contract-valid V3 command payload."""
        if op == "UpdateExpenseFinancials":
            return self._build_update_expense(pot)
        if op in ("UpdateShareholderWeights", "UpdateShareholderDetails"):
            return self._build_update_shareholder(pot, op)
        return self._build_create_expense(pot)

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

    @staticmethod
    def _apply_terminal_meta(pot: PotState, meta: Optional[Dict[str, Any]]):
        if not meta:
            return
        mtype = meta.get("type")
        if mtype == "CreateExpense" and "expense" in meta:
            exp_data = meta["expense"]
            pot.applied_expenses.append(ExpenseInfo.from_dict(exp_data) if isinstance(exp_data, dict) else exp_data)
        elif mtype == "UpdateExpenseFinancials" and "expenseId" in meta:
            for exp in pot.applied_expenses:
                if exp.id == meta["expenseId"]:
                    exp.corrected = True
                    break
        elif mtype in ("UpdateShareholderWeights", "UpdateShareholderDetails") and "shareholderId" in meta:
            attr = "weight_updated" if mtype == "UpdateShareholderWeights" else "attached"
            for sh in pot.shareholders:
                if sh.id == meta["shareholderId"]:
                    setattr(sh, attr, True)
                    break

    def on_command_terminal(self, command_id: str, pot_id: str, resulting_version: Optional[int], outcome: str):
        """Advances Pot version and frees in-flight lock upon terminal notification."""
        with self._lock:
            pot_idx = self._pot_index_by_id.get(pot_id)
            if pot_idx is None:
                return
            pot = self.pots[pot_idx]
            meta = self._emitted_command_metadata.pop(command_id, None)
            op = pot.in_flight_operation or (meta.get("type") if meta else None) or "CreateExpense"
            pot.in_flight, pot.in_flight_command_id, pot.in_flight_operation = False, None, None
            pot.confirmed_version = resulting_version if (resulting_version and resulting_version > pot.confirmed_version) else (pot.confirmed_version + 1)
            if outcome in ("CHANGED", "COMPLETED"):
                if op in self.applied_counts:
                    self.applied_counts[op] += 1
                self._apply_terminal_meta(pot, meta)
            elif op in self.rejected_counts:
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


def _run_write_query(sql: str) -> List[str]:
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={os.environ.get('POSTGRES_PASSWORD', 'pocoma-local-only')}",
        os.environ.get("POSTGRES_CONTAINER", "pocoma-functional-postgres-1"),
        "psql", "-U", os.environ.get("POSTGRES_USER", "pocoma"), "-d", os.environ.get("WRITE_DB", "pocoma_write"), "-tAc", sql,
    ]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
        if res.returncode == 0:
            return [line.strip() for line in res.stdout.strip().split("\n") if line.strip()]
    except Exception:
        pass
    return []


def _sync_shareholder_flag(pot_map: Dict[str, PotState], sql: str, attr: str):
    for line in _run_write_query(sql):
        parts = line.split("|")
        if len(parts) == 2 and parts[0] in pot_map:
            for sh in pot_map[parts[0]].shareholders:
                if sh.id == parts[1]:
                    setattr(sh, attr, True)


def _sync_expenses(pot_map: Dict[str, PotState], pot_ids_str: str):
    sql_exp = (
        "SELECT ef.pot_id, ef.object_id, MAX(ef.local_version), ef.payer_id, ef.numerator, ef.denominator "
        f"FROM v3_write.v3_expense_financials ef WHERE ef.pot_id IN ({pot_ids_str}) "
        "GROUP BY ef.pot_id, ef.object_id, ef.payer_id, ef.numerator, ef.denominator;"
    )
    exp_by_pot: Dict[str, List[ExpenseInfo]] = {pid: [] for pid in pot_map}
    for line in _run_write_query(sql_exp):
        parts = line.split("|")
        if len(parts) < 6 or parts[0] not in pot_map:
            continue
        try:
            max_lv, num, den = int(parts[2]), int(parts[4]), int(parts[5])
        except ValueError:
            continue
        exp_by_pot[parts[0]].append(ExpenseInfo(
            id=parts[1], label=f"synced-{parts[1][:8]}", date="2026-11-08",
            amount_num=num, amount_den=den, payer_id=parts[3],
            participants=[s.id for s in pot_map[parts[0]].shareholders], corrected=(max_lv > 1),
        ))
    for pid, exp_list in exp_by_pot.items():
        if exp_list:
            pot_map[pid].applied_expenses = exp_list


def sync_pot_states_from_db(pots: List[PotState]):
    """Synchronizes confirmed versions, applied expenses, and shareholder statuses from database."""
    if not pots:
        return
    pot_map = {p.pot_id: p for p in pots}
    pot_ids_str = ",".join(f"'{p.pot_id}'" for p in pots)
    for line in _run_write_query(f"SELECT pot_id, MAX(version) FROM v3_write.v3_pot_version WHERE pot_id IN ({pot_ids_str}) GROUP BY pot_id;"):
        parts = line.split("|")
        if len(parts) == 2 and parts[0] in pot_map and parts[1].isdigit():
            pot_map[parts[0]].confirmed_version = int(parts[1])
    _sync_expenses(pot_map, pot_ids_str)
    _sync_shareholder_flag(pot_map, f"SELECT pot_id, object_id FROM v3_write.v3_shareholder_details WHERE pot_id IN ({pot_ids_str}) AND user_id IS NOT NULL;", "attached")
    _sync_shareholder_flag(pot_map, f"SELECT pot_id, object_id FROM v3_write.v3_shareholder_weight WHERE pot_id IN ({pot_ids_str}) GROUP BY pot_id, object_id HAVING COUNT(*) > 1;", "weight_updated")


def _submit_fixture_cmd(base_url: str, token: str, body: Dict[str, Any], idem_key: str) -> str:
    data_bytes = json.dumps(body).encode("utf-8")
    for attempt in range(20):
        try:
            req = urllib.request.Request(
                f"{base_url}/api/v3/commands", data=data_bytes,
                headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json", "Idempotency-Key": idem_key},
            )
            with urllib.request.urlopen(req, timeout=10) as resp:
                if resp.status == 202:
                    return json.loads(resp.read().decode())["requestId"]
        except urllib.error.HTTPError as e:
            if e.code == 503 and attempt < 19:
                time.sleep(0.04 * (1 + attempt))
                continue
            raise
    raise RuntimeError(f"Exceeded admission retries for {idem_key}")


def _poll_fixture_result(base_url: str, token: str, cid: str) -> Dict[str, Any]:
    deadline = time.time() + 25.0
    while time.time() < deadline:
        try:
            req = urllib.request.Request(f"{base_url}/api/v3/commands/{cid}/result", headers={"Authorization": f"Bearer {token}"})
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode())
                    if data.get("outcome") in ("COMPLETED", "CHANGED"):
                        return data
        except urllib.error.HTTPError as e:
            if e.code not in (404, 503):
                raise
        time.sleep(0.04)
    return {}


def _prepare_single_pot(pot: PotState, base_url: str, token: str, binding_token: str) -> PotState:
    cid1 = _submit_fixture_cmd(base_url, token, {
        "operation": "CreatePot", "potId": pot.pot_id, "observedVersion": 0,
        "bindingToken": binding_token, "payload": {"label": pot.label},
    }, f"prep-create-{pot.pot_id}")
    _poll_fixture_result(base_url, token, cid1)
    targets = [
        {"id": s.id, "name": s.name, "attachment": "SELF" if s.attached else "FREE",
         "weight": {"numerator": str(s.weight_num), "denominator": str(s.weight_den)}}
        for s in pot.shareholders
    ]
    cid2 = _submit_fixture_cmd(base_url, token, {
        "operation": "AddShareholders", "potId": pot.pot_id, "observedVersion": 1,
        "bindingToken": binding_token, "payload": {"targets": targets},
    }, f"prep-addsh-{pot.pot_id}")
    d2 = _poll_fixture_result(base_url, token, cid2)
    pot.confirmed_version = d2.get("resultingPotVersion", 2)
    return pot


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
    with ThreadPoolExecutor(max_workers=max_workers) as executor:
        prepared_pots = [f.result() for f in as_completed(executor.submit(_prepare_single_pot, p, base_url, token, binding_token) for p in pots)]
    pot_map = {p.pot_id: p for p in prepared_pots}
    ordered_pots = [pot_map[p.pot_id] for p in pots]
    elapsed = time.time() - start_t
    print(f"  ✓ Prepared {len(ordered_pots)} live Pots in {elapsed:.2f}s ({len(ordered_pots)/elapsed:.1f} pots/s)")
    if manifest_path:
        save_fixtures_manifest(manifest_path, config, ordered_pots)
    sync_pot_states_from_db(ordered_pots)
    return ordered_pots
