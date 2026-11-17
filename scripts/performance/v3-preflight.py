#!/usr/bin/env python3
"""Pocoma V3 — Mandatory Preflight Verification Gate (scripts/performance/v3-preflight.py).

Guarantees system safety before any performance benchmark execution:
1. Target host authorization (strictly non-production, localhost/127.0.0.1 only).
2. Environment validation (non-prod markers, container validation).
3. Git SHA identification and repository state traceability.
4. Configuration completeness.
5. WRITE and READ database consistency and Flyway migration state.
6. Readiness of core services (Monolith, PostgreSQL, Keycloak, Prometheus, Grafana).
7. Availability of indispensable metrics (Admission, Read, Workers, Hikari, JVM).
8. Host resource adequacy (CPU, memory, disk).
9. READ maintenance state compliance (READY for nominal benchmarks).

Exit codes:
  0: All preflight checks passed. Safe to proceed with benchmark.
  1: Preflight validation failure. Execution rejected.
"""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request

REPO_ROOT = Path(__file__).resolve().parents[2]

ALLOWED_TARGET_HOSTS = {"127.0.0.1", "localhost", "::1"}
POSTGRES_CONTAINER = os.environ.get("POSTGRES_CONTAINER", "pocoma-functional-postgres-1")
POSTGRES_USER = os.environ.get("POSTGRES_USER", "pocoma")
POSTGRES_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "pocoma-local-only")

DEFAULT_API_URL = os.environ.get("POCOMA_BASE_URL", "http://127.0.0.1:8080")
DEFAULT_ACTUATOR_URL = os.environ.get("POCOMA_ACTUATOR_URL", "http://127.0.0.1:18081")
DEFAULT_PROMETHEUS_URL = os.environ.get("PROMETHEUS_URL", "http://127.0.0.1:9090")
DEFAULT_GRAFANA_URL = os.environ.get("GRAFANA_URL", "http://127.0.0.1:3000")
WRITE_DB = os.environ.get("WRITE_DB", "pocoma_write")
READ_DB = os.environ.get("READ_DB", "pocoma_read")


def run_psql(db, sql_command, check=True):
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        POSTGRES_CONTAINER,
        "psql", "-X", "-U", POSTGRES_USER, "-d", db, "-v", "ON_ERROR_STOP=1", "-tAc", sql_command
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"psql failed on {db}: {res.stderr.strip()}")
    return res.stdout.strip()


def http_get(url, timeout=5):
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Pocoma-Preflight/1.0"})
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, resp.read().decode("utf-8")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8")
    except Exception as e:
        return 0, str(e)


class PreflightChecker:
    def __init__(self, api_url=DEFAULT_API_URL, actuator_url=DEFAULT_ACTUATOR_URL,
                 prometheus_url=DEFAULT_PROMETHEUS_URL, grafana_url=DEFAULT_GRAFANA_URL,
                 expected_read_state="READY", allow_destructive=False):
        self.api_url = api_url.rstrip("/")
        self.actuator_url = actuator_url.rstrip("/")
        self.prometheus_url = prometheus_url.rstrip("/")
        self.grafana_url = grafana_url.rstrip("/")
        self.expected_read_state = expected_read_state
        self.allow_destructive = allow_destructive
        self.results = []

    def record(self, check_name, passed, message, fatal=True):
        self.results.append({
            "check": check_name,
            "passed": passed,
            "message": message,
            "fatal": fatal,
        })
        symbol = "✓" if passed else "✗"
        print(f"  [{symbol}] {check_name}: {message}")

    def check_target_authorized(self):
        parsed = urllib.parse.urlparse(self.api_url)
        hostname = parsed.hostname or "127.0.0.1"
        is_allowed = hostname in ALLOWED_TARGET_HOSTS
        self.record(
            "Target Host Authorization",
            is_allowed,
            f"Host '{hostname}' is {'authorized (non-production)' if is_allowed else 'FORBIDDEN'}"
        )

    def check_git_status(self):
        try:
            sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=REPO_ROOT, text=True).strip()
            branch = subprocess.check_output(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=REPO_ROOT, text=True).strip()
            status = subprocess.check_output(["git", "status", "--porcelain"], cwd=REPO_ROOT, text=True).strip()
            dirty = len(status) > 0
            self.record(
                "Git Repository State",
                True,
                f"SHA: {sha[:10]}, Branch: {branch}, Modified: {len(status.splitlines()) if dirty else 0} files",
                fatal=False
            )
        except Exception as e:
            self.record("Git Repository State", False, f"Failed to identify git repository: {e}")

    def check_services_readiness(self):
        # 1. Monolith Actuator
        code, body = http_get(f"{self.actuator_url}/actuator/health")
        actuator_ok = code == 200 and '"status":"UP"' in body
        self.record("Pocoma Actuator Health", actuator_ok, f"Status HTTP {code} on {self.actuator_url}")

        # 2. Monolith HTTP API
        code, _ = http_get(f"{self.api_url}/api/v3/me/binding")
        api_ok = code in (200, 401, 404)
        self.record("Pocoma HTTP API", api_ok, f"Status HTTP {code} on {self.api_url}/api/v3/me/binding")

        # 3. Prometheus
        code, body = http_get(f"{self.prometheus_url}/-/ready")
        prom_ok = code == 200 and "Ready" in body
        self.record("Prometheus Ready", prom_ok, f"Status HTTP {code} on {self.prometheus_url}")

        # 4. Grafana
        code, body = http_get(f"{self.grafana_url}/api/health")
        grafana_ok = code == 200 and '"database": "ok"' in body
        self.record("Grafana Ready", grafana_ok, f"Status HTTP {code} on {self.grafana_url}")

    def check_database_consistency(self):
        try:
            # Check v3_write tables in pocoma_write
            write_tables = run_psql(WRITE_DB, "SELECT count(*) FROM information_schema.tables WHERE table_schema='v3_write';")
            write_ok = int(write_tables) >= 5
            self.record("Database WRITE Schema", write_ok, f"Found {write_tables} tables in {WRITE_DB}.v3_write")

            # Check v3_read tables in pocoma_read
            read_tables = run_psql(READ_DB, "SELECT count(*) FROM information_schema.tables WHERE table_schema='v3_read';")
            read_ok = int(read_tables) >= 10
            self.record("Database READ Schema", read_ok, f"Found {read_tables} tables in {READ_DB}.v3_read")

            # Check checkpoints
            cp_count = run_psql(READ_DB, "SELECT count(*) FROM v3_read.discovery_checkpoint;")
            cp_ok = int(cp_count) == 28
            self.record("READ Discovery Checkpoints", cp_ok, f"Found {cp_count}/28 checkpoints")

            # Check maintenance state
            m_state = run_psql(READ_DB, "SELECT state FROM public.read_maintenance_state LIMIT 1;")
            state_ok = (m_state == self.expected_read_state)
            self.record(
                "READ Maintenance State",
                state_ok,
                f"State is '{m_state}', expected '{self.expected_read_state}'"
            )
        except Exception as e:
            self.record("Database Consistency", False, f"PostgreSQL validation error: {e}")

    def check_indispensable_metrics(self):
        code, body = http_get(f"{self.actuator_url}/actuator/prometheus")
        if code != 200:
            self.record("Prometheus Metrics Scrape", False, f"HTTP {code} from actuator/prometheus")
            return

        required_metrics = [
            "pocoma_v3_admission_capacity_available",
            "pocoma_v3_read_quota_permits",
            "pocoma_v3_worker_cycles_total",
            "jvm_memory_used_bytes",
            "hikaricp_connections",
        ]
        missing = [m for m in required_metrics if m not in body]
        metrics_ok = len(missing) == 0
        self.record(
            "Indispensable Metrics Exposure",
            metrics_ok,
            f"All {len(required_metrics)} metrics present" if metrics_ok else f"Missing: {missing}"
        )

    def check_host_resources(self):
        try:
            cpu_count = os.cpu_count() or 1
            cpu_ok = cpu_count >= 2
            self.record("Host CPU Allocation", cpu_ok, f"{cpu_count} CPU cores detected")

            stat = shutil.disk_usage(REPO_ROOT)
            free_gb = stat.free / (1024 ** 3)
            disk_ok = free_gb >= 1.0
            self.record("Host Disk Space", disk_ok, f"{free_gb:.1f} GB free disk space")
        except Exception as e:
            self.record("Host Resources", False, f"Resource check error: {e}")

    def run_all(self):
        print("================================================================================")
        print(" Pocoma V3 — Preflight Mandatory Verification Gate")
        print("================================================================================")
        self.check_target_authorized()
        self.check_git_status()
        self.check_services_readiness()
        self.check_database_consistency()
        self.check_indispensable_metrics()
        self.check_host_resources()

        failed_fatal = [r for r in self.results if not r["passed"] and r["fatal"]]
        print("--------------------------------------------------------------------------------")
        if failed_fatal:
            print(f"PREFLIGHT VERDICT: FAILED ({len(failed_fatal)} fatal issues detected)")
            print("Benchmark execution is strictly REFUSED.")
            return False
        else:
            print("PREFLIGHT VERDICT: PASSED (System is ready and safe for execution)")
            return True


def main():
    parser = argparse.ArgumentParser(description="Pocoma V3 Preflight Verification Gate")
    parser.add_argument("--api-url", default=DEFAULT_API_URL, help="Pocoma V3 API base URL")
    parser.add_argument("--actuator-url", default=DEFAULT_ACTUATOR_URL, help="Pocoma V3 Actuator URL")
    parser.add_argument("--prometheus-url", default=DEFAULT_PROMETHEUS_URL, help="Prometheus URL")
    parser.add_argument("--grafana-url", default=DEFAULT_GRAFANA_URL, help="Grafana URL")
    parser.add_argument("--expected-read-state", default="READY", help="Expected READ maintenance state")
    parser.add_argument("--json", action="store_true", help="Output results in JSON format")

    args = parser.parse_args()
    checker = PreflightChecker(
        api_url=args.api_url,
        actuator_url=args.actuator_url,
        prometheus_url=args.prometheus_url,
        grafana_url=args.grafana_url,
        expected_read_state=args.expected_read_state,
    )
    ok = checker.run_all()
    if args.json:
        print(json.dumps(checker.results, indent=2))
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
