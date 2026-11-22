#!/usr/bin/env python3
"""Real L6/I2 CLI qualification on a disposable five-runtime-style compose project."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import uuid

ROOT = Path.cwd()
BENCH, OUTPUT = map(Path, sys.argv[1:])
OUTPUT.mkdir(exist_ok=False)
PG_IMAGE = "postgres@sha256:a77aa70e641402a21411b92b721c58d10f23b50ae5fdd2333efd22d166e1e5f7"
JAVA_IMAGE = "eclipse-temurin@sha256:66bb900643426ad01996d25bada7d56751913f9cec3b827fcb715d2ec9a0fbfc"

runner = ROOT / "scripts/performance/run-l7-4a-baseline.py"
spec = importlib.util.spec_from_file_location("rebuild_bench", runner)
base = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = base
spec.loader.exec_module(base)

project = f"pocoma-c3-rebuild-proof-{uuid.uuid4().hex[:8]}"
postgres = f"{project}-postgres-1"
snapshot = Path("/private/tmp/pocoma-l74a-snapshots/medium")
cfg = base.load_profiles_config()
read_services = ("pocoma-results-worker", "pocoma-business-read-worker")
http_read = "pocoma-http-read"


def run(exec_args, **kwargs):
    return subprocess.run(exec_args, check=True, text=True, **kwargs)


def query(database, sql, *, read_only=True):
    if read_only:
        sql = f"begin read only; {sql}; commit"
    return run(["docker", "exec", postgres, "psql", "-qAtX", "-U", "pocoma", "-d", database,
                "-v", "ON_ERROR_STOP=1", "-c", sql], capture_output=True, timeout=10).stdout.strip()


def polls(predicate, seconds):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        if predicate():
            return
        time.sleep(0.15)
    raise RuntimeError(f"failed to reach condition in {seconds}s")


def distributed_keys():
    return query("pocoma_read", """
        select md5(coalesce(jsonb_agg(to_jsonb(effect) order by family, projection_key, logical_version)::text, '[]'))
        from v3_read.read_availability_effects effect
    """)


def existing_events():
    return set(query("pocoma_read", """
        select jsonb_build_array(family, position, event_id, projection_key, logical_version)
        from v3_read.read_availability_event
    """).splitlines())


def write_fingerprint():
    return query("pocoma_write", """
        select md5(jsonb_build_object(
          'counters', (select jsonb_object_agg(family, position) from v3_write.v3_event_counter),
          'events', (select jsonb_agg(to_jsonb(e) order by family, position) from v3_write.v3_event_position e),
          'commands', (select jsonb_agg(to_jsonb(r) order by request_id) from v3_write.v3_command_result r)
        )::text)
    """)


def maintenance_state():
    return query("pocoma_read", "select state || '/' || phase from public.read_maintenance_state")


with tempfile.TemporaryDirectory(prefix=project) as temp:
    work = Path(temp)
    compose = work / "compose.json"
    compose_args = ["docker", "compose", "-f", str(compose)]
    definition = {
        "name": project,
        "services": {
            "postgres": {
                "image": PG_IMAGE,
                "labels": {base.bench.LABEL: "true"},
                "environment": {
                    "POSTGRES_USER": "pocoma", "POSTGRES_PASSWORD": "pocoma-local-only", "POSTGRES_DB": "pocoma",
                },
                "tmpfs": ["/var/lib/postgresql/data:size=768m"],
                "mem_limit": "1536m", "cpus": 2.0,
            }
        },
    }
    compose.write_text(json.dumps(definition))
    env = os.environ | {
        "POSTGRES_CONTAINER": postgres, "POCOMA_REBUILD_COMPOSE_FILE": str(compose),
        "COMPOSE_PROJECT_NAME": project, "READ_DB": "pocoma_read", "WRITE_DB": "pocoma_write",
        "READ_SCHEMA": "v3_read", "WRITE_SCHEMA": "v3_write",
    }
    rebuild = [sys.executable, "-Bu", "scripts/operations/v3-rebuild-read.py", "--operator-id=c3-qualification"]
    try:
        run(compose_args + ["up", "-d", "postgres"], capture_output=True, timeout=40)
        base.wait_for(lambda: base.bench.command(["docker", "exec", postgres, "pg_isready",
                                                 "-h", "127.0.0.1", "-U", "pocoma"]) != "", 30)
        metadata, archive = base.verify_snapshot_metadata(snapshot, "medium", 2)
        ip = next(iter(base.bench.owned(postgres)["NetworkSettings"]["Networks"].values()))["IPAddress"]
        v5 = base.V5_READ_MIGRATION
        base.V5_READ_MIGRATION = work / "no-v5-on-c2"
        base.restore_snapshot_into_pg(postgres, archive, OUTPUT)
        before = write_fingerprint()

        def configure(runtime_repository):
            base.ROOT = runtime_repository
            base._configure_services(type("Arguments", (), {"java_image": JAVA_IMAGE})(), cfg, work, ip, definition)
            for key in ("results-worker", "business-read-worker", "http-read"):
                service = definition["services"].pop(key)
                service["ports"] = [port.replace(":28002:", ":28302:").replace(":28082:", ":28382:")
                                    .replace(":28084:", ":28384:").replace(":28085:", ":28385:")
                                    for port in service["ports"]]
                definition["services"]["pocoma-" + key] = service
            del definition["services"]["http-write"], definition["services"]["command-worker"]
            compose.write_text(json.dumps(definition))

        configure(BENCH / "c2")
        run(compose_args + ["up", "-d", *read_services, http_read], capture_output=True, timeout=45)
        for port in [28382, 28384, 28385]:
            base.wait_for(lambda port=port: bool(base.bench.fetch_metrics(f"http://127.0.0.1:{port}")), 45)
        base.await_read_rebuild_from_write(postgres, metadata["writeCounters"], timeout_s=90)
        assert query("pocoma_read", "select to_regclass('v3_read.read_availability_counter') is null") == "t"
        assert query("pocoma_read", "select count(*) from v3_read.read_pot_version") == "404"

        base.V5_READ_MIGRATION = v5
        configure(ROOT)
        with (OUTPUT / "first-activation.log").open("w") as log:
            run(rebuild + ["--confirm-destructive=RESET_READ_DATA"], env=env,
                stdout=log, stderr=subprocess.STDOUT, timeout=240)
        assert maintenance_state() == "READY/IDLE"
        assert query("pocoma_read", "select v3_read.read_availability_valid()") == "t"
        assert before == write_fingerprint()
        digest = distributed_keys()
        original = existing_events()

        with (OUTPUT / "interrupted-replay.log").open("w") as log:
            process = subprocess.Popen(rebuild + ["--confirm-destructive=RESET_READ_DATA"], env=env,
                                       stdout=log, stderr=subprocess.STDOUT, text=True)
            try:
                partial = set()

                def has_partial_replay():
                    nonlocal_helper = maintenance_state()
                    if nonlocal_helper != "REBUILDING/REPLAYING":
                        return False
                    partial.update(existing_events())
                    return 0 < len(partial) < len(original)

                polls(has_partial_replay, 75)
            finally:
                if process.poll() is None:
                    process.terminate()
                process.wait(timeout=10)
        assert maintenance_state() == "REBUILDING/REPLAYING"
        with (OUTPUT / "resume.log").open("w") as log:
            run(rebuild + ["--resume"], env=env, stdout=log, stderr=subprocess.STDOUT, timeout=190)
        assert maintenance_state() == "READY/IDLE"
        assert query("pocoma_read", "select v3_read.read_availability_valid()") == "t"
        resumed = existing_events()
        assert partial <= resumed
        assert distributed_keys() == digest
        assert write_fingerprint() == before
        assert original != resumed
        receipt = {
            "project": project, "snapshot": metadata["snapshotSha256"], "writeFingerprint": before,
            "logicalAvailabilityFingerprint": digest, "initialHistorical": len(original),
            "committedBeforeResume": len(partial), "historicalAfterResume": len(resumed),
            "checkpoints": int(query("pocoma_read", "select count(*) from v3_read.discovery_checkpoint")),
            "pendingSlots": int(query("pocoma_read", "select count(*) from v3_read.consumption_slot")),
            "state": maintenance_state(),
        }
        (OUTPUT / "manifest.json").write_text(json.dumps(receipt, indent=2) + "\n")
        print(json.dumps(receipt))
    finally:
        run(compose_args + ["down", "--remove-orphans"], capture_output=True, timeout=90)
