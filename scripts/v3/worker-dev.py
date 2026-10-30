#!/usr/bin/env python3
"""Explicit DEV tools. Internal SQL injection is not a business API or a Bruno journey."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import signal
import shutil
from urllib.parse import urlsplit
import subprocess
import sys
import tempfile
import time
import zipfile
import uuid
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_JAR = ROOT / "app/runtime-command-consumption-worker/target/pocoma-runtime-command-consumption-worker-0.0.1-SNAPSHOT-exec.jar"
MAIN = "com.kartaguez.pocoma.runtime.command.v3.dev.V3WorkerDevelopmentTools"


def properties(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator or key.strip() in result:
            raise ValueError("simple unique key=value properties required")
        result[key.strip()] = re.sub(r"\$\{([A-Z][A-Z0-9_]*)\}", lambda m: os.environ[m[1]], value.strip())
    return result


def java_operation(args, operation: str, *extra: str) -> None:
    jar = args.jar.resolve()
    if not jar.is_file():
        raise ValueError("build the COMMAND exec jar first")
    tracked = subprocess.check_output(["git", "ls-files", "app"], cwd=ROOT, text=True).splitlines()
    sources = [ROOT / p for p in tracked if p.endswith((".java", ".xml", ".properties", ".sql")) and "/src/test/" not in p]
    # Include new untracked production files too; never accept an older artifact silently.
    for module in (ROOT / "app").iterdir():
        if module.is_dir() and (module / "src/main").exists():
            sources.extend(p for p in (module / "src/main").rglob("*") if p.is_file())
    if any(p.exists() and p.stat().st_mtime > jar.stat().st_mtime for p in sources):
        raise ValueError("jar older than production sources; rebuild explicitly")
    with tempfile.TemporaryDirectory(prefix="pocoma-i5-cli-") as directory:
        target = Path(directory)
        with zipfile.ZipFile(jar) as archive:
            for member in archive.infolist():
                if member.filename.startswith(("BOOT-INF/classes/", "BOOT-INF/lib/")):
                    destination = (target / member.filename).resolve()
                    if not destination.is_relative_to(target.resolve()):
                        raise ValueError("unsafe jar path")
                    archive.extract(member, target)
        classpath = os.pathsep.join((str(target / "BOOT-INF/classes"), str(target / "BOOT-INF/lib/*")))
        subprocess.run(["java", "-cp", classpath, MAIN, operation, str(args.config.resolve()), *extra], check=True, cwd=ROOT)


def prepare(args) -> None:
    values = properties(args.config)
    if values.get("pocoma.v3.worker.environment") != "DEV":
        raise ValueError("explicit DEV authorization required")
    java_operation(args, "prepare")
    admin = os.environ["POCOMA_I5_ADMIN_USER"]
    for authority in ("write", "read"):
        prefix = f"pocoma.v3.pilot.{authority}."
        jdbc = values[f"pocoma.v3.worker.{authority}-control.url"]
        endpoint = jdbc.removeprefix("jdbc:").split("?", 1)[0]
        env = dict(os.environ, PGPASSWORD=os.environ["POCOMA_I5_ADMIN_PASSWORD"], PGOPTIONS="-c search_path=" + values[prefix + "schema"])
        parameters = {
            "expected_database": values[prefix + "database"], "expected_schema": values[prefix + "schema"],
            "expected_admin": admin, "authority": authority.upper(), "runtime_role": values[prefix + "username"],
            "reactivation_role": os.environ["POCOMA_I5_REACTIVATION_ROLE"], "injector_role": os.environ["POCOMA_I5_INJECTOR_ROLE"],
            "environment": "DEV", "fixture_identity": values["pocoma.v3.worker.fixture-identity"],
            "technical_probes": values["pocoma.v3.worker.technical-probes"],
            "identity_authority": values.get("pocoma.v3.worker.identity-authority", "fixture"),
            "identity_read_role": values["pocoma.v3.pilot.read.username"],
        }
        stages = ["bootstrap-l2-routing.sql", "grant-l2-lifecycle.sql", "grant-l2-discovery.sql", "bootstrap-l2-worker-dev.sql", "grant-l2-worker-dev.sql"]
        if parameters["identity_authority"] == "real" and authority == "write":
            stages.insert(0, "configure-l3-identity-logging.sql")
        for script in stages:
            command = ["psql", "-X", "--dbname", endpoint, "--username", admin, "-v", "ON_ERROR_STOP=1"]
            for key, value in parameters.items():
                command.extend(("-v", key + "=" + value))
            command.extend(("-f", str(ROOT / "scripts/v3" / script)))
            container = os.environ.get("POCOMA_I5_PSQL_CONTAINER")
            stdin = None
            if container:
                # Explicit disposable Docker target only; verify its published TCP endpoint.
                parsed = urlsplit(endpoint)
                info = json.loads(subprocess.check_output(["docker", "inspect", container], text=True))[0]
                ports = info["NetworkSettings"]["Ports"].get("5432/tcp") or []
                if parsed.hostname not in ("localhost", "127.0.0.1") or not any(int(p["HostPort"]) == parsed.port for p in ports):
                    raise ValueError("psql container differs from the explicit JDBC target")
                command = ["docker", "exec", "-i", "-e", "PGPASSWORD", "-e", "PGOPTIONS", container, "psql", "-X", "--host", "127.0.0.1", "--port", "5432", "--dbname", values[prefix + "database"], "--username", admin, "-v", "ON_ERROR_STOP=1"]
                for key, value in parameters.items():
                    command.extend(("-v", key + "=" + value))
                stdin = (ROOT / "scripts/v3" / script).read_bytes()
            elif not shutil.which("psql"):
                raise ValueError("psql required, or explicit POCOMA_I5_PSQL_CONTAINER for the same disposable server")
            # Administrative SQL stdout may contain fixture values: it is not an operational log.
            run = subprocess.run(command, env=env, input=stdin, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            if run.returncode:
                raise RuntimeError(f"administrative stage refused: {authority}/{script}; no automatic cleanup/reset")
            print(f"I5_DEV_STAGE {authority}/{script} completed")
    java_operation(args, "validate")
    receipt = {"kind": "DEV preparation only, not certification", "git": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(), "jar_sha256": hashlib.sha256(args.jar.read_bytes()).hexdigest()}
    path = args.config.with_suffix(".prepared.json")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as file:
        json.dump(receipt, file, indent=2)
    print("I5_DEV_PREPARED; start/restart never calls prepare")


def demo_identity(args) -> None:
    """Confidential controlled bench. The Worker must already run in explicit real mode."""
    values = properties(args.config)
    if values.get("pocoma.v3.worker.identity-authority") != "real" or values.get("pocoma.v3.worker.identity-request") != "true":
        raise ValueError("explicit real IdentityRequest required")
    issuer = "controlled-l3-dev-" + uuid.uuid4().hex
    with tempfile.TemporaryDirectory(prefix="pocoma-l3-private-") as directory:
        folder = Path(directory)
        def private_file(name, text):
            path = folder / name
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as file:
                file.write(text)
            return path
        def own_token(actor):
            key = private_file(uuid.uuid4().hex, f"issuer={issuer}\nsubject={actor}\n")
            token_path = folder / uuid.uuid4().hex
            java_operation(args, "identity-token", str(key), str(token_path))
            return token_path.read_text()
        def submit(operation, actor, target, token=None, expected="CHANGED"):
            request_id = str(uuid.uuid4())
            time = datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")
            text = f"request-id={request_id}\ncreated-at={time}\noperation={operation}\nrequester.issuer={issuer}\nrequester.subject={actor}\ntarget.issuer={issuer}\ntarget.subject={target}\n"
            if token is not None:
                text += "binding-token=" + token + "\n"
            intention = private_file(request_id, text)
            java_operation(args, "inject-identity", str(intention))
            java_operation(args, "verify-identity", request_id, str(args.await_seconds), expected)
            return request_id
        submit("REGISTRATION", "a", "a")
        submit("REGISTRATION", "c", "c")
        token_a = own_token("a")
        submit("LINK", "a", "b", token_a)
        submit("UNLINK", "a", "b", token_a)
        submit("LINK", "a", "b", token_a)
        submit("UNLINK", "b", "b", own_token("b"))
        submit("UNLINK", "a", "a", token_a)
        submit("LINK", "a", "never", token_a, "REJECTED")
    print("L3_DEV_R_ID_SCENARIO_COMPLETED controlled internal only; external consent/READ/Pot authority unqualified")


def process_identity(pid: int) -> str | None:
    result = subprocess.run(["ps", "-ww", "-p", str(pid), "-o", "lstart=", "-o", "command="], capture_output=True, text=True)
    return result.stdout.strip() or None


def stop(args) -> int:
    if args.pid < 2 or args.pid == os.getpid():
        raise ValueError("explicit Worker PID required")
    identity = process_identity(args.pid)
    if not identity or "java" not in identity or str(args.jar.resolve()) not in identity or "v3-consumption-worker-dev" not in identity or str(args.config.resolve()) not in identity:
        raise ValueError("PID does not identify this configured Worker jar")
    offset = args.log_file.stat().st_size if args.log_file and args.log_file.exists() else 0
    if process_identity(args.pid) != identity:
        raise ValueError("PID identity changed")
    os.kill(args.pid, signal.SIGTERM)
    deadline = time.monotonic() + 105  # Before the configured Spring phase timeout of 120s.
    while time.monotonic() < deadline:
        current = process_identity(args.pid)
        if current != identity:
            if args.log_file and args.log_file.exists():
                with args.log_file.open("rb") as log:
                    log.seek(offset)
                    if f"I5_WORKER_STOPPED pid={args.pid}".encode() in log.read():
                        print("I5_DEV_STOPPED clean Worker callback observed after physical drain")
                        return 0
            print("I5_DEV_STOP_INCONCLUSIVE process exited; clean drain not observed")
            return 3
        time.sleep(0.25)
    if process_identity(args.pid) == identity:
        os.kill(args.pid, signal.SIGKILL)
        print("I5_DEV_CRASH incomplete drain; SIGKILL before forced Spring phase closure; verify PostgreSQL before takeover")
        return 2
    print("I5_DEV_STOP_INCONCLUSIVE identity changed at deadline")
    return 3


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("prepare", "validate", "inspect", "inject-command", "inject-identity", "verify-identity", "identity-token", "demo-identity", "verify", "stop"))
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--jar", type=Path, default=DEFAULT_JAR)
    parser.add_argument("--request-id")
    parser.add_argument("--intention-file", type=Path)
    parser.add_argument("--own-key-file", type=Path)
    parser.add_argument("--token-output", type=Path)
    parser.add_argument("--scenario", choices=("create-pot",), default="create-pot")
    parser.add_argument("--await-seconds", type=int, default=90)
    parser.add_argument("--pid", type=int)
    parser.add_argument("--log-file", type=Path)
    args = parser.parse_args()
    if args.operation == "prepare":
        prepare(args)
    elif args.operation == "stop":
        if args.pid is None:
            parser.error("stop requires --pid")
        return stop(args)
    elif args.operation == "demo-identity":
        demo_identity(args)
    elif args.operation == "inject-identity":
        if args.intention_file is None: parser.error("protected --intention-file required")
        java_operation(args, args.operation, str(args.intention_file.resolve()))
    elif args.operation == "identity-token":
        if args.own_key_file is None or args.token_output is None: parser.error("private key/output files required")
        java_operation(args, args.operation, str(args.own_key_file.resolve()), str(args.token_output.resolve()))
    elif args.operation in ("inject-command", "verify", "verify-identity"):
        if args.request_id is None:
            parser.error("request UUID required")
        extra = [args.request_id] + ([str(args.await_seconds)] if args.operation in ("verify", "verify-identity") else [])
        java_operation(args, args.operation, *extra)
    else:
        java_operation(args, args.operation)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, KeyError, RuntimeError, subprocess.CalledProcessError, OSError) as error:
        # Values, SQL text and child environment are never dumped.
        print("I5_DEV_REFUSED category=" + type(error).__name__, file=sys.stderr)
        raise SystemExit(2)
