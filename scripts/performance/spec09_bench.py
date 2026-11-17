"""Reusable, read-only SPEC-09 collectors and conservative E1 verdicts.

No missing observation becomes zero, no local E1 becomes a SPEC-09 PASS.
Docker collection is restricted to containers explicitly owned by this bench.
"""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
LABEL = "pocoma.spec09.disposable"


def utc():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def command(argv, **kwargs):
    return subprocess.check_output(argv, text=True, timeout=kwargs.pop("timeout", 20), **kwargs).strip()


def provenance():
    paths = sorted(set(ROOT.glob("scripts/performance/*.py"))
                   | set(ROOT.glob("scripts/performance/*.json"))
                   | set(ROOT.glob("scripts/performance/*.mjs"))
                   | set(ROOT.glob("scripts/k6/**/*.js"))
                   | set(ROOT.glob("scripts/v3/*.sql"))
                   | set(ROOT.glob("docker/v3-five-runtimes/*.properties"))
                   | set(ROOT.glob("app/**/src/main/resources/**/*.sql")))
    paths += [ROOT / "scripts/operations/setup-v3-database.py",
              ROOT / "docs/v3/SPEC-09_Performance.md", ROOT / "docs/v3/SPEC-10_Test_Certification.md"]
    jars = sorted(ROOT.glob("app/runtime-*/target/*-exec.jar"))
    # Only the four current runtime modules can supply executables.
    jars = [p for p in jars if p.parts[-3] in {
        "runtime-web-write", "runtime-web-read", "runtime-worker-process", "runtime-worker-materialize"}]
    return {"sha": command(["git", "rev-parse", "HEAD"], cwd=ROOT),
            "dirty": command(["git", "status", "--porcelain"], cwd=ROOT),
            "inputs": {str(p.relative_to(ROOT)): digest(p) for p in paths + jars}}


def owned(container):
    info = json.loads(command(["docker", "inspect", container]))[0]
    if info["Config"].get("Labels", {}).get(LABEL) != "true":
        raise ValueError("container lacks the disposable SPEC-09 ownership label")
    return info


def sql(container, database, statement):
    owned(container)
    # Server-enforced read-only transaction; no query text or credentials in receipts.
    output = command(["docker", "exec", "-i", container, "psql", "-X", "-qAt",
                      "-U", "pocoma", "-d", database, "-v", "ON_ERROR_STOP=1"],
                     input="BEGIN READ ONLY; SET LOCAL statement_timeout='3s'; "
                     + statement + "; COMMIT;", timeout=8)
    return json.loads(output)


def database_snapshot(container, authority):
    schema = {"write": "v3_write", "read": "v3_read"}[authority]
    extra = ("'pendingSources',(select count(*) from v3_write.v3_command where status='PENDING'),"
             "'counters',(select jsonb_object_agg(family,position) from v3_write.v3_event_counter),"
             "'positions',(select coalesce(jsonb_agg(x),'[]') from (select family,count(*) n,"
             "min(position) first,max(position) last from v3_write.v3_event_position group by family) x),"
             if authority == "write" else
             "'maintenance',(select to_jsonb(m)-'owner_token' from public.read_maintenance_state m),")
    return sql(container, "pocoma_" + authority, f"""
        SELECT jsonb_build_object(
          'serverVersion',current_setting('server_version'),
          'fsync',current_setting('fsync'),'synchronousCommit',current_setting('synchronous_commit'),
          'databaseBytes',pg_database_size(current_database()),
          'wal',(select to_jsonb(w) from pg_stat_wal w),
          'transactions',(select to_jsonb(d)-'datid' from pg_stat_database d where datname=current_database()),
          'activity',(select coalesce(jsonb_agg(x),'[]') from (
              select state,wait_event_type,wait_event,count(*) n from pg_stat_activity
              where datname=current_database() group by state,wait_event_type,wait_event) x),
          'blocking',(select coalesce(jsonb_agg(jsonb_build_object('pid',pid,'blockers',pg_blocking_pids(pid))),'[]')
              from pg_stat_activity where cardinality(pg_blocking_pids(pid))>0),
          {extra}
          'slots',(select coalesce(jsonb_agg(x),'[]') from (
              select consumption_id,state,count(*) n,max(attempt_count) max_attempts
              from {schema}.consumption_slot group by consumption_id,state) x),
          'checkpoints',(select jsonb_agg(to_jsonb(c)) from {schema}.discovery_checkpoint c))
    """)


def fetch_metrics(url):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(url + "/actuator/prometheus", timeout=3) as response:
        text = response.read().decode()
    names = {line.split("{", 1)[0].split(" ", 1)[0] for line in text.splitlines()
             if line and not line.startswith("#")}
    required = ("jvm_memory_used_bytes", "process_cpu_usage", "hikaricp_connections_active")
    if any(name not in names for name in required):
        raise ValueError("indispensable JVM/CPU/pool metric missing")
    return text


def snapshot(container, metrics, output, index):
    started = time.monotonic_ns()
    with ThreadPoolExecutor(max_workers=7) as pool:
        futures = {name: pool.submit(fetch_metrics, url) for name, url in metrics.items()}
        dbs = {authority: pool.submit(database_snapshot, container, authority) for authority in ("write", "read")}
        result = {"utc": utc(), "monotonicNs": started, "databases": {k: f.result() for k, f in dbs.items()}}
        result["metricFiles"] = {}
        for name, future in futures.items():
            path = output / f"metrics-{index}-{name}.prom"
            path.write_text(future.result())
            result["metricFiles"][path.name] = digest(path)
    result["collectorMs"] = (time.monotonic_ns() - started) / 1e6
    return result


def quantile(values, fraction):
    if not values:
        return None
    values = sorted(values)
    return values[max(0, math.ceil(len(values) * fraction) - 1)]


def record_failure(receipt, error):
    # A preceding E1 PASS cannot survive an interrupted/incomplete E3 as an
    # overall PASS. Keep the independent E1 summary untouched for attribution.
    receipt.update(status="INCONCLUSIVE", safety="INCONCLUSIVE",
                   error=type(error).__name__ + ": " + str(error))
    if isinstance(error, AssertionError):
        receipt.update(status="FAIL", safety="FAIL")


def summarize(rows, planned, duration):
    valid = [r for r in rows if r.get("status") == "OBSERVED"]
    safety = "FAIL" if any(r.get("status") == "INVARIANT_VIOLATION" for r in rows) else "INCONCLUSIVE"
    if len(valid) == planned and len({r["requestId"] for r in valid}) == planned:
        safety = "PASS"
    elif len({r["requestId"] for r in valid}) != len(valid):
        safety = "FAIL"
    return {"safety": safety, "performance": "INCONCLUSIVE", "contractual": "NOT_RUN",
            "reason": "E1 local small-profile bench validation; not Configuration A/B or a contractual sample",
            "planned": planned, "observed": len(valid), "retainedNonSuccess": len(rows)-len(valid),
            "offeredPerSecond": planned / duration, "admitted": sum("requestId" in r for r in rows),
            "latenciesMs": {key: {f"p{p}": quantile([r[key] for r in valid], p/100) for p in (50, 95, 99)}
                            for key in ("admissionMs", "completeLowerMs", "completeUpperMs", "balanceUpperMs")}}


def validate_progress(before, after):
    for authority in ("write", "read"):
        old, new = before["databases"][authority], after["databases"][authority]
        if authority == "read" and any(d["maintenance"]["state"] != "READY" for d in (old, new)):
            raise ValueError("READ maintenance is not READY")
        key = lambda r: (r["consumption_id"], r["partition_id"])
        a, b = {key(r): r["position"] for r in old["checkpoints"]}, {key(r): r["position"] for r in new["checkpoints"]}
        if a.keys() != b.keys() or any(b[k] < v for k, v in a.items()):
            raise AssertionError("checkpoint identity or monotonicity differs")
        if new["slots"]:
            raise ValueError("unfinished slots remain after E1 drainage")
        if new["transactions"]["deadlocks"] != old["transactions"]["deadlocks"]:
            raise AssertionError("new PostgreSQL deadlock")
    write = after["databases"]["write"]
    if write["pendingSources"]:
        raise ValueError("unfinished Command sources remain")
    for row in write["positions"]:
        if (row["first"], row["last"]) != (1, row["n"]) or write["counters"][row["family"]] != row["n"]:
            raise AssertionError("publication positions are not dense")


def reconcile(container, rows):
    observed = [r for r in rows if r.get("status") == "OBSERVED"]
    if not observed:
        raise ValueError("no observed requests to reconcile")
    import uuid
    values = ",".join("('" + str(uuid.UUID(r["requestId"])) + "'::uuid)" for r in observed)
    write = sql(container, "pocoma_write", f"""WITH ids(request_id) AS (VALUES {values})
       SELECT coalesce(jsonb_agg(x ORDER BY request_id),'[]') FROM (
         SELECT q.request_id,q.pot_id,r.resulting_pot_version FROM ids
         JOIN v3_write.v3_command q USING(request_id) JOIN v3_write.v3_command_result r USING(request_id)
         JOIN v3_write.v3_pot_event e ON e.request_id=q.request_id AND e.subject_version=r.resulting_pot_version
         JOIN v3_write.v3_command_completion_event c ON c.subject_id=q.request_id
         WHERE q.status='TERMINAL' AND r.outcome='CHANGED') x""")
    read = sql(container, "pocoma_read", f"""WITH ids(request_id) AS (VALUES {values})
       SELECT coalesce(jsonb_agg(x ORDER BY request_id),'[]') FROM (
         SELECT r.request_id,r.pot_id,r.resulting_pot_version FROM ids
         JOIN v3_read.read_command_result r USING(request_id)
         JOIN v3_read.read_pot_balance b ON b.pot_id=r.pot_id AND b.pot_version=r.resulting_pot_version
         JOIN v3_read.read_pot_version v ON v.pot_id=r.pot_id AND v.pot_version=r.resulting_pot_version
         WHERE r.write_outcome='CHANGED') x""")
    expected = sorted([dict(request_id=r["requestId"], pot_id=r["potId"], resulting_pot_version=r["version"])
                       for r in observed], key=lambda r: r["request_id"])
    if write != expected or read != expected:
        raise AssertionError("HTTP/WRITE/READ request identities or versions differ")
    return {"write": write, "read": read, "complete": True}
