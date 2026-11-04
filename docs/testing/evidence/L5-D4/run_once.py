"""One bounded, isolated calibration invocation. Not a reusable benchmark framework."""
import hashlib
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import tempfile
import time
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
BASE = "5193c540c5be2ea6bf31d4fe159c02490ab352aa"
def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
def app_hashes():
    return {p: sha(ROOT / p) for p in git("ls-files", "app", "scripts/v3").splitlines()}

# Static preflight: no server, Docker, Maven, network, socket or database is used.
assert git("branch", "--show-current") == "feat/v3-architecture"
assert git("rev-parse", "HEAD") == BASE
assert not git("status", "--porcelain", "--untracked-files=all", "--", "app", "scripts/v3")
for key in ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"]:
    assert not os.environ.get(key), f"unreviewed JVM override: {key}"
assert not (OUT / "campaign.json").exists(), "no automatic retry or second campaign"
sources = [
    "app/domain-pot/src/main/java/com/kartaguez/pocoma/domain/pot/v3/Fraction.java",
    "app/engine-read-pot/src/main/java/com/kartaguez/pocoma/engine/read/pot/v3/ReadPotV3Result.java",
]
m2 = Path.home() / ".m2/repository/com/fasterxml/jackson/core"
jars = [m2 / f"{a}/{v}/{a}-{v}.jar" for a,v in [
    ("jackson-annotations", "2.21"), ("jackson-core", "2.21.2"), ("jackson-databind", "2.21.2")]]
assert all(p.is_file() for p in jars)
before = app_hashes()
environment = {"os": platform.platform(), "machine": platform.machine(), "python": platform.python_version(),
    "java_version": subprocess.check_output(["java", "-version"], stderr=subprocess.STDOUT, text=True).strip(),
    "javac_version": subprocess.check_output(["javac", "-version"], stderr=subprocess.STDOUT, text=True).strip(),
    "java_path": subprocess.check_output(["/usr/bin/which", "java"], text=True).strip(),
    "jars": {str(p.relative_to(Path.home())): sha(p) for p in jars},
    "source_hashes": {p: sha(ROOT/p) for p in sources},
    "harness_sha256": sha(OUT/"Calibration.java"), "runner_sha256": sha(Path(__file__)),
    "app_and_sql_hashes_before": before}
(OUT / "environment.json").write_text(json.dumps(environment, indent=2)+"\n")
start = time.monotonic()
receipt = {"head": BASE, "started_utc": datetime.now(timezone.utc).isoformat(), "limit_seconds": 1800,
    "processes": [], "database_connections": 0, "automatic_retries": 0, "status": "RUNNING"}
def save():
    receipt["elapsed_seconds"] = time.monotonic()-start
    (OUT/"campaign.json").write_text(json.dumps(receipt,indent=2)+"\n")
def run(argv, filename):
    remaining = 1790-(time.monotonic()-start)  # reserve ten seconds for physical cleanup
    if remaining <= 0: raise TimeoutError("campaign budget exhausted")
    then = time.monotonic()
    with (OUT/filename).open("wb") as log:
        process = subprocess.Popen(argv,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
        safe_argv = [v.replace(str(ROOT),"<repo>").replace(str(Path.home()),"~") for v in argv]
        safe_argv = [v.replace(directory,"<temporary-classes>") for v in safe_argv]
        row = {"argv": safe_argv, "pid": process.pid, "process_group": process.pid}
        receipt["processes"].append(row); save()
        try:
            process.wait(timeout=remaining)
        except BaseException:
            os.killpg(process.pid,signal.SIGTERM)
            try: process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,signal.SIGKILL); process.wait(timeout=2)
            raise
        finally:
            row.update(exit_code=process.returncode,seconds=time.monotonic()-then,reaped=process.returncode is not None)
            try: os.killpg(process.pid,0); row["process_group_absent"]=False
            except ProcessLookupError: row["process_group_absent"]=True
            except PermissionError: row["process_group_absent"]="UNKNOWN"
            save()
        if process.returncode: raise RuntimeError(f"experiment exited {process.returncode}; no retry")
def interrupted(signum, frame):
    raise KeyboardInterrupt(f"campaign interrupted by signal {signum}")
signal.signal(signal.SIGTERM, interrupted)
try:
    with tempfile.TemporaryDirectory(prefix="pocoma-l5-d4-") as directory:
        cp = os.pathsep.join(map(str,jars))
        run(["javac","-cp",cp,"-d",directory,*sources,str(OUT/"Calibration.java")],"compile.log")
        run(["java","-Xms64m","-Xmx256m","-cp",directory+os.pathsep+cp,"Calibration"],"measurements.json")
    receipt["status"]="COMPLETED"
except BaseException as exc:
    receipt["status"]="STOPPED_WITH_ERROR"
    receipt["error"]=str(exc)
    raise
finally:
    receipt["app_and_sql_unchanged"]=before==app_hashes()
    receipt["ended_utc"]=datetime.now(timezone.utc).isoformat()
    save()
print(json.dumps({k:v for k,v in receipt.items() if k!="processes"},indent=2))
