#!/usr/bin/env python3
"""Pocoma V3 — Decoupled Correlation Store, Observers & Drainage Engine (scripts/performance/v3_observer.py).

Implements Phase B, C, E requirements:
1. Complete decoupling: Injector -> Correlation Store -> Terminal Observer / Read Observer.
2. Zero coupling of injection throughput with READ polling or 503 capacity refusals.
3. Strict end-to-end command correlation (commandId, potId, t_send, t_admit, t_terminal, t_read).
4. Strictly coherent statistical populations (no population blending).
5. Exact quantiles (p50, p90, p95, p99, mean, min, max) without bucket approximations.
6. Real-time PostgreSQL consumption_slot backlog timeseries tracking and bounded drainage.
"""

from collections import deque
from dataclasses import asdict, dataclass, field
import json
import math
import os
from pathlib import Path
import subprocess
import threading
import time
from typing import Any, Dict, List, Optional, Tuple
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


def query_db_backlog() -> int:
    """Queries current pending consumption_slot count in pocoma_write."""
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        POSTGRES_CONTAINER, "psql", "-U", POSTGRES_USER, "-d", WRITE_DB, "-tAc",
        "SELECT count(*) FROM v3_write.consumption_slot;"
    ]
    try:
        res = subprocess.run(cmd, capture_output=True, text=True, timeout=5)
        if res.returncode == 0 and res.stdout.strip().isdigit():
            return int(res.stdout.strip())
    except Exception:
        pass
    return 0


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

    # Terminal Observer results
    t_terminal: Optional[float] = None
    terminal_status: Optional[str] = None  # APPLIED, REJECTED, TIMEOUT, etc.
    resulting_version: Optional[int] = None
    terminal_probes: int = 0
    terminal_503: int = 0

    # READ Observer results
    t_read: Optional[float] = None
    read_version: Optional[int] = None
    read_probes: int = 0
    read_503: int = 0
    failure_reason: Optional[str] = None

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

    def __init__(self, run_id: str, journal_path: Optional[Path] = None, max_capacity: int = 50000):
        self.run_id = run_id
        self.max_capacity = max_capacity
        self.journal_path = journal_path or (EVIDENCE_DIR / f"{run_id}_admissions.jsonl")

        self._lock = threading.Lock()
        self.records: Dict[str, CorrelationRecord] = {}
        self.terminal_queue: deque = deque()
        self.read_queue: deque = deque()

        self.emitted_count = 0
        self.admitted_count = 0
        self.rejected_admission_count = 0
        self.error_admission_count = 0

        # Ensure journal directory exists and initialize file
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
                self.terminal_queue.append(cid)
            elif status == 503:
                self.rejected_admission_count += 1
                rec.failure_reason = "ADMISSION_503_CAPACITY"
                self.records[cid] = rec
            else:
                self.error_admission_count += 1
                rec.failure_reason = f"ADMISSION_ERROR_HTTP_{status}"
                self.records[cid] = rec

            # Persist to journal immediately
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
    """Observes command terminal results with controlled concurrency to prevent READ saturation."""

    def __init__(self, store: CorrelationStore, token_getter,
                 base_url: str = BASE_URL, command_timeout_sec: float = 90.0,
                 probe_interval_sec: float = 0.015):
        self.store = store
        self.token_getter = token_getter
        self.base_url = base_url.rstrip("/")
        self.command_timeout_sec = command_timeout_sec
        self.probe_interval_sec = probe_interval_sec

        self.stop_event = threading.Event()
        self.thread: Optional[threading.Thread] = None

        # Telemetry
        self.terminalized_count = 0
        self.applied_count = 0
        self.rejected_cas_count = 0
        self.timeouts_count = 0
        self.total_probes = 0
        self.total_503 = 0

    def start(self):
        self.thread = threading.Thread(target=self._run_loop, daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=3.0)

    def _run_loop(self):
        while not self.stop_event.is_set():
            cid = None
            with self.store._lock:
                if self.store.terminal_queue:
                    cid = self.store.terminal_queue.popleft()

            if not cid:
                time.sleep(self.probe_interval_sec)
                continue

            with self.store._lock:
                rec = self.store.records.get(cid)

            if not rec:
                continue

            now = time.time() * 1000
            if (now - rec.t_admit) > (self.command_timeout_sec * 1000):
                rec.terminal_status = "TIMEOUT"
                rec.failure_reason = f"Terminal probe timeout after {self.command_timeout_sec}s"
                self.timeouts_count += 1
                continue

            # Poll result endpoint
            token = self.token_getter()
            url = f"{self.base_url}/api/v3/commands/{cid}/result"
            req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})

            self.total_probes += 1
            rec.terminal_probes += 1

            try:
                with urllib.request.urlopen(req, timeout=5) as resp:
                    if resp.status == 200:
                        data = json.loads(resp.read().decode())
                        rec.t_terminal = time.time() * 1000
                        outcome = data.get("outcome", "UNKNOWN")
                        rec.terminal_status = outcome
                        self.terminalized_count += 1

                        if outcome in ("COMPLETED", "CHANGED", "APPLIED"):
                            rec.resulting_version = data.get("resultingPotVersion")
                            self.applied_count += 1
                            with self.store._lock:
                                self.store.read_queue.append(cid)
                        elif outcome == "REJECTED":
                            self.rejected_cas_count += 1
                            rec.failure_reason = f"CAS_REJECTED: {data.get('rejection', 'n/a')}"
                        continue
            except urllib.error.HTTPError as e:
                if e.code == 404:
                    # Still in flight, put back at end of queue
                    with self.store._lock:
                        self.store.terminal_queue.append(cid)
                    time.sleep(self.probe_interval_sec)
                elif e.code == 503:
                    rec.terminal_503 += 1
                    self.total_503 += 1
                    with self.store._lock:
                        self.store.terminal_queue.append(cid)
                    time.sleep(0.025)
                elif e.code == 401:
                    # Token renewal required
                    try:
                        self.token_getter(force_renew=True)
                    except Exception:
                        pass
                    with self.store._lock:
                        self.store.terminal_queue.append(cid)
                    time.sleep(0.05)
                else:
                    rec.terminal_status = f"HTTP_{e.code}"
                    rec.failure_reason = f"Terminal probe HTTP {e.code}"
                    self.terminalized_count += 1
            except Exception as e:
                with self.store._lock:
                    self.store.terminal_queue.append(cid)
                time.sleep(self.probe_interval_sec)


class ReadObserver:
    """Observes READ aggregate and balance availability with C-03 oracle verification."""

    def __init__(self, store: CorrelationStore, token_getter,
                 binding_token: Optional[str] = None,
                 base_url: str = BASE_URL, read_timeout_sec: float = 90.0,
                 probe_interval_sec: float = 0.015):
        self.store = store
        self.token_getter = token_getter
        self.binding_token = binding_token
        self.base_url = base_url.rstrip("/")
        self.read_timeout_sec = read_timeout_sec
        self.probe_interval_sec = probe_interval_sec

        self.stop_event = threading.Event()
        self.thread: Optional[threading.Thread] = None

        # Telemetry
        self.read_materialized_count = 0
        self.read_timeouts_count = 0
        self.total_read_probes = 0
        self.total_read_503 = 0

    def start(self):
        self.thread = threading.Thread(target=self._run_loop, daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=3.0)

    def _run_loop(self):
        while not self.stop_event.is_set():
            cid = None
            with self.store._lock:
                if self.store.read_queue:
                    cid = self.store.read_queue.popleft()

            if not cid:
                time.sleep(self.probe_interval_sec)
                continue

            with self.store._lock:
                rec = self.store.records.get(cid)

            if not rec or rec.resulting_version is None:
                continue

            now = time.time() * 1000
            if (now - (rec.t_terminal or rec.t_admit)) > (self.read_timeout_sec * 1000):
                rec.failure_reason = f"READ probe timeout after {self.read_timeout_sec}s"
                self.read_timeouts_count += 1
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
                    rec.read_503 += 1
                    self.total_read_503 += 1
                elif e.code == 401:
                    try:
                        self.token_getter(force_renew=True)
                    except Exception:
                        pass
            except Exception:
                pass

            if not balance_ok:
                with self.store._lock:
                    self.store.read_queue.append(cid)
                time.sleep(self.probe_interval_sec)
                continue

            # Step 2: Probe complete pot
            pot_url = f"{self.base_url}/api/v3/pots/{pot_id}?minVersion={v}"
            pot_req = urllib.request.Request(pot_url, headers=req_headers)

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
                    rec.read_503 += 1
                    self.total_read_503 += 1
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
                self.read_materialized_count += 1
            else:
                with self.store._lock:
                    self.store.read_queue.append(cid)
                time.sleep(self.probe_interval_sec)


class DecoupledBenchCoordinator:
    """Coordinates Injector, Observers, Real-time Backlog Polling, and Drainage."""

    def __init__(self, run_id: str, token_manager,
                 binding_token: Optional[str] = None,
                 max_drain_seconds: float = 60.0,
                 journal_path: Optional[Path] = None):
        self.run_id = run_id
        self.token_manager = token_manager
        self.binding_token = binding_token
        self.max_drain_seconds = max_drain_seconds

        self.store = CorrelationStore(run_id=run_id, journal_path=journal_path)
        self.term_obs = TerminalObserver(self.store, token_manager.get_token)
        self.read_obs = ReadObserver(self.store, token_manager.get_token, binding_token=binding_token)

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
            bl = query_db_backlog()
            phase = "injection" if self.injection_stop_time == 0.0 else "drainage"
            elapsed = (t_now - self.injection_start_time)
            with self.store._lock:
                pending_term = len(self.store.terminal_queue)
                pending_read = len(self.store.read_queue)
                admitted = self.store.admitted_count
                applied = self.term_obs.applied_count
                complete = sum(1 for r in self.store.records.values() if r.is_complete)

            self.backlog_timeseries.append({
                "timestamp": round(t_now, 2),
                "elapsedSeconds": round(elapsed, 2),
                "phase": phase,
                "dbBacklog": bl,
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
            bl = query_db_backlog()
            with self.store._lock:
                pending_term = len(self.store.terminal_queue)
                pending_read = len(self.store.read_queue)

            if bl == 0 and pending_term == 0 and pending_read == 0:
                print(f"  ✓ Drainage complete in {time.time() - drain_start:.1f}s (backlog=0, queues drained)")
                break

            time.sleep(0.5)

        self.drainage_stop_time = time.time()
        drain_duration = self.drainage_stop_time - drain_start

        # Stop poller and observers
        self._poller_stop.set()
        if self._poller_thread:
            self._poller_thread.join(timeout=2.0)
        self.term_obs.stop()
        self.read_obs.stop()
        self.store.close()

        final_bl = query_db_backlog()
        return {
            "drainDurationSec": round(drain_duration, 2),
            "finalDbBacklog": final_bl,
            "timedOut": time.time() >= drain_deadline and final_bl > 0,
        }

    def consolidate_metrics(self, demanded_rate: float, injection_target_sec: float) -> Dict[str, Any]:
        """Consolidates complete E2E metrics, populations, and conservation equations."""
        all_records = list(self.store.records.values())

        if all_records:
            t0 = min(r.t_send for r in all_records) / 1000.0
            t1 = self.injection_stop_time if self.injection_stop_time else (max(r.t_send for r in all_records) / 1000.0)
            inj_duration = max(1.0, round(t1 - t0, 2))
        else:
            inj_duration = injection_target_sec

        total_duration = (self.drainage_stop_time - self.injection_start_time) if self.drainage_stop_time else inj_duration

        # Exact populations
        admitted_records = [r for r in all_records if r.admission_status == 202]
        terminal_records = [r for r in admitted_records if r.t_terminal is not None]
        applied_records = [r for r in terminal_records if r.terminal_status in ("COMPLETED", "CHANGED", "APPLIED")]
        read_records = [r for r in applied_records if r.t_read is not None]
        complete_records = [r for r in read_records if r.is_complete]

        # Latency samples
        adm_latencies = [r.latency_admission_ms for r in admitted_records]
        term_latencies = [r.latency_terminal_ms for r in terminal_records if r.latency_terminal_ms is not None]
        read_latencies = [r.latency_read_ms for r in read_records if r.latency_read_ms is not None]
        e2e_latencies = [r.latency_e2e_ms for r in complete_records if r.latency_e2e_ms is not None]

        # Rates (strictly calculated over homogeneous injection window)
        emitted_rate = round(self.store.emitted_count / inj_duration, 2)
        admission_rate = round(self.store.admitted_count / inj_duration, 2)
        applied_rate = round(len(applied_records) / inj_duration, 2)
        read_rate = round(len(read_records) / inj_duration, 2)

        # Generator compliance (mandate E2: >= 95% demanded rate)
        gen_ratio = emitted_rate / demanded_rate if demanded_rate > 0 else 1.0
        is_gen_valid = gen_ratio >= 0.95

        # Incomplete breakdown
        non_terminal_at_close = len(admitted_records) - len(terminal_records)
        non_read_at_close = len(applied_records) - len(read_records)

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
                "terminalized": len(terminal_records),
                "applied": len(applied_records),
                "casRejected": self.term_obs.rejected_cas_count,
                "terminalTimeouts": self.term_obs.timeouts_count,
                "readMaterialized": len(read_records),
                "readTimeouts": self.read_obs.read_timeouts_count,
                "completeE2E": len(complete_records),
                "nonTerminalAtClose": non_terminal_at_close,
                "nonReadAtClose": non_read_at_close,
            },
            "ratesPerSecond": {
                "demanded": demanded_rate,
                "emitted": emitted_rate,
                "admitted": admission_rate,
                "applied": applied_rate,
                "readMaterialized": read_rate,
            },
            "coverage": {
                "completeOverAdmitted": round(coverage_ratio, 4),
                "percentage": round(coverage_ratio * 100.0, 2),
            },
            "conservations": {
                "emittedEquation": (self.store.emitted_count == (
                    self.store.admitted_count + self.store.rejected_admission_count + self.store.error_admission_count
                )),
                "admittedEquation": (len(admitted_records) == (
                    len(applied_records) + self.term_obs.rejected_cas_count + self.term_obs.timeouts_count + non_terminal_at_close
                )),
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
        }
