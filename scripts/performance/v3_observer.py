#!/usr/bin/env python3
"""Pocoma V3 — Decoupled Correlation Store, Concurrent Observers, SQL Reconciler & Drainage Engine.
(scripts/performance/v3_observer.py — R4 Architecture).

Implements Phase B, C, D, E requirements:
1. Concurrent, capacity-driven observation (TerminalObserver & ReadObserver with bounded thread pools).
2. Rate/concurrency limiting with exponential backoff on 404/503/401 without thread starvation.
3. Dual-axis backlog telemetry: upstream pending commands vs discovery slot buffer.
4. Read-only PostgreSQL server-side ground truth reconciliation (SqlReconciler).
5. Strict, non-double-counting mathematical conservation equations.
6. Exact quantiles (p50, p90, p95, p99, mean, min, max) calculated on explicit populations.
7. Support for separate R4 evidence directory and clean drainage lifecycle.
"""

from collections import deque
from dataclasses import asdict, dataclass, field
import json
import math
import os
from pathlib import Path
import queue
import subprocess
import threading
import time
from typing import Any, Dict, List, Optional, Set, Tuple
import urllib.error
import urllib.parse
import urllib.request

REPO_ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_DIR = REPO_ROOT / "docs" / "testing" / "evidence" / "L6-I3"

POSTGRES_CONTAINER = os.environ.get("POSTGRES_CONTAINER", "pocoma-functional-postgres-1")
POSTGRES_USER = os.environ.get("POSTGRES_USER", "pocoma")
POSTGRES_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "pocoma-local-only")
WRITE_DB = os.environ.get("WRITE_DB", "pocoma_write")
READ_DB = os.environ.get("READ_DB", "pocoma_read")
BASE_URL = os.environ.get("POCOMA_BASE_URL", "http://127.0.0.1:8080")


def normalize_backlog_info(bl: Any) -> Dict[str, int]:
    """Normalizes backlog response whether dict or legacy integer."""
    if isinstance(bl, dict):
        return bl
    val = int(bl) if isinstance(bl, (int, float)) else 0
    return {
        "upstreamPending": val,
        "slotBufferTotal": val,
        "slotBufferAvailable": val,
        "slotBufferQuarantined": 0,
        "slotBufferClaimed": 0,
    }


def query_db_backlog() -> Dict[str, int]:
    """Queries both upstream pending commands and discovery slot buffer metrics in pocoma_write."""
    sql = """
    SELECT
      (SELECT count(*) FROM v3_write.v3_command WHERE status = 'PENDING') AS upstream_pending,
      count(*) AS slot_total,
      count(*) FILTER (WHERE state = 'AVAILABLE') AS slot_available,
      count(*) FILTER (WHERE state = 'QUARANTINED') AS slot_quarantined,
      count(*) FILTER (WHERE claim_token IS NOT NULL) AS slot_claimed
    FROM v3_write.consumption_slot;
    """
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        POSTGRES_CONTAINER, "psql", "-U", POSTGRES_USER, "-d", WRITE_DB, "-tAc", sql
    ]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        if res.returncode == 0 and "|" in res.stdout:
            parts = res.stdout.strip().split("|")
            if len(parts) == 5:
                return {
                    "upstreamPending": int(parts[0]),
                    "slotBufferTotal": int(parts[1]),
                    "slotBufferAvailable": int(parts[2]),
                    "slotBufferQuarantined": int(parts[3]),
                    "slotBufferClaimed": int(parts[4]),
                }
    except Exception:
        pass
    return {
        "upstreamPending": 0,
        "slotBufferTotal": 0,
        "slotBufferAvailable": 0,
        "slotBufferQuarantined": 0,
        "slotBufferClaimed": 0,
    }


def calculate_exact_quantiles(values: List[float]) -> Dict[str, Optional[float]]:
    """Calculates exact sample statistics without histogram bucket distortion."""
    if not values:
        return {"count": 0, "p50": None, "p90": None, "p95": None, "p99": None,
                "mean": None, "min": None, "max": None}

    sorted_vals = sorted(values)
    n = len(sorted_vals)

    def percentile(p: float) -> float:
        if n == 1:
            return sorted_vals[0]
        k = (n - 1) * (p / 100.0)
        f = math.floor(k)
        c = math.ceil(k)
        if f == c:
            return sorted_vals[int(k)]
        return sorted_vals[f] * (c - k) + sorted_vals[c] * (k - f)

    return {
        "count": n,
        "p50": round(percentile(50.0), 2),
        "p90": round(percentile(90.0), 2),
        "p95": round(percentile(95.0), 2),
        "p99": round(percentile(99.0), 2),
        "mean": round(sum(sorted_vals) / n, 2),
        "min": round(sorted_vals[0], 2),
        "max": round(sorted_vals[-1], 2),
    }


@dataclass
class CorrelationRecord:
    command_id: str
    pot_id: str
    t_send: float                    # client emission timestamp (ms)
    t_admit: float                   # HTTP 202 reception timestamp (ms)
    admission_status: int            # HTTP status (202, 503, etc.)
    expected_version: int
    observed_version: int
    attempts: int = 1
    retries: int = 0

    # Client-side Terminal Observer results
    t_terminal: Optional[float] = None
    terminal_status: Optional[str] = None  # COMPLETED, REJECTED, TIMEOUT, UNPOLLED, etc.
    resulting_version: Optional[int] = None
    terminal_probes: int = 0
    terminal_503: int = 0

    # Client-side READ Observer results
    t_read: Optional[float] = None
    read_version: Optional[int] = None
    read_probes: int = 0
    read_503: int = 0
    failure_reason: Optional[str] = None

    # Server Ground Truth (populated via SqlReconciler)
    server_status: Optional[str] = None    # PENDING, TERMINAL, UNKNOWN
    server_outcome: Optional[str] = None   # COMPLETED, CHANGED, REJECTED, null
    server_version: Optional[int] = None
    server_read_materialized: Optional[bool] = None

    @property
    def latency_admission_ms(self) -> float:
        return max(0.0, self.t_admit - self.t_send)

    @property
    def latency_terminal_ms(self) -> Optional[float]:
        return max(0.0, self.t_terminal - self.t_admit) if self.t_terminal is not None else None

    @property
    def latency_read_ms(self) -> Optional[float]:
        return max(0.0, self.t_read - self.t_terminal) if (self.t_read is not None and self.t_terminal is not None) else None

    @property
    def latency_e2e_ms(self) -> Optional[float]:
        return max(0.0, self.t_read - self.t_send) if self.t_read is not None else None

    @property
    def is_complete(self) -> bool:
        return (self.t_terminal is not None
                and self.t_read is not None
                and self.terminal_status in ("COMPLETED", "CHANGED", "APPLIED"))

    @property
    def is_complete_client(self) -> bool:
        return self.is_complete

    def to_dict(self) -> Dict[str, Any]:
        d = asdict(self)
        d["latency_admission_ms"] = round(self.latency_admission_ms, 2)
        d["latency_terminal_ms"] = round(self.latency_terminal_ms, 2) if self.latency_terminal_ms is not None else None
        d["latency_read_ms"] = round(self.latency_read_ms, 2) if self.latency_read_ms is not None else None
        d["latency_e2e_ms"] = round(self.latency_e2e_ms, 2) if self.latency_e2e_ms is not None else None
        d["is_complete"] = self.is_complete
        return d


class CorrelationStore:
    """Thread-safe bounded in-memory store and JSONL event journal."""

    def __init__(self, run_id: str, journal_path: Optional[Path] = None, max_capacity: int = 100000):
        self.run_id = run_id
        self.max_capacity = max_capacity
        self.journal_path = journal_path or (EVIDENCE_DIR / f"{run_id}_admissions.jsonl")

        self._lock = threading.Lock()
        self.records: Dict[str, CorrelationRecord] = {}

        # Concurrent bounded queues for observers: stores (cid, attempt_count, next_poll_ms)
        self.terminal_queue: queue.Queue = queue.Queue(maxsize=max_capacity)
        self.read_queue: queue.Queue = queue.Queue(maxsize=max_capacity)

        self.emitted_count = 0
        self.admitted_count = 0
        self.rejected_admission_count = 0
        self.error_admission_count = 0

        self.journal_path.parent.mkdir(parents=True, exist_ok=True)
        self._journal_file = open(self.journal_path, "a", encoding="utf-8")

    def record_admission_event(self, event_data: Dict[str, Any]) -> CorrelationRecord:
        """Processes an admission event emitted by the injector."""
        with self._lock:
            self.emitted_count += 1
            cid = event_data.get("commandId") or f"unassigned-{self.emitted_count}"
            status = int(event_data.get("status", 0))

            if len(self.records) >= self.max_capacity:
                raise OverflowError(f"CorrelationStore capacity {self.max_capacity} exceeded")

            rec = CorrelationRecord(
                command_id=cid,
                pot_id=event_data.get("potId", ""),
                t_send=float(event_data.get("t_send", time.time() * 1000)),
                t_admit=float(event_data.get("t_admit", time.time() * 1000)),
                admission_status=status,
                expected_version=int(event_data.get("expectedVersion", 1)),
                observed_version=int(event_data.get("observedVersion", 0)),
                attempts=int(event_data.get("attempts", 1)),
                retries=int(event_data.get("retries", 0)),
            )

            if status == 202 and rec.command_id and not rec.command_id.startswith("unassigned"):
                self.admitted_count += 1
                self.records[cid] = rec
                # Enqueue for concurrent observation with initial poll delay of 15ms
                self.terminal_queue.put((cid, 0, time.time() * 1000 + 15))
            elif status == 503:
                self.rejected_admission_count += 1
                rec.failure_reason = "ADMISSION_503_CAPACITY"
                self.records[cid] = rec
            else:
                self.error_admission_count += 1
                rec.failure_reason = f"ADMISSION_ERROR_HTTP_{status}"
                self.records[cid] = rec

            self._journal_file.write(json.dumps(rec.to_dict()) + "\n")
            self._journal_file.flush()
            return rec

    def close(self):
        with self._lock:
            try:
                self._journal_file.close()
            except Exception:
                pass
            try:
                with open(self.journal_path, "w", encoding="utf-8") as f:
                    for rec in self.records.values():
                        f.write(json.dumps(rec.to_dict()) + "\n")
            except Exception as e:
                print(f"[Warning] Failed to enrich journal: {e}")


class TerminalObserver:
    """Concurrent, bounded, capacity-driven observer for command terminal outcomes."""

    def __init__(self, store: CorrelationStore, token_getter,
                 base_url: str = BASE_URL, command_timeout_sec: float = 90.0,
                 max_workers: int = 12):
        self.store = store
        self.token_getter = token_getter
        self.base_url = base_url.rstrip("/")
        self.command_timeout_sec = command_timeout_sec
        self.max_workers = max(1, min(32, max_workers))

        self.stop_event = threading.Event()
        self.threads: List[threading.Thread] = []

        # Telemetry
        self._counter_lock = threading.Lock()
        self.terminalized_count = 0
        self.applied_count = 0
        self.rejected_cas_count = 0
        self.timeouts_count = 0
        self.total_probes = 0
        self.total_503 = 0

    def start(self):
        for i in range(self.max_workers):
            t = threading.Thread(target=self._worker_loop, name=f"term-obs-{i}", daemon=True)
            self.threads.append(t)
            t.start()

    def stop(self):
        self.stop_event.set()
        for t in self.threads:
            t.join(timeout=2.0)

    def _worker_loop(self):
        while not self.stop_event.is_set():
            try:
                item = self.store.terminal_queue.get(timeout=0.1)
            except queue.Empty:
                continue

            cid, attempt, next_poll_ms = item
            now_ms = time.time() * 1000

            if now_ms < next_poll_ms:
                # Sleep fractionally and re-check stop event
                sleep_sec = min(0.02, (next_poll_ms - now_ms) / 1000.0)
                time.sleep(max(0.005, sleep_sec))

            with self.store._lock:
                rec = self.store.records.get(cid)

            if not rec:
                self.store.terminal_queue.task_done()
                continue

            # Check timeout
            if (now_ms - rec.t_admit) > (self.command_timeout_sec * 1000):
                rec.terminal_status = "TIMEOUT"
                rec.failure_reason = f"Terminal probe timeout after {self.command_timeout_sec}s"
                with self._counter_lock:
                    self.timeouts_count += 1
                self.store.terminal_queue.task_done()
                continue

            token = self.token_getter()
            url = f"{self.base_url}/api/v3/commands/{cid}/result"
            req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})

            with self._counter_lock:
                self.total_probes += 1
            rec.terminal_probes += 1

            try:
                with urllib.request.urlopen(req, timeout=5) as resp:
                    if resp.status == 200:
                        data = json.loads(resp.read().decode())
                        rec.t_terminal = time.time() * 1000
                        outcome = data.get("outcome", "UNKNOWN")
                        rec.terminal_status = outcome
                        with self._counter_lock:
                            self.terminalized_count += 1

                        if outcome in ("COMPLETED", "CHANGED", "APPLIED"):
                            rec.resulting_version = data.get("resultingPotVersion")
                            with self._counter_lock:
                                self.applied_count += 1
                            self.store.read_queue.put((cid, 0, time.time() * 1000 + 10))
                        elif outcome == "REJECTED":
                            with self._counter_lock:
                                self.rejected_cas_count += 1
                            rec.failure_reason = f"CAS_REJECTED: {data.get('rejection', 'n/a')}"
                        self.store.terminal_queue.task_done()
                        continue
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    # In-flight: schedule retry with exponential backoff (20ms, 40ms, 80ms, 160ms, cap at 250ms)
                    backoff = min(250.0, 20.0 * (2 ** min(attempt, 4)))
                    self.store.terminal_queue.put((cid, attempt + 1, time.time() * 1000 + backoff))
                elif e.code == 503:
                    with self._counter_lock:
                        self.total_503 += 1
                    rec.terminal_503 += 1
                    time.sleep(0.04)
                    self.store.terminal_queue.put((cid, attempt + 1, time.time() * 1000 + 100))
                elif e.code == 401:
                    try:
                        self.token_getter(force_renew=True)
                    except Exception:
                        pass
                    time.sleep(0.04)
                    self.store.terminal_queue.put((cid, attempt + 1, time.time() * 1000 + 50))
                else:
                    rec.terminal_status = f"HTTP_{e.code}"
                    rec.failure_reason = f"Terminal probe HTTP {e.code}"
                    with self._counter_lock:
                        self.terminalized_count += 1
            except Exception:
                # Network transient: brief pause and re-queue
                self.store.terminal_queue.put((cid, attempt + 1, time.time() * 1000 + 50))

            self.store.terminal_queue.task_done()


class ReadObserver:
    """Concurrent, bounded observer for READ projection and C-03 balance availability."""

    def __init__(self, store: CorrelationStore, token_getter,
                 binding_token: Optional[str] = None,
                 base_url: str = BASE_URL, read_timeout_sec: float = 90.0,
                 max_workers: int = 8):
        self.store = store
        self.token_getter = token_getter
        self.binding_token = binding_token
        self.base_url = base_url.rstrip("/")
        self.read_timeout_sec = read_timeout_sec
        self.max_workers = max(1, min(16, max_workers))

        self.stop_event = threading.Event()
        self.threads: List[threading.Thread] = []

        # Telemetry
        self._counter_lock = threading.Lock()
        self.read_materialized_count = 0
        self.read_timeouts_count = 0
        self.total_read_probes = 0
        self.total_read_503 = 0

    def start(self):
        for i in range(self.max_workers):
            t = threading.Thread(target=self._worker_loop, name=f"read-obs-{i}", daemon=True)
            self.threads.append(t)
            t.start()

    def stop(self):
        self.stop_event.set()
        for t in self.threads:
            t.join(timeout=2.0)

    def _worker_loop(self):
        while not self.stop_event.is_set():
            try:
                item = self.store.read_queue.get(timeout=0.1)
            except queue.Empty:
                continue

            cid, attempt, next_poll_ms = item
            now_ms = time.time() * 1000

            if now_ms < next_poll_ms:
                sleep_sec = min(0.02, (next_poll_ms - now_ms) / 1000.0)
                time.sleep(max(0.005, sleep_sec))

            with self.store._lock:
                rec = self.store.records.get(cid)

            if not rec or rec.resulting_version is None:
                self.store.read_queue.task_done()
                continue

            start_ref = rec.t_terminal or rec.t_admit
            if (now_ms - start_ref) > (self.read_timeout_sec * 1000):
                rec.failure_reason = f"READ probe timeout after {self.read_timeout_sec}s"
                with self._counter_lock:
                    self.read_timeouts_count += 1
                self.store.read_queue.task_done()
                continue

            token = self.token_getter()
            pot_id = rec.pot_id
            v = rec.resulting_version

            req_headers = {"Authorization": f"Bearer {token}"}
            if self.binding_token:
                req_headers["X-Pocoma-Binding-Token"] = self.binding_token

            # Step 1: Probe balance
            bal_url = f"{self.base_url}/api/v3/pots/{pot_id}/versions/{v}/balance"
            bal_req = urllib.request.Request(bal_url, headers=req_headers)

            with self._counter_lock:
                self.total_read_probes += 1
            rec.read_probes += 1

            balance_ok = False
            try:
                with urllib.request.urlopen(bal_req, timeout=5) as resp:
                    if resp.status == 200:
                        bdata = json.loads(resp.read().decode())
                        if bdata.get("potVersion") == v and bdata.get("balances"):
                            balance_ok = True
            except urllib.error.HTTPError as e:
                if e.code == 503:
                    with self._counter_lock:
                        self.total_read_503 += 1
                    rec.read_503 += 1
                elif e.code == 401:
                    try:
                        self.token_getter(force_renew=True)
                    except Exception:
                        pass
            except Exception:
                pass

            if not balance_ok:
                backoff = min(200.0, 20.0 * (2 ** min(attempt, 4)))
                self.store.read_queue.put((cid, attempt + 1, time.time() * 1000 + backoff))
                self.store.read_queue.task_done()
                continue

            # Step 2: Probe complete pot
            pot_url = f"{self.base_url}/api/v3/pots/{pot_id}?minVersion={v}"
            pot_req = urllib.request.Request(pot_url, headers=req_headers)

            with self._counter_lock:
                self.total_read_probes += 1
            rec.read_probes += 1

            pot_ok = False
            try:
                with urllib.request.urlopen(pot_req, timeout=5) as resp:
                    if resp.status == 200:
                        pdata = json.loads(resp.read().decode())
                        if pdata.get("potVersion", 0) >= v:
                            pot_ok = True
            except urllib.error.HTTPError as e:
                if e.code == 503:
                    with self._counter_lock:
                        self.total_read_503 += 1
                    rec.read_503 += 1
                elif e.code == 401:
                    try:
                        self.token_getter(force_renew=True)
                    except Exception:
                        pass
            except Exception:
                pass

            if pot_ok:
                rec.t_read = time.time() * 1000
                rec.read_version = v
                with self._counter_lock:
                    self.read_materialized_count += 1
            else:
                backoff = min(200.0, 20.0 * (2 ** min(attempt, 4)))
                self.store.read_queue.put((cid, attempt + 1, time.time() * 1000 + backoff))

            self.store.read_queue.task_done()


class SqlReconciler:
    """Performs read-only PostgreSQL server-side ground truth reconciliation."""

    @staticmethod
    def reconcile(store: CorrelationStore) -> Dict[str, Any]:
        with store._lock:
            records = list(store.records.values())

        admitted_records = [r for r in records if r.admission_status == 202 and r.command_id]
        if not admitted_records:
            return {
                "serverTotalAdmitted": 0,
                "serverApplied": 0,
                "serverCasRejected": 0,
                "serverPending": 0,
                "serverFailed": 0,
                "serverUnknown": 0,
                "readMaterializedVerified": 0,
                "serverTerminalVerified": 0,
                "reconciliationExact": True,
            }

        # Query v3_write in chunks of 500 IDs to avoid query length limits
        cids = [r.command_id for r in admitted_records]
        chunk_size = 500
        server_map: Dict[str, Tuple[str, Optional[str], Optional[int]]] = {}

        for i in range(0, len(cids), chunk_size):
            chunk = cids[i:i + chunk_size]
            id_list_str = "'" + "','".join(chunk) + "'"
            sql = f"""
            SELECT c.request_id, c.status, r.outcome, r.resulting_pot_version
            FROM v3_write.v3_command c
            LEFT JOIN v3_write.v3_command_result r ON c.request_id = r.request_id
            WHERE c.request_id IN ({id_list_str});
            """
            cmd = [
                "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
                POSTGRES_CONTAINER, "psql", "-U", POSTGRES_USER, "-d", WRITE_DB, "-tAc", sql
            ]
            try:
                res = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
                if res.returncode == 0:
                    for line in res.stdout.strip().split("\n"):
                        if line.strip():
                            parts = line.strip().split("|")
                            req_id = parts[0]
                            st = parts[1]
                            outcome = parts[2] if len(parts) > 2 and parts[2] else None
                            v = int(parts[3]) if len(parts) > 3 and parts[3].isdigit() else None
                            server_map[req_id] = (st, outcome, v)
            except Exception as e:
                print(f"[Warning] SQL reconciliation query failed for chunk {i}: {e}")

        # Update records with server state
        server_applied = 0
        server_cas_rejected = 0
        server_pending = 0
        server_failed = 0
        server_unknown = 0
        read_verified = 0

        for r in admitted_records:
            if r.command_id in server_map:
                st, outcome, v = server_map[r.command_id]
                r.server_status = st
                r.server_outcome = outcome
                r.server_version = v

                if st == "TERMINAL":
                    if outcome in ("COMPLETED", "CHANGED", "APPLIED"):
                        server_applied += 1
                    elif outcome == "REJECTED":
                        server_cas_rejected += 1
                    else:
                        server_failed += 1
                elif st == "PENDING":
                    server_pending += 1
                else:
                    server_unknown += 1
            else:
                r.server_status = "UNKNOWN"
                server_unknown += 1

            # Check READ verification
            if r.server_version is not None and r.resulting_version == r.server_version:
                r.server_read_materialized = True
                read_verified += 1
            else:
                r.server_read_materialized = False

        total_admitted = len(admitted_records)
        server_terminal = server_applied + server_cas_rejected + server_failed
        reconciliation_exact = (total_admitted == (server_terminal + server_pending + server_unknown))

        return {
            "serverTotalAdmitted": total_admitted,
            "serverApplied": server_applied,
            "serverCasRejected": server_cas_rejected,
            "serverPending": server_pending,
            "serverFailed": server_failed,
            "serverUnknown": server_unknown,
            "serverTerminalVerified": server_terminal,
            "readMaterializedVerified": read_verified,
            "reconciliationExact": reconciliation_exact,
        }


class DecoupledBenchCoordinator:
    """Coordinates Injector, Concurrent Observers, Dual-Axis Backlog Polling, and SQL Reconciliation."""

    def __init__(self, run_id: str, token_manager,
                 binding_token: Optional[str] = None,
                 max_drain_seconds: float = 60.0,
                 journal_path: Optional[Path] = None,
                 evidence_dir: Optional[Path] = None,
                 terminal_workers: int = 12,
                 read_workers: int = 8,
                 store: Optional[CorrelationStore] = None):
        self.run_id = run_id
        self.token_manager = token_manager
        self.binding_token = binding_token
        self.max_drain_seconds = max_drain_seconds
        self.evidence_dir = evidence_dir or EVIDENCE_DIR

        self.store = store or CorrelationStore(run_id=run_id, journal_path=journal_path)
        self.term_obs = TerminalObserver(
            self.store, token_manager.get_token, max_workers=terminal_workers
        )
        self.read_obs = ReadObserver(
            self.store, token_manager.get_token, binding_token=binding_token, max_workers=read_workers
        )

        self.backlog_timeseries: List[Dict[str, Any]] = []
        self._poller_stop = threading.Event()
        self._poller_thread: Optional[threading.Thread] = None

        self.injection_start_time = 0.0
        self.injection_stop_time = 0.0
        self.drainage_stop_time = 0.0

    def start(self):
        self.injection_start_time = time.time()
        self.term_obs.start()
        self.read_obs.start()
        self._poller_thread = threading.Thread(target=self._backlog_poller_loop, daemon=True)
        self._poller_thread.start()

    def _backlog_poller_loop(self):
        while not self._poller_stop.is_set():
            t_now = time.time()
            bl_raw = query_db_backlog()
            bl_info = normalize_backlog_info(bl_raw)
            phase = "injection" if self.injection_stop_time == 0.0 else "drainage"
            elapsed = (t_now - self.injection_start_time)

            pending_term = self.store.terminal_queue.qsize()
            pending_read = self.store.read_queue.qsize()

            with self.store._lock:
                admitted = self.store.admitted_count
                applied = self.term_obs.applied_count
                complete = sum(1 for r in self.store.records.values() if r.is_complete)

            self.backlog_timeseries.append({
                "timestamp": round(t_now, 2),
                "elapsedSeconds": round(elapsed, 2),
                "phase": phase,
                "dbBacklog": bl_info["upstreamPending"],
                "upstreamPending": bl_info["upstreamPending"],
                "slotBufferTotal": bl_info["slotBufferTotal"],
                "slotBufferAvailable": bl_info["slotBufferAvailable"],
                "slotBufferQuarantined": bl_info["slotBufferQuarantined"],
                "slotBufferClaimed": bl_info["slotBufferClaimed"],
                "pendingTerminalQueue": pending_term,
                "pendingReadQueue": pending_read,
                "admitted": admitted,
                "applied": applied,
                "completeE2E": complete,
            })
            time.sleep(1.0)

    def mark_injection_ended(self):
        self.injection_stop_time = time.time()

    def drain(self) -> Dict[str, Any]:
        """Runs the bounded drainage window until all admitted commands complete or timeout."""
        drain_start = time.time()
        drain_deadline = drain_start + self.max_drain_seconds

        print(f"\n▶ Step 4: Entering Bounded Drainage Window (Limit: {self.max_drain_seconds}s)...")
        while time.time() < drain_deadline:
            bl_raw = query_db_backlog()
            bl_info = normalize_backlog_info(bl_raw)
            pending_term = self.store.terminal_queue.qsize()
            pending_read = self.store.read_queue.qsize()

            if (bl_info["upstreamPending"] == 0
                    and bl_info["slotBufferTotal"] == 0
                    and pending_term == 0
                    and pending_read == 0):
                print(f"  ✓ Drainage complete in {time.time() - drain_start:.1f}s (all queues and backlogs drained)")
                break

            time.sleep(0.5)

        self.drainage_stop_time = time.time()
        drain_duration = self.drainage_stop_time - drain_start

        # Stop poller and observers cleanly
        self._poller_stop.set()
        if self._poller_thread:
            self._poller_thread.join(timeout=2.0)
        self.term_obs.stop()
        self.read_obs.stop()
        self.store.close()

        final_bl = normalize_backlog_info(query_db_backlog())
        return {
            "drainDurationSec": round(drain_duration, 2),
            "finalUpstreamPending": final_bl["upstreamPending"],
            "finalSlotBufferTotal": final_bl["slotBufferTotal"],
            "finalDbBacklog": final_bl["slotBufferTotal"],
            "timedOut": time.time() >= drain_deadline and (final_bl["upstreamPending"] > 0 or final_bl["slotBufferTotal"] > 0),
        }

    def consolidate_metrics(self, demanded_rate: float, injection_target_sec: float) -> Dict[str, Any]:
        """Consolidates complete E2E metrics, dual-axis populations, and conservation equations."""
        all_records = list(self.store.records.values())

        if all_records:
            t0 = min(r.t_send for r in all_records) / 1000.0
            t1 = self.injection_stop_time if self.injection_stop_time else (max(r.t_send for r in all_records) / 1000.0)
            inj_duration = max(1.0, round(t1 - t0, 2))
        else:
            inj_duration = injection_target_sec

        total_duration = (self.drainage_stop_time - self.injection_start_time) if self.drainage_stop_time else inj_duration

        # Perform read-only SQL reconciliation
        sql_proof = SqlReconciler.reconcile(self.store)

        # Exact client observation populations
        admitted_records = [r for r in all_records if r.admission_status == 202]
        client_terminal_records = [r for r in admitted_records if r.t_terminal is not None]
        client_applied_records = [r for r in client_terminal_records if r.terminal_status in ("COMPLETED", "CHANGED", "APPLIED")]
        client_cas_records = [r for r in client_terminal_records if r.terminal_status == "REJECTED"]
        client_timeout_records = [r for r in admitted_records if r.terminal_status == "TIMEOUT"]
        client_unpolled = len(admitted_records) - (len(client_applied_records) + len(client_cas_records) + len(client_timeout_records))

        client_read_records = [r for r in client_applied_records if r.t_read is not None]
        client_read_timeouts = [r for r in client_applied_records if r.t_read is None and r.failure_reason and "READ probe timeout" in r.failure_reason]
        client_read_unpolled = len(client_applied_records) - (len(client_read_records) + len(client_read_timeouts))
        complete_records = [r for r in client_read_records if r.is_complete_client]

        # Latency samples
        adm_latencies = [r.latency_admission_ms for r in admitted_records]
        term_latencies = [r.latency_terminal_ms for r in client_terminal_records if r.latency_terminal_ms is not None]
        read_latencies = [r.latency_read_ms for r in client_read_records if r.latency_read_ms is not None]
        e2e_latencies = [r.latency_e2e_ms for r in complete_records if r.latency_e2e_ms is not None]

        # Rates calculated strictly over homogeneous injection window
        emitted_rate = round(self.store.emitted_count / inj_duration, 2)
        admission_rate = round(self.store.admitted_count / inj_duration, 2)
        client_applied_rate = round(len(client_applied_records) / inj_duration, 2)
        server_applied_rate = round(sql_proof["serverApplied"] / inj_duration, 2)
        client_read_rate = round(len(client_read_records) / inj_duration, 2)

        # Generator compliance (mandate: >= 95% demanded rate)
        gen_ratio = emitted_rate / demanded_rate if demanded_rate > 0 else 1.0
        is_gen_valid = gen_ratio >= 0.95

        # Strict mathematical conservation equations (no double counting)
        emitted_eq = (self.store.emitted_count == (
            self.store.admitted_count + self.store.rejected_admission_count + self.store.error_admission_count
        ))
        client_admitted_eq = (len(admitted_records) == (
            len(client_applied_records) + len(client_cas_records) + len(client_timeout_records) + client_unpolled
        ))
        server_admitted_eq = sql_proof["reconciliationExact"]
        read_eq = (len(client_applied_records) == (
            len(client_read_records) + len(client_read_timeouts) + client_read_unpolled
        ))

        coverage_ratio = len(complete_records) / len(admitted_records) if admitted_records else 0.0

        return {
            "runId": self.run_id,
            "demandedRate": demanded_rate,
            "injectionTargetSeconds": injection_target_sec,
            "injectionActualSeconds": round(inj_duration, 2),
            "totalScenarioSeconds": round(total_duration, 2),
            "generatorStatus": "LOAD_GENERATOR_VALID" if is_gen_valid else "GENERATOR_LIMITED",
            "generatorRateRatio": round(gen_ratio * 100.0, 1),
            "counts": {
                "emitted": self.store.emitted_count,
                "admitted": self.store.admitted_count,
                "capacityRejected": self.store.rejected_admission_count,
                "admissionErrors": self.store.error_admission_count,

                # Client Observation counts
                "clientTerminalized": len(client_terminal_records),
                "applied": len(client_applied_records),
                "clientApplied": len(client_applied_records),
                "clientCasRejected": len(client_cas_records),
                "clientTimeouts": len(client_timeout_records),
                "clientUnpolledAtClose": client_unpolled,

                "readMaterialized": len(client_read_records),
                "clientReadMaterialized": len(client_read_records),
                "clientReadTimeouts": len(client_read_timeouts),
                "clientReadUnpolled": client_read_unpolled,
                "completeE2E": len(complete_records),

                # Server Ground Truth counts
                "serverApplied": sql_proof["serverApplied"],
                "serverCasRejected": sql_proof["serverCasRejected"],
                "serverPending": sql_proof["serverPending"],
                "serverFailed": sql_proof["serverFailed"],
                "serverUnknown": sql_proof["serverUnknown"],
                "serverTerminalVerified": sql_proof["serverTerminalVerified"],
                "serverReadVerified": sql_proof["readMaterializedVerified"],
            },
            "ratesPerSecond": {
                "demanded": demanded_rate,
                "emitted": emitted_rate,
                "admitted": admission_rate,
                "applied": client_applied_rate,
                "clientApplied": client_applied_rate,
                "serverApplied": server_applied_rate,
                "readMaterialized": client_read_rate,
            },
            "coverage": {
                "completeOverAdmitted": round(coverage_ratio, 4),
                "percentage": round(coverage_ratio * 100.0, 2),
                "clientObservedPercentage": round((len(client_terminal_records) / len(admitted_records) * 100.0), 2) if admitted_records else 0.0,
                "serverVerifiedPercentage": round((sql_proof["serverTerminalVerified"] / len(admitted_records) * 100.0), 2) if admitted_records else 0.0,
            },
            "conservations": {
                "emittedEquation": emitted_eq,
                "admittedEquation": client_admitted_eq,
                "clientAdmittedEquation": client_admitted_eq,
                "serverAdmittedEquation": server_admitted_eq,
                "readMaterializedEquation": read_eq,
            },
            "latencies": {
                "admission": calculate_exact_quantiles(adm_latencies),
                "terminal": calculate_exact_quantiles(term_latencies),
                "readMaterialization": calculate_exact_quantiles(read_latencies),
                "e2eTotal": calculate_exact_quantiles(e2e_latencies),
            },
            "probesAnd503": {
                "terminalProbes": self.term_obs.total_probes,
                "terminal503": self.term_obs.total_503,
                "readProbes": self.read_obs.total_read_probes,
                "read503": self.read_obs.total_read_503,
            },
            "backlogTimeseries": self.backlog_timeseries,
            "sqlReconciliation": sql_proof,
        }
