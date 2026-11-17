#!/usr/bin/env python3
"""Create a disposable five-runtime E1 bench, measure, retain evidence, remove it.

No existing database/container is targeted. No contractual SPEC-09 result is
claimed. Run packaging first, outside the observation window. Private workspace
contains issuer tokens/configuration; publish only the evidence directory.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from fractions import Fraction
import importlib.util
import json
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys
import threading
import time
import uuid

import smoke_test_5_runtimes as oracle
import spec09_bench as bench

ROOT = bench.ROOT
RUNTIMES = {
    "http-write": ("runtime-web-write", "v3-http-admission", 18080),
    "http-read": ("runtime-web-read", "v3-http-read", 18082),
    "command-worker": ("runtime-worker-process", "v3-consumption-worker-dev", 18083),
    "results-worker": ("runtime-worker-materialize", "v3-read-worker", 18084),
    "business-read-worker": ("runtime-worker-materialize", "v3-read-worker", 18085),
}


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts/performance" / filename)
    mod = importlib.util.module_from_spec(spec)
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


def wait_for(probe, seconds=100):
    deadline = time.monotonic() + seconds
    while True:
        try:
            if probe():
                return
        except (OSError, ValueError, subprocess.CalledProcessError):
            pass
        if time.monotonic() >= deadline:
            raise TimeoutError("E1 readiness deadline")
        time.sleep(.5)


@dataclass(frozen=True)
class AttemptContext:
    state: dict
    token: str
    binding: str
    stop: threading.Event
    lock: threading.Lock
    after_admission: object = None
    observation_seconds: float = 8


def _poll_e1(path, accepted, ctx, observation_seconds):
    while time.monotonic_ns() - accepted < observation_seconds * 1e9:
        if ctx.stop.is_set():
            raise ValueError("indispensable collector stopped")
        code, result = oracle.request("GET", "http://127.0.0.1:28002/api/v3" + path, ctx.token, binding=ctx.binding)
        if time.monotonic_ns() - accepted >= observation_seconds * 1e9:
            raise TimeoutError("E1 observation deadline; late success censored")
        if code == 200:
            return result, time.monotonic_ns()
        if code not in (404, 503):
            raise ValueError("probe HTTP " + str(code))
        time.sleep(.025)
    raise TimeoutError("E1 observation deadline; censored sample retained")


def _execute_attempt_inner(index, scheduled, row, ctx):
    lag = (time.monotonic() - scheduled) * 1000
    row["offerLagMs"] = lag
    if lag > 100:  # E1 safety parameter, not a SPEC-09 threshold.
        return {**row, "status": "DROPPED_OFFER"}
    expense, key = str(uuid.uuid4()), str(uuid.uuid4())
    payer = ctx.state["shareholders"][0]
    amount = Fraction(index + 1, 3)
    expected = oracle.expected_balances(ctx.state, payer, amount)
    payload = dict(
        operation="CreateExpense", potId=ctx.state["potId"], observedVersion=ctx.state["version"],
        bindingToken=ctx.binding, payload=dict(
            id=expense, label=f"E1 test {index}", date="2026-11-12",
            amount=dict(numerator=str(index + 1), denominator="3"), payerId=payer,
            participants=[dict(id=s, weight=None) for s in ctx.state["shareholders"]],
        ),
    )
    began = time.monotonic_ns()
    row["sentNs"] = began
    status, admitted = oracle.request("POST", "http://127.0.0.1:28000/api/v3/commands", ctx.token, payload, key=key)
    accepted = time.monotonic_ns()
    row["admissionMs"] = (accepted - began) / 1e6
    if status != 202:
        raise ValueError("admission refused: " + str(status))
    row["requestId"] = str(uuid.UUID(admitted["requestId"]))
    obs_seconds = ctx.observation_seconds
    if ctx.after_admission:
        derived_window = ctx.after_admission(row["requestId"])
        if derived_window is not None:
            obs_seconds = derived_window
            row["derivedObservationSeconds"] = derived_window
    result, _ = _poll_e1("/commands/" + row["requestId"] + "/result", accepted, ctx, obs_seconds)
    version = oracle.applied_version(result, ctx.state["version"])
    path = "/pots/" + ctx.state["potId"]
    pot, complete_at = _poll_e1(path + "?minVersion=" + str(version), accepted, ctx, obs_seconds)
    balance, balance_at = _poll_e1(path + "/versions/" + str(version) + "/balance", accepted, ctx, obs_seconds)
    try:
        oracle.validate_complete(pot, balance, ctx.state, version, expense, expected)
    except ValueError as error:
        raise AssertionError(str(error)) from error
    row.update(
        status="OBSERVED", potId=ctx.state["potId"], version=version,
        completeLowerMs=(complete_at - accepted) / 1e6, completeUpperMs=(complete_at - began) / 1e6,
        balanceUpperMs=(balance_at - began) / 1e6,
    )
    ctx.state.update(version=version, expenses=ctx.state["expenses"] + 1, balances=expected)
    return row


def attempt(index, base, ctx):
    scheduled = base + index
    row = {"index": index, "status": "NOT_ATTEMPTED", "scheduledNs": round(scheduled * 1e9)}
    if ctx.stop.is_set():
        return row
    if not ctx.lock.acquire(blocking=False):
        return {**row, "status": "DROPPED_OFFER"}
    try:
        return _execute_attempt_inner(index, scheduled, row, ctx)
    except AssertionError as error:
        row.update(status="INVARIANT_VIOLATION", error=str(error))
        ctx.stop.set()
    except TimeoutError as error:
        row.update(status="CENSORED", error=str(error))
        ctx.stop.set()
    except Exception as error:
        # Oracle ValueErrors are critical after an HTTP success; no further load.
        row.update(
            status="INVARIANT_VIOLATION" if isinstance(error, ValueError) else "PROBE_ERROR",
            error=type(error).__name__ + ": " + str(error),
        )
        ctx.stop.set()
    finally:
        ctx.lock.release()
    return row


@dataclass(frozen=True)
class CrashEnv:
    pg: str
    project: str
    dc: object
    state: dict
    token: str
    binding: str
    fresh_token: str
    receipt: dict
    stop: threading.Event


def _kill_and_restart_worker(worker, dc, case):
    old = bench.owned(worker)["State"]
    if not old["Paused"]:
        raise AssertionError("E3 worker was not paused during admission")
    bench.command(["docker", "kill", "--signal", "KILL", worker])
    killed = bench.owned(worker)["State"]
    case["process"] = {
        "priorStartedAt": old["StartedAt"], "killedAt": bench.utc(),
        "exitCode": killed["ExitCode"], "runningAfterKill": killed["Running"], "pidAfterKill": killed["Pid"],
    }
    if killed["Running"] or killed["ExitCode"] != 137 or killed["Pid"] != 0:
        raise AssertionError("SIGKILL of the actual worker not established")
    dc("start", "command-worker")
    restarted = bench.owned(worker)["State"]
    case["process"].update(restartedAt=restarted["StartedAt"], runningAfterRestart=restarted["Running"])
    if not restarted["Running"] or restarted["StartedAt"] == old["StartedAt"]:
        raise AssertionError("worker generation did not restart")


def _barrier_pending(pg, worker, dc, req_info, kill_restart=True):
    request_id, kind, case = req_info
    table, result, event, completion, subject = (
        ("v3_command", "v3_command_result", "v3_pot_event", "v3_command_completion_event", "subject_id")
        if kind == "COMMAND" else
        ("v3_identity_request", "v3_identity_request_result", "v3_identity_event", "v3_identity_completion_event", "subject_id")
    )
    request_id = str(uuid.UUID(request_id))
    before = bench.sql(pg, "pocoma_write", f"""SELECT jsonb_build_object(
      'status',(select status from v3_write.{table} where request_id='{request_id}'),
      'results',(select count(*) from v3_write.{result} where request_id='{request_id}'),
      'events',(select count(*) from v3_write.{event} where request_id='{request_id}'),
      'completions',(select count(*) from v3_write.{completion} where {subject}='{request_id}'))""")
    case.update(requestId=request_id, beforeKill=before, pendingObservedAt=bench.utc())
    if before != dict(status="PENDING", results=0, events=0, completions=0):
        raise AssertionError("E3 after-admission barrier not established")
    leases = bench.sql(pg, "pocoma_write", """SELECT jsonb_build_object('databaseTime',clock_timestamp(),
      'remainingMs',greatest(0,coalesce(max(extract(epoch from (owned_until-clock_timestamp()))*1000),0)),
      'partitions',jsonb_agg(jsonb_build_object('consumptionId',consumption_id,'partition',partition_id,
         'ownedUntil',owned_until))) FROM v3_write.partition_ownership""")
    case["ownershipBeforeKill"] = leases
    if not 0 <= leases["remainingMs"] <= 241000:
        raise AssertionError("observed lease outside configured 240-second envelope")
    if not kill_restart:
        return None
    _kill_and_restart_worker(worker, dc, case)
    return leases["remainingMs"] / 1000 + 60


def _verify_e3_registration(pg, fresh_token, identity_case, stop):
    request_id = identity_case["requestId"]

    def available():
        if stop.is_set():
            raise TimeoutError("indispensable E3 collector stopped")
        code, current = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", fresh_token)
        if code != 200:
            return False
        if current.get("active") is not True or current.get("euiVersion") != 1:
            raise AssertionError("E3 binding differs")
        code, pots = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/pots?limit=1", fresh_token, binding=current["bindingToken"])
        return code == 200 and pots.get("potIds") == []

    wait_for(available, 60)
    write = bench.sql(pg, "pocoma_write", f"""SELECT jsonb_build_object(
      'terminal',(select count(*) from v3_write.v3_identity_request where request_id='{request_id}' and status='TERMINAL'),
      'result',(select count(*) from v3_write.v3_identity_request_result where request_id='{request_id}' and outcome='CHANGED' and target_version=1),
      'users',(select count(*) from v3_write.v3_pocoma_user where created_request_id='{request_id}'),
      'events',(select count(*) from v3_write.v3_identity_event where request_id='{request_id}'),
      'completions',(select count(*) from v3_write.v3_identity_completion_event where subject_id='{request_id}'),
      'user',(select user_id::text from v3_write.v3_pocoma_user where created_request_id='{request_id}'))""")
    read = bench.sql(pg, "pocoma_read", f"""SELECT jsonb_build_object('results',count(*),'user',min(b.user_id::text))
      FROM v3_read.read_identity_request_result r JOIN v3_read.read_current_binding b
      ON b.issuer=r.requester_issuer AND b.subject=r.requester_subject
      WHERE r.request_id='{request_id}' AND r.write_outcome='CHANGED' AND r.target_version=1
      AND b.eui_version=1 AND b.binding_token IS NOT NULL""")
    identity_case.update(write=write, read=read, authorizedEmptyNavigation=True)
    if {k: v for k, v in write.items() if k != "user"} != dict(terminal=1, result=1, users=1, events=2, completions=1):
        raise AssertionError("Registration effects/publications are not unique")
    if not write["user"] or read != dict(results=1, user=write["user"]):
        raise AssertionError("Registration WRITE/READ user identity differs")


def crash_campaign(env):
    """E02-J only: process stopped before admission, killed after durable PENDING."""
    worker = env.project + "-command-worker-1"
    evidence = {"scenario": "E3_E02_J_AFTER_HTTP_ADMISSION", "status": "INCONCLUSIVE", "cases": []}
    env.receipt["crashCampaign"] = evidence
    case = {"kind": "COMMAND"}
    evidence["cases"].append(case)
    bench.owned(worker)
    bench.command(["docker", "pause", worker])
    identity_case = {"kind": "REGISTRATION"}

    def crash_both(request_id):
        status, admitted = oracle.request(
            "POST", "http://127.0.0.1:28000/api/v3/identity/requests", env.fresh_token,
            dict(operation="Registration"), key=str(uuid.uuid4()),
        )
        if status != 202:
            raise AssertionError("E3 Registration not admitted")
        identity_request = str(uuid.UUID(admitted["requestId"]))
        evidence["cases"].append(identity_case)
        _barrier_pending(env.pg, worker, env.dc, (identity_request, "IDENTITY", identity_case), kill_restart=False)
        window = _barrier_pending(env.pg, worker, env.dc, (request_id, "COMMAND", case))
        identity_case["process"] = case["process"]
        return window

    ctx = AttemptContext(env.state, env.token, env.binding, env.stop, threading.Lock(), crash_both)
    row = attempt(0, time.monotonic(), ctx)
    case["observation"] = row
    if row["status"] != "OBSERVED":
        if row["status"] == "INVARIANT_VIOLATION":
            raise AssertionError("Command recovery invariant: " + str(row))
        raise TimeoutError("Command recovery not established: " + row["status"])
    case["reconciliation"] = bench.reconcile(env.pg, [row])
    _verify_e3_registration(env.pg, env.fresh_token, identity_case, env.stop)
    evidence["status"] = "PASS"


def _parse_e1_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace", type=Path, required=True, help="new private directory outside Git")
    parser.add_argument("--output", type=Path, required=True, help="new publishable evidence directory")
    parser.add_argument("--java-image", required=True, help="immutable local image ID containing Java 21")
    parser.add_argument("--postgres-image", required=True, help="immutable local PostgreSQL 17 image ID")
    parser.add_argument("--seed-id", default="l7-spec09-e1-small-2", help="same synthetic seed on fresh targets")
    parser.add_argument("--crash-after-admission", action="store_true", help="separate E3 process crash campaign after E1")
    args = parser.parse_args()
    if not all(value.startswith("sha256:") and len(value) == 71 for value in (args.java_image, args.postgres_image)):
        raise ValueError("pin local images by complete sha256 ID")
    work, out = args.workspace.resolve(), args.output.resolve()
    if work == ROOT or ROOT in work.parents:
        raise ValueError("private workspace must be outside Git and separate from evidence")
    if work == out or work in out.parents or out in work.parents:
        raise ValueError("private workspace must be outside Git and separate from evidence")
    work.mkdir(mode=0o700)
    out.mkdir(parents=True)
    if shutil.disk_usage(work).free < 1024**3:
        raise ValueError("E1 requires at least 1 GiB free workspace capacity before creating Docker resources")
    return args, work, out


def _configure_runtime_services(args, work, ip, definition):
    for name, (mod, profile, management) in RUNTIMES.items():
        jar = ROOT / "app" / mod / "target" / f"pocoma-{mod}-0.0.1-SNAPSHOT-exec.jar"
        if not jar.is_file():
            raise ValueError("package the current runtime first: " + mod)
        content = (ROOT / "docker/v3-five-runtimes" / (name + ".properties")).read_text()
        content = content.replace("172.28.0.2/32", ip + "/32")
        content += "\npocoma.v3.http.issuer=http://pocoma-spec09-e1.invalid\n"
        content += "pocoma.v3.http.jwk-set-uri=http://host.docker.internal:28090/jwks.json\n"
        config = work / (name + ".properties")
        config.write_text(content)
        ports = [f"127.0.0.1:{management + 10000}:{management}"]
        if name in ("http-write", "http-read"):
            port = 8080 if name == "http-write" else 8082
            ports.append(f"127.0.0.1:{port + 19920}:{port}")
        definition["services"][name] = {
            "image": args.java_image, "labels": {bench.LABEL: "true"}, "entrypoint": ["java", "-jar", "/app/e1.jar"],
            "environment": {
                "SPRING_PROFILES_ACTIVE": profile,
                "SPRING_CONFIG_ADDITIONAL_LOCATION": "file:/app/e1.properties",
                "JAVA_TOOL_OPTIONS": "-Xms64m -Xmx256m -XX:+UseSerialGC",
            },
            "volumes": [str(jar) + ":/app/e1.jar:ro", str(config) + ":/app/e1.properties:ro"],
            "ports": ports, "mem_limit": "448m", "cpus": .5,
        }


def _seed_and_load_states(pg, seed_id, token, binding, out):
    seed = module("spec09_seed_e1", "v3-seed.py")
    seed.POSTGRES_CONTAINER = pg
    data = seed.create_seed("small", seed_id, pots_count=2)
    data = seed.materialize_dataset(data, "http://127.0.0.1:28000", "http://127.0.0.1:28002")
    (out / "seed.json").write_text(json.dumps(data, indent=2))
    states = []
    for pot in data["pots"]:
        _, current = oracle.request("GET", "http://127.0.0.1:28002/api/v3/pots/" + pot["potId"], token, binding=binding)
        states.append(oracle.initial_state(current, dict(potId=pot["potId"], shareholders=[s["id"] for s in pot["shareholders"]], expenses=20)))
    return states


def _run_e1_measurements(states, token, binding, stop):
    locks = [threading.Lock() for _ in states]
    base = time.monotonic() + .2
    with ThreadPoolExecutor(max_workers=2) as pool:
        pending = []
        for i in range(12):
            time.sleep(max(0, base + i - time.monotonic()))
            ctx = AttemptContext(states[i % 2], token, binding, stop, locks[i % 2])
            pending.append(pool.submit(attempt, i, base, ctx))
        return [f.result() for f in pending]


def _build_topology_receipt(ip, seed_id):
    return {
        "kind": "LOCAL_DOCKER_SINGLE_HOST", "postgresAddress": ip,
        "postgresMemoryBytes": 640 * 1024**2, "postgresCpus": 1, "postgresData": "fresh 512 MiB tmpfs; no crash-durability claim",
        "runtimeMemoryBytesEach": 448 * 1024**2, "runtimeCpusEach": .5,
        "javaOptions": "-Xms64m -Xmx256m -XX:+UseSerialGC",
        "writeUrl": "http://127.0.0.1:28000", "readUrl": "http://127.0.0.1:28002",
        "testIssuer": "http://pocoma-spec09-e1.invalid", "jwks": "http://host.docker.internal:28090/jwks.json",
        "pollMs": 25, "observationDeadlineMs": 8000, "offerLagCeilingMs": 100,
        "resourceSampleWaitSeconds": 2, "memoryStopPercent": 92, "seedId": seed_id,
    }


def _bootstrap_e1_cluster(args, work, out, pg, ip, definition, dc, receipt):
    with (out / "bootstrap.log").open("x") as log:
        subprocess.run([sys.executable, "-B", str(ROOT / "scripts/operations/setup-v3-database.py"),
                        "--postgres-container", pg], check=True, stdout=log, stderr=log, timeout=100)
    project = receipt["project"]
    (work / "subjects.json").write_text(json.dumps(["spec09-e1-" + project, "spec09-e3-" + project]))
    issuer_log = (out / "issuer.log").open("x")
    issuer = subprocess.Popen([
        "node", str(ROOT / "scripts/performance/spec09-test-issuer.mjs"),
        "--host", "0.0.0.0", "--port", "28090", "--issuer", "http://pocoma-spec09-e1.invalid",
        "--audience", "pocoma-api", "--subjects", str(work / "subjects.json"),
        "--token-file", str(work / "tokens.json"), "--ttl-seconds", "1200",
    ], stdout=issuer_log, stderr=issuer_log)
    wait_for(lambda: (work / "tokens.json").is_file(), 10)
    _configure_runtime_services(args, work, ip, definition)
    (work / "compose.json").write_text(json.dumps(definition))
    receipt["configurationSha256"] = {p.name: bench.digest(p) for p in work.glob("*.properties")}
    dc("up", "-d")
    return issuer


def _prepare_e1_workload(pg, work, out, seed_id, receipt):
    registration = module("spec09_registration_e1", "run-l7-spec09-registration.py")
    token = next(iter(json.loads((work / "tokens.json").read_text()).values()))
    identity = registration.observe_one("http://127.0.0.1:28000", "http://127.0.0.1:28002", token, str(uuid.uuid4()))
    receipt["identity"] = identity
    if identity["outcome"] != "AUTHORIZED":
        raise ValueError("Registration and authorized navigation failed")
    code, binding = oracle.request("GET", "http://127.0.0.1:28002/api/v3/me/binding", token)
    if code != 200:
        raise ValueError("binding unavailable")
    binding = binding["bindingToken"]
    os.environ.update(POCOMA_TOKEN=token, POCOMA_BINDING_TOKEN=binding)
    states = _seed_and_load_states(pg, seed_id, token, binding, out)
    return token, binding, states


def main():
    args, work, out = _parse_e1_args()
    project = "pocoma-spec09-" + uuid.uuid4().hex[:10]
    pg = project + "-postgres-1"
    compose = work / "compose.json"
    compose_args = ["docker", "compose", "-p", project, "-f", str(compose)]
    pre = bench.provenance()
    receipt = {
        "mode": "E1_LOCAL_ONLY", "status": "INCONCLUSIVE", "startedAt": bench.utc(),
        "provenance": pre, "project": project, "snapshots": [], "observations": [],
        "contractual": "NOT_RUN", "safety": "INCONCLUSIVE",
    }
    issuer = None
    stop = threading.Event()
    collector = None
    collector_errors = []
    db = {
        "image": args.postgres_image, "labels": {bench.LABEL: "true"},
        "environment": {"POSTGRES_USER": "pocoma", "POSTGRES_PASSWORD": "pocoma-local-only", "POSTGRES_DB": "pocoma"},
        "tmpfs": ["/var/lib/postgresql/data:size=512m"], "mem_limit": "640m", "cpus": 1,
    }
    definition = {"services": {"postgres": db}}
    compose.write_text(json.dumps(definition))

    def dc(*argv, timeout=120):
        with (out / "lifecycle.log").open("a") as log:
            subprocess.run(compose_args + list(argv), check=True, stdout=log, stderr=log, timeout=timeout)

    def interrupted(_signum, _frame):
        raise KeyboardInterrupt("E1 interrupted")

    signal.signal(signal.SIGTERM, interrupted)
    try:
        receipt["docker"] = json.loads(bench.command(["docker", "info", "--format", "{{json .}}"]))
        receipt["docker"] = {k: receipt["docker"][k] for k in ("Name", "NCPU", "MemTotal", "ServerVersion", "Architecture")}
        dc("up", "-d", "postgres")
        wait_for(lambda: bench.command(["docker", "exec", pg, "pg_isready", "-h", "127.0.0.1", "-U", "pocoma"]) != "")
        ip = next(iter(bench.owned(pg)["NetworkSettings"]["Networks"].values()))["IPAddress"]
        receipt["topology"] = _build_topology_receipt(ip, args.seed_id)
        issuer = _bootstrap_e1_cluster(args, work, out, pg, ip, definition, dc, receipt)
        metrics = {name: f"http://127.0.0.1:{port + 10000}" for name, (_, _, port) in RUNTIMES.items()}
        for url in metrics.values():
            wait_for(lambda url=url: bool(bench.fetch_metrics(url)))
        receipt["containers"] = [
            {"name": name, "image": bench.owned(project + "-" + name + "-1")["Image"],
             "memoryLimit": definition["services"][name]["mem_limit"]}
            for name in RUNTIMES
        ]
        token, binding, states = _prepare_e1_workload(pg, work, out, args.seed_id, receipt)
        initial = bench.snapshot(pg, metrics, out, "initial")
        receipt["snapshots"].append(initial)
        bench.validate_progress(initial, initial)

        def collect(observed_metrics=metrics, prefix=""):
            i = 0
            while not stop.wait(2):
                try:
                    receipt["snapshots"].append(bench.snapshot(pg, observed_metrics, out, prefix + str(i)))
                    i += 1
                    stats = [json.loads(line) for line in bench.command(["docker", "stats", "--no-stream", "--format", "{{json .}}",
                             *[project + "-" + name + "-1" for name in ("postgres", *RUNTIMES)]]).splitlines()]
                    receipt.setdefault("resources", []).append({"utc": bench.utc(), "stats": stats})
                    if any(float(row["MemPerc"].rstrip("%")) > 92 for row in stats):
                        raise ValueError("E1 memory safety ceiling exceeded")
                except Exception as error:
                    collector_errors.append(type(error).__name__ + ": " + str(error))
                    stop.set()

        collector = threading.Thread(target=collect)
        collector.start()
        receipt["observations"] = _run_e1_measurements(states, token, binding, stop)
        stop.set()
        collector.join(25)
        if collector.is_alive() or collector_errors:
            raise ValueError("collector unavailable: " + str(collector_errors))
        final = bench.snapshot(pg, metrics, out, "final")
        receipt["snapshots"].append(final)
        bench.validate_progress(initial, final)
        receipt["reconciliation"] = bench.reconcile(pg, receipt["observations"])
        receipt["summary"] = bench.summarize(receipt["observations"], 12, 12)
        receipt["safety"] = receipt["summary"]["safety"]
        receipt["status"] = receipt["safety"]
        if args.crash_after_admission and receipt["status"] == "PASS":
            fresh_token = list(json.loads((work / "tokens.json").read_text()).values())[1]
            stop.clear()
            collector = threading.Thread(target=collect, args=({k: v for k, v in metrics.items() if k != "command-worker"}, "e3-"))
            collector.start()
            crash_campaign(CrashEnv(pg, project, dc, states[0], token, binding, fresh_token, receipt, stop))
            stop.set()
            collector.join(25)
            if collector.is_alive() or collector_errors:
                raise ValueError("E3 collector unavailable: " + str(collector_errors))
            after_crash = bench.snapshot(pg, metrics, out, "after-crash")
            receipt["snapshots"].append(after_crash)
            bench.validate_progress(final, after_crash)
        post = bench.provenance()
        if pre != post:
            receipt["status"] = "INCONCLUSIVE"
            raise ValueError("source or executable provenance changed during E1")
    except BaseException as error:
        bench.record_failure(receipt, error)
    finally:
        stop.set()
        if collector:
            collector.join(25)
        if issuer:
            issuer.terminate()
            issuer.wait(timeout=5)
        try:
            with (out / "runtime.log").open("x") as log:
                subprocess.run(compose_args + ["logs", "--no-color"], stdout=log, stderr=log, timeout=20)
            dc("down", "--volumes", "--remove-orphans")
            receipt["cleanup"] = "REMOVED_OWN_PROJECT"
        except Exception as error:
            receipt["cleanup"] = type(error).__name__
            receipt["status"] = "INCONCLUSIVE"
        receipt["finishedAt"] = bench.utc()
        receipt["collectorErrors"] = collector_errors
        (out / "receipt.json").write_text(json.dumps(receipt, indent=2, allow_nan=False)+"\n")
    print(json.dumps({k: receipt[k] for k in ("status", "safety", "contractual", "cleanup")}))
    return 0 if receipt["status"] == "PASS" else 2


if __name__ == "__main__":
    sys.exit(main())
