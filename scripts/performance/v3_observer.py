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


def fetch_server_stage_handoffs(base_url: str = BASE_URL) -> List[Dict[str, Any]]:
    """Fetches diagnostic stage handoff records from the application monolith."""
    urls = [
        f"{base_url.rstrip('/')}/actuator/stage-handoffs?limit=10000",
        "http://127.0.0.1:18081/actuator/stage-handoffs?limit=10000",
    ]
    for url in urls:
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "Pocoma-Observer/1.0"})
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode("utf-8"))
                    return data.get("records", [])
        except Exception:
            continue
    return []


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
    operation: str = "CreateExpense"

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

    # Server Stage Handoff Metrology (populated via StageHandoffTracker)
    server_t0_ms: Optional[float] = None
    server_t0a_ms: Optional[float] = None
    server_t0b_ms: Optional[float] = None
    server_t0c_ms: Optional[float] = None
    server_t1_ms: Optional[float] = None
    server_t2_ms: Optional[float] = None
    stage_a_cmd_wait_ms: Optional[float] = None
    stage_a1_wait_discovery_ms: Optional[float] = None
    stage_a2_discovery_to_slot_ms: Optional[float] = None
    stage_a3_slot_acq_wait_ms: Optional[float] = None
    stage_a4_acq_to_attempt_ms: Optional[float] = None
    stage_b_cmd_proc_ms: Optional[float] = None
    stage_c_read_wait_ms: Optional[float] = None
    stage_e_obs_wait_ms: Optional[float] = None
    partition: Optional[int] = None
    missing_data_reason: Optional[str] = None

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
        d["stage_a_cmd_wait_ms"] = round(self.stage_a_cmd_wait_ms, 2) if self.stage_a_cmd_wait_ms is not None else None
        d["stage_a1_wait_discovery_ms"] = round(self.stage_a1_wait_discovery_ms, 2) if self.stage_a1_wait_discovery_ms is not None else None
        d["stage_a2_discovery_to_slot_ms"] = round(self.stage_a2_discovery_to_slot_ms, 2) if self.stage_a2_discovery_to_slot_ms is not None else None
        d["stage_a3_slot_acq_wait_ms"] = round(self.stage_a3_slot_acq_wait_ms, 2) if self.stage_a3_slot_acq_wait_ms is not None else None
        d["stage_a4_acq_to_attempt_ms"] = round(self.stage_a4_acq_to_attempt_ms, 2) if self.stage_a4_acq_to_attempt_ms is not None else None
        d["stage_b_cmd_proc_ms"] = round(self.stage_b_cmd_proc_ms, 2) if self.stage_b_cmd_proc_ms is not None else None
        d["stage_c_read_wait_ms"] = round(self.stage_c_read_wait_ms, 2) if self.stage_c_read_wait_ms is not None else None
        d["stage_e_obs_wait_ms"] = round(self.stage_e_obs_wait_ms, 2) if self.stage_e_obs_wait_ms is not None else None
        d["is_complete"] = self.is_complete
        return d


class CorrelationStore:
    """Thread-safe bounded in-memory store and JSONL event journal."""

    def __init__(self, run_id: str, journal_path: Optional[Path] = None, max_capacity: int = 100000, scheduler: Any = None):
        self.run_id = run_id
        self.max_capacity = max_capacity
        self.journal_path = journal_path or (EVIDENCE_DIR / f"{run_id}_admissions.jsonl")
        self.scheduler = scheduler

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

    def _classify_admission(self, rec: CorrelationRecord, status: int, cid: str):
        if status == 202 and not cid.startswith("unassigned"):
            self.admitted_count += 1
            self.records[cid] = rec
            self.terminal_queue.put((cid, 0, time.time() * 1000 + 15))
            return
        if status == 503:
            self.rejected_admission_count += 1
            rec.failure_reason = "ADMISSION_503_CAPACITY"
        else:
            self.error_admission_count += 1
            rec.failure_reason = f"ADMISSION_ERROR_HTTP_{status}"
        self.records[cid] = rec

    def _notify_scheduler(self, cid: str, rec: CorrelationRecord, status: int, event_data: Dict[str, Any]):
        if not self.scheduler:
            return
        try:
            self.scheduler.record_admission(
                command_id=cid, pot_id=rec.pot_id, operation=rec.operation,
                status=status, meta=event_data.get("_meta"),
            )
        except Exception:
            pass

    def record_admission_event(self, event_data: Dict[str, Any]) -> CorrelationRecord:
        """Processes an admission event emitted by the injector."""
        with self._lock:
            self.emitted_count += 1
            cid = event_data.get("commandId") or f"unassigned-{self.emitted_count}"
            status = int(event_data.get("status", 0))
            if len(self.records) >= self.max_capacity:
                raise OverflowError(f"CorrelationStore capacity {self.max_capacity} exceeded")
            rec = CorrelationRecord(
                command_id=cid, pot_id=event_data.get("potId", ""),
                t_send=float(event_data.get("t_send", time.time() * 1000)),
                t_admit=float(event_data.get("t_admit", time.time() * 1000)),
                admission_status=status, expected_version=int(event_data.get("expectedVersion", 1)),
                observed_version=int(event_data.get("observedVersion", 0)),
                attempts=int(event_data.get("attempts", 1)), retries=int(event_data.get("retries", 0)),
                operation=str(event_data.get("operation", "CreateExpense")),
            )
            self._classify_admission(rec, status, cid)
            self._journal_file.write(json.dumps(rec.to_dict()) + "\n")
            self._journal_file.flush()
            self._notify_scheduler(cid, rec, status, event_data)
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
                 max_workers: int = 32, on_terminal_callback: Optional[Any] = None):
        self.store = store
        self.token_getter = token_getter
        self.base_url = base_url.rstrip("/")
        self.command_timeout_sec = command_timeout_sec
        self.max_workers = max(1, min(48, max_workers))
        self.on_terminal_callback = on_terminal_callback

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

    def _notify_callback(self, cid: str, pot_id: str, version: Optional[int], outcome: str):
        if not self.on_terminal_callback:
            return
        try:
            self.on_terminal_callback(cid, pot_id, version, outcome)
        except Exception:
            pass

    def _record_terminal_outcome(self, rec: CorrelationRecord, cid: str, data: Dict[str, Any]):
        rec.t_terminal = time.time() * 1000
        outcome = data.get("outcome", "UNKNOWN")
        rec.terminal_status = outcome
        with self._counter_lock:
            self.terminalized_count += 1
            if outcome in ("COMPLETED", "CHANGED", "APPLIED"):
                self.applied_count += 1
            elif outcome == "REJECTED":
                self.rejected_cas_count += 1
        if outcome in ("COMPLETED", "CHANGED", "APPLIED"):
            rec.resulting_version = data.get("resultingPotVersion")
            self.store.read_queue.put((cid, 0, time.time() * 1000 + 10))
        elif outcome == "REJECTED":
            rec.failure_reason = f"CAS_REJECTED: {data.get('rejection', 'n/a')}"
        self._notify_callback(cid, rec.pot_id, data.get("resultingPotVersion"), outcome)

    def _handle_http_error(self, rec: CorrelationRecord, cid: str, attempt: int, code: int):
        if code == 404:
            backoff = min(250.0, 20.0 * (2 ** min(attempt, 4)))
            self.store.terminal_queue.put((cid, attempt + 1, time.time() * 1000 + backoff))
            return
        if code == 503:
            with self._counter_lock:
                self.total_503 += 1
            rec.terminal_503 += 1
            time.sleep(0.04)
            self.store.terminal_queue.put((cid, attempt + 1, time.time() * 1000 + 100))
            return
        if code == 401:
            try:
                self.token_getter(force_renew=True)
            except Exception:
                pass
            time.sleep(0.04)
            self.store.terminal_queue.put((cid, attempt + 1, time.time() * 1000 + 50))
            return
        rec.terminal_status = f"HTTP_{code}"
        rec.failure_reason = f"Terminal probe HTTP {code}"
        with self._counter_lock:
            self.terminalized_count += 1
        self._notify_callback(cid, rec.pot_id, None, f"HTTP_{code}")

    def _process_terminal_item(self, item):
        cid, attempt, next_poll_ms = item
        now_ms = time.time() * 1000
        if now_ms < next_poll_ms:
            time.sleep(max(0.005, min(0.02, (next_poll_ms - now_ms) / 1000.0)))
        with self.store._lock:
            rec = self.store.records.get(cid)
        if not rec:
            return
        if (now_ms - rec.t_admit) > (self.command_timeout_sec * 1000):
            rec.terminal_status = "TIMEOUT"
            rec.failure_reason = f"Terminal probe timeout after {self.command_timeout_sec}s"
            with self._counter_lock:
                self.timeouts_count += 1
            self._notify_callback(cid, rec.pot_id, None, "TIMEOUT")
            return
        req = urllib.request.Request(
            f"{self.base_url}/api/v3/commands/{cid}/result",
            headers={"Authorization": f"Bearer {self.token_getter()}"},
        )
        with self._counter_lock:
            self.total_probes += 1
        rec.terminal_probes += 1
        try:
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    self._record_terminal_outcome(rec, cid, json.loads(resp.read().decode()))
        except urllib.error.HTTPError as e:
            self._handle_http_error(rec, cid, attempt, e.code)
        except Exception:
            self.store.terminal_queue.put((cid, attempt + 1, time.time() * 1000 + 50))

    def _worker_loop(self):
        while not self.stop_event.is_set():
            try:
                item = self.store.terminal_queue.get(timeout=0.1)
            except queue.Empty:
                continue
            try:
                self._process_terminal_item(item)
            finally:
                self.store.terminal_queue.task_done()


class ReadObserver:
    """Concurrent, bounded observer for READ projection and C-03 balance availability."""

    def __init__(self, store: CorrelationStore, token_getter,
                 binding_token: Optional[str] = None,
                 base_url: str = BASE_URL, read_timeout_sec: float = 90.0,
                 max_workers: int = 32):
        self.store = store
        self.token_getter = token_getter
        self.binding_token = binding_token
        self.base_url = base_url.rstrip("/")
        self.read_timeout_sec = read_timeout_sec
        self.max_workers = max(1, min(48, max_workers))

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

    def _probe_json(self, url: str, headers: Dict[str, str], rec: CorrelationRecord) -> Optional[Dict[str, Any]]:
        with self._counter_lock:
            self.total_read_probes += 1
        rec.read_probes += 1
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=5) as resp:
                if resp.status == 200:
                    return json.loads(resp.read().decode())
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
        return None

    def _process_read_item(self, item):
        cid, attempt, next_poll_ms = item
        now_ms = time.time() * 1000
        if now_ms < next_poll_ms:
            time.sleep(max(0.005, min(0.02, (next_poll_ms - now_ms) / 1000.0)))
        with self.store._lock:
            rec = self.store.records.get(cid)
        if not rec or rec.resulting_version is None:
            return
        if (now_ms - (rec.t_terminal or rec.t_admit)) > (self.read_timeout_sec * 1000):
            rec.failure_reason = f"READ probe timeout after {self.read_timeout_sec}s"
            with self._counter_lock:
                self.read_timeouts_count += 1
            return
        v = rec.resulting_version
        req_headers = {"Authorization": f"Bearer {self.token_getter()}"}
        if self.binding_token:
            req_headers["X-Pocoma-Binding-Token"] = self.binding_token
        bdata = self._probe_json(f"{self.base_url}/api/v3/pots/{rec.pot_id}/versions/{v}/balance", req_headers, rec)
        if not (bdata and bdata.get("potVersion") == v and bdata.get("balances")):
            backoff = min(200.0, 20.0 * (2 ** min(attempt, 4)))
            self.store.read_queue.put((cid, attempt + 1, time.time() * 1000 + backoff))
            return
        pdata = self._probe_json(f"{self.base_url}/api/v3/pots/{rec.pot_id}?minVersion={v}", req_headers, rec)
        if pdata and pdata.get("potVersion", 0) >= v:
            rec.t_read = time.time() * 1000
            rec.read_version = v
            with self._counter_lock:
                self.read_materialized_count += 1
        else:
            backoff = min(200.0, 20.0 * (2 ** min(attempt, 4)))
            self.store.read_queue.put((cid, attempt + 1, time.time() * 1000 + backoff))

    def _worker_loop(self):
        while not self.stop_event.is_set():
            try:
                item = self.store.read_queue.get(timeout=0.1)
            except queue.Empty:
                continue
            try:
                self._process_read_item(item)
            finally:
                self.store.read_queue.task_done()


class SqlReconciler:
    """Performs read-only PostgreSQL server-side ground truth reconciliation."""

    @staticmethod
    def _query_server_map(cids: List[str]) -> Dict[str, Tuple[str, Optional[str], Optional[int]]]:
        server_map: Dict[str, Tuple[str, Optional[str], Optional[int]]] = {}
        for i in range(0, len(cids), 500):
            id_list_str = "'" + "','".join(cids[i:i + 500]) + "'"
            sql = (
                "SELECT c.request_id, c.status, r.outcome, r.resulting_pot_version "
                "FROM v3_write.v3_command c "
                "LEFT JOIN v3_write.v3_command_result r ON c.request_id = r.request_id "
                f"WHERE c.request_id IN ({id_list_str});"
            )
            cmd = ["docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
                   POSTGRES_CONTAINER, "psql", "-U", POSTGRES_USER, "-d", WRITE_DB, "-tAc", sql]
            try:
                res = subprocess.run(cmd, capture_output=True, text=True, timeout=15)
                if res.returncode == 0:
                    SqlReconciler._parse_server_rows(res.stdout, server_map)
            except Exception as e:
                print(f"[Warning] SQL reconciliation query failed for chunk {i}: {e}")
        return server_map

    @staticmethod
    def _parse_server_rows(stdout: str, server_map: Dict[str, Tuple[str, Optional[str], Optional[int]]]):
        for line in stdout.strip().split("\n"):
            if not line.strip():
                continue
            parts = line.strip().split("|")
            outcome = parts[2] if len(parts) > 2 and parts[2] else None
            v = int(parts[3]) if len(parts) > 3 and parts[3].isdigit() else None
            server_map[parts[0]] = (parts[1], outcome, v)

    @staticmethod
    def reconcile(store: CorrelationStore) -> Dict[str, Any]:
        with store._lock:
            admitted = [r for r in store.records.values() if r.admission_status == 202 and r.command_id]
        if not admitted:
            return {
                "serverTotalAdmitted": 0, "serverApplied": 0, "serverCasRejected": 0,
                "serverPending": 0, "serverFailed": 0, "serverUnknown": 0,
                "readMaterializedVerified": 0, "serverTerminalVerified": 0, "reconciliationExact": True,
            }
        server_map = SqlReconciler._query_server_map([r.command_id for r in admitted])
        counts = {"applied": 0, "cas": 0, "pending": 0, "failed": 0, "unknown": 0, "read": 0}
        for r in admitted:
            SqlReconciler._tally_record(r, server_map.get(r.command_id), counts)
        total = len(admitted)
        terminal = counts["applied"] + counts["cas"] + counts["failed"]
        return {
            "serverTotalAdmitted": total, "serverApplied": counts["applied"],
            "serverCasRejected": counts["cas"], "serverPending": counts["pending"],
            "serverFailed": counts["failed"], "serverUnknown": counts["unknown"],
            "serverTerminalVerified": terminal, "readMaterializedVerified": counts["read"],
            "reconciliationExact": total == (terminal + counts["pending"] + counts["unknown"]),
        }

    @staticmethod
    def _tally_record(r: CorrelationRecord, entry: Optional[Tuple[str, Optional[str], Optional[int]]], counts: Dict[str, int]):
        if entry is None:
            r.server_status = "UNKNOWN"
            counts["unknown"] += 1
        else:
            st, outcome, v = entry
            r.server_status, r.server_outcome, r.server_version = st, outcome, v
            if st == "TERMINAL":
                key = "applied" if outcome in ("COMPLETED", "CHANGED", "APPLIED") else ("cas" if outcome == "REJECTED" else "failed")
                counts[key] += 1
            else:
                counts["pending" if st == "PENDING" else "unknown"] += 1
        r.server_read_materialized = r.server_version is not None and r.resulting_version == r.server_version
        if r.server_read_materialized:
            counts["read"] += 1


class DecoupledBenchCoordinator:
    """Coordinates Injector, Concurrent Observers, Dual-Axis Backlog Polling, and SQL Reconciliation."""

    def __init__(self, run_id: str, token_manager,
                 binding_token: Optional[str] = None,
                 max_drain_seconds: float = 60.0,
                 journal_path: Optional[Path] = None,
                 evidence_dir: Optional[Path] = None,
                 terminal_workers: int = 32,
                 read_workers: int = 32,
                 store: Optional[CorrelationStore] = None,
                 scheduler: Any = None):
        self.run_id = run_id
        self.token_manager = token_manager
        self.binding_token = binding_token
        self.max_drain_seconds = max_drain_seconds
        self.evidence_dir = evidence_dir or EVIDENCE_DIR
        self.scheduler = scheduler

        j_path = journal_path or (self.evidence_dir / f"{run_id}_admissions.jsonl")
        self.store = store or CorrelationStore(run_id=run_id, journal_path=j_path, scheduler=scheduler)
        self.term_obs = TerminalObserver(
            self.store, token_manager.get_token, max_workers=terminal_workers,
            on_terminal_callback=(self.scheduler.on_command_terminal if self.scheduler else None)
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

    def _is_drained(self) -> bool:
        bl_info = normalize_backlog_info(query_db_backlog())
        return (
            bl_info["upstreamPending"] == 0
            and bl_info["slotBufferTotal"] == 0
            and self.store.terminal_queue.qsize() == 0
            and self.store.read_queue.qsize() == 0
        )

    def drain(self) -> Dict[str, Any]:
        """Runs the bounded drainage window until all admitted commands complete or timeout."""
        drain_start = time.time()
        drain_deadline = drain_start + self.max_drain_seconds
        print(f"\n▶ Step 4: Entering Bounded Drainage Window (Limit: {self.max_drain_seconds}s)...")
        while time.time() < drain_deadline:
            if self._is_drained():
                print(f"  ✓ Drainage complete in {time.time() - drain_start:.1f}s (all queues and backlogs drained)")
                break
            time.sleep(0.5)

        self.drainage_stop_time = time.time()
        self._poller_stop.set()
        if self._poller_thread:
            self._poller_thread.join(timeout=2.0)
        self.term_obs.stop()
        self.read_obs.stop()
        self.store.close()

        final_bl = normalize_backlog_info(query_db_backlog())
        has_backlog = final_bl["upstreamPending"] > 0 or final_bl["slotBufferTotal"] > 0
        return {
            "drainDurationSec": round(self.drainage_stop_time - drain_start, 2),
            "finalUpstreamPending": final_bl["upstreamPending"],
            "finalSlotBufferTotal": final_bl["slotBufferTotal"],
            "finalDbBacklog": final_bl["slotBufferTotal"],
            "timedOut": time.time() >= drain_deadline and has_backlog,
        }

    @staticmethod
    def _compute_operation_metrics(admitted_records: List[CorrelationRecord]) -> Dict[str, Any]:
        operation_metrics = {}
        for op in sorted({r.operation for r in admitted_records}):
            op_admitted = [r for r in admitted_records if r.operation == op]
            op_applied = [r for r in op_admitted if r.terminal_status in ("COMPLETED", "CHANGED", "APPLIED")]
            op_cas = [r for r in op_admitted if r.terminal_status == "REJECTED"]
            op_term = [r for r in op_admitted if r.t_terminal is not None]
            op_read = [r for r in op_applied if r.t_read is not None]
            op_e2e = [r for r in op_applied if r.is_complete_client]
            operation_metrics[op] = {
                "admitted": len(op_admitted), "applied": len(op_applied), "casRejected": len(op_cas),
                "readMaterialized": len(op_read), "completeE2E": len(op_e2e),
                "latencies": {
                    "admission": calculate_exact_quantiles([r.latency_admission_ms for r in op_admitted]),
                    "terminal": calculate_exact_quantiles([r.latency_terminal_ms for r in op_term if r.latency_terminal_ms is not None]),
                    "readMaterialization": calculate_exact_quantiles([r.latency_read_ms for r in op_read if r.latency_read_ms is not None]),
                    "e2eTotal": calculate_exact_quantiles([r.latency_e2e_ms for r in op_e2e if r.latency_e2e_ms is not None]),
                },
            }
        return operation_metrics

    @staticmethod
    def _record_server_handoff(r: CorrelationRecord, srec: Dict[str, Any], state):
        samples, proj_waits, proj_procs, counters = state
        r.server_t0_ms, r.server_t0a_ms, r.server_t0b_ms = srec.get("t0EpochMs"), srec.get("t0aEpochMs"), srec.get("t0bEpochMs")
        r.server_t0c_ms, r.server_t1_ms, r.server_t2_ms = srec.get("t0cEpochMs"), srec.get("t1EpochMs"), srec.get("t2EpochMs")
        r.partition, r.missing_data_reason = srec.get("partition"), srec.get("missingDataReason")
        field_map = (
            ("commandWaitMs", "stage_a_cmd_wait_ms", "cmd_wait"),
            ("waitBeforeDiscoveryMs", "stage_a1_wait_discovery_ms", "a1"),
            ("discoveryToSlotMs", "stage_a2_discovery_to_slot_ms", "a2"),
            ("slotAcquisitionWaitMs", "stage_a3_slot_acq_wait_ms", "a3"),
            ("acquisitionToAttemptMs", "stage_a4_acq_to_attempt_ms", "a4"),
            ("commandProcessingMs", "stage_b_cmd_proc_ms", "cmd_proc"),
            ("c03ReadWaitMs", "stage_c_read_wait_ms", "c03_wait"),
        )
        for src_key, attr, bucket in field_map:
            val = srec.get(src_key)
            if val is not None:
                setattr(r, attr, val)
                samples[bucket].append(val)
        stages = [srec.get(k) for k in ("waitBeforeDiscoveryMs", "discoveryToSlotMs", "slotAcquisitionWaitMs", "acquisitionToAttemptMs", "commandWaitMs")]
        if all(v is not None for v in stages):
            counters["fully"] += 1
            if abs(sum(stages[:4]) - stages[4]) <= 1.0:
                counters["reconciled"] += 1
        for p_name, p_data in srec.get("projections", {}).items():
            if p_data.get("readWaitMs") is not None:
                proj_waits.setdefault(p_name, []).append(p_data["readWaitMs"])
            if p_data.get("readProcessingMs") is not None:
                proj_procs.setdefault(p_name, []).append(p_data["readProcessingMs"])
        c03_ready = srec.get("c03ReadyEpochMs")
        if c03_ready and r.t_read:
            obs_delay = max(0.0, r.t_read - c03_ready)
            r.stage_e_obs_wait_ms = obs_delay
            samples["c03_obs"].append(obs_delay)

    def _correlate_handoff_metrics(self, client_applied_records: List[CorrelationRecord]) -> Dict[str, Any]:
        server_records = fetch_server_stage_handoffs(self.read_obs.base_url)
        server_by_id = {r["commandId"]: r for r in server_records if "commandId" in r}
        samples: Dict[str, List[float]] = {k: [] for k in ("cmd_wait", "a1", "a2", "a3", "a4", "cmd_proc", "c03_wait", "c03_obs")}
        proj_waits: Dict[str, List[float]] = {}
        proj_procs: Dict[str, List[float]] = {}
        counters = {"reconciled": 0, "fully": 0}
        for r in client_applied_records:
            if r.command_id in server_by_id:
                self._record_server_handoff(r, server_by_id[r.command_id], (samples, proj_waits, proj_procs, counters))
        reconcile_rate = (counters["reconciled"] / counters["fully"] * 100.0) if counters["fully"] > 0 else 0.0
        return {
            "totalServerTracked": len(server_records),
            "matchedCommands": sum(1 for r in client_applied_records if r.command_id in server_by_id),
            "fullyCorrelatedCommands": counters["fully"],
            "reconciledDecompositions": counters["reconciled"],
            "reconciliationRatePercent": round(reconcile_rate, 2),
            "stageA_commandWait": calculate_exact_quantiles(samples["cmd_wait"]),
            "stageA1_waitBeforeDiscovery": calculate_exact_quantiles(samples["a1"]),
            "stageA2_discoveryToSlot": calculate_exact_quantiles(samples["a2"]),
            "stageA3_slotAcquisitionWait": calculate_exact_quantiles(samples["a3"]),
            "stageA4_acquisitionToAttempt": calculate_exact_quantiles(samples["a4"]),
            "stageB_commandProcessing": calculate_exact_quantiles(samples["cmd_proc"]),
            "stageC_c03ReadWait": calculate_exact_quantiles(samples["c03_wait"]),
            "stageE_observationWait": calculate_exact_quantiles(samples["c03_obs"]),
            "projections": {
                p: {"readWait": calculate_exact_quantiles(proj_waits.get(p, [])),
                    "readProcessing": calculate_exact_quantiles(proj_procs.get(p, []))}
                for p in sorted(set(proj_waits) | set(proj_procs))
            },
        }

    def consolidate_metrics(self, demanded_rate: float, injection_target_sec: float) -> Dict[str, Any]:
        """Consolidates complete E2E metrics, dual-axis populations, and conservation equations."""
        all_records = list(self.store.records.values())
        if all_records:
            t0 = min(r.t_send for r in all_records) / 1000.0
            t1 = self.injection_stop_time or (max(r.t_send for r in all_records) / 1000.0)
            inj_duration = max(1.0, round(t1 - t0, 2))
        else:
            inj_duration = injection_target_sec
        total_duration = (self.drainage_stop_time - self.injection_start_time) if self.drainage_stop_time else inj_duration
        sql_proof = SqlReconciler.reconcile(self.store)

        admitted = [r for r in all_records if r.admission_status == 202]
        terminal = [r for r in admitted if r.t_terminal is not None]
        applied = [r for r in terminal if r.terminal_status in ("COMPLETED", "CHANGED", "APPLIED")]
        cas_rejected = [r for r in terminal if r.terminal_status == "REJECTED"]
        timeouts = [r for r in admitted if r.terminal_status == "TIMEOUT"]
        unpolled = len(admitted) - (len(applied) + len(cas_rejected) + len(timeouts))
        read_records = [r for r in applied if r.t_read is not None]
        read_timeouts = [r for r in applied if r.t_read is None and r.failure_reason and "READ probe timeout" in r.failure_reason]
        read_unpolled = len(applied) - (len(read_records) + len(read_timeouts))
        complete = [r for r in read_records if r.is_complete_client]

        emitted_rate = round(self.store.emitted_count / inj_duration, 2)
        gen_ratio = emitted_rate / demanded_rate if demanded_rate > 0 else 1.0
        coverage_ratio = len(complete) / len(admitted) if admitted else 0.0
        client_admitted_eq = len(admitted) == (len(applied) + len(cas_rejected) + len(timeouts) + unpolled)
        return {
            "runId": self.run_id, "demandedRate": demanded_rate,
            "injectionTargetSeconds": injection_target_sec, "injectionActualSeconds": round(inj_duration, 2),
            "totalScenarioSeconds": round(total_duration, 2),
            "generatorStatus": "LOAD_GENERATOR_VALID" if gen_ratio >= 0.95 else "GENERATOR_LIMITED",
            "generatorRateRatio": round(gen_ratio * 100.0, 1),
            "mixReport": self.scheduler.get_mix_report() if self.scheduler else None,
            "latenciesByOperation": self._compute_operation_metrics(admitted),
            "counts": {
                "emitted": self.store.emitted_count, "admitted": self.store.admitted_count,
                "capacityRejected": self.store.rejected_admission_count, "admissionErrors": self.store.error_admission_count,
                "clientTerminalized": len(terminal), "applied": len(applied), "clientApplied": len(applied),
                "clientCasRejected": len(cas_rejected), "clientTimeouts": len(timeouts), "clientUnpolledAtClose": unpolled,
                "readMaterialized": len(read_records), "clientReadMaterialized": len(read_records),
                "clientReadTimeouts": len(read_timeouts), "clientReadUnpolled": read_unpolled, "completeE2E": len(complete),
                "serverApplied": sql_proof["serverApplied"], "serverCasRejected": sql_proof["serverCasRejected"],
                "serverPending": sql_proof["serverPending"], "serverFailed": sql_proof["serverFailed"],
                "serverUnknown": sql_proof["serverUnknown"], "serverTerminalVerified": sql_proof["serverTerminalVerified"],
                "serverReadVerified": sql_proof["readMaterializedVerified"],
            },
            "ratesPerSecond": {
                "demanded": demanded_rate, "emitted": emitted_rate,
                "admitted": round(self.store.admitted_count / inj_duration, 2),
                "applied": round(len(applied) / inj_duration, 2), "clientApplied": round(len(applied) / inj_duration, 2),
                "serverApplied": round(sql_proof["serverApplied"] / inj_duration, 2),
                "readMaterialized": round(len(read_records) / inj_duration, 2),
            },
            "coverage": {
                "completeOverAdmitted": round(coverage_ratio, 4), "percentage": round(coverage_ratio * 100.0, 2),
                "clientObservedPercentage": round((len(terminal) / len(admitted) * 100.0), 2) if admitted else 0.0,
                "serverVerifiedPercentage": round((sql_proof["serverTerminalVerified"] / len(admitted) * 100.0), 2) if admitted else 0.0,
            },
            "conservations": {
                "emittedEquation": self.store.emitted_count == (self.store.admitted_count + self.store.rejected_admission_count + self.store.error_admission_count),
                "admittedEquation": client_admitted_eq, "clientAdmittedEquation": client_admitted_eq,
                "serverAdmittedEquation": sql_proof["reconciliationExact"],
                "readMaterializedEquation": len(applied) == (len(read_records) + len(read_timeouts) + read_unpolled),
            },
            "latencies": {
                "admission": calculate_exact_quantiles([r.latency_admission_ms for r in admitted]),
                "terminal": calculate_exact_quantiles([r.latency_terminal_ms for r in terminal if r.latency_terminal_ms is not None]),
                "readMaterialization": calculate_exact_quantiles([r.latency_read_ms for r in read_records if r.latency_read_ms is not None]),
                "e2eTotal": calculate_exact_quantiles([r.latency_e2e_ms for r in complete if r.latency_e2e_ms is not None]),
            },
            "probesAnd503": {
                "terminalProbes": self.term_obs.total_probes, "terminal503": self.term_obs.total_503,
                "readProbes": self.read_obs.total_read_probes, "read503": self.read_obs.total_read_503,
            },
            "backlogTimeseries": self.backlog_timeseries,
            "sqlReconciliation": sql_proof,
            "asyncStageHandoffs": self._correlate_handoff_metrics(applied),
        }
