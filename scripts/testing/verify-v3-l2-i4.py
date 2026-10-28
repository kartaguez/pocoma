#!/usr/bin/env python3
"""Run the exact declared I4 gate; retain fresh XML, physical evidence and failed runs."""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time
import re
import xml.etree.ElementTree as POM
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANCHORS = {"command": "runtime-command-consumption-worker", "read": "infra-persistence-read-jdbc",
           "monolith": "runtime-monolith", "architecture": "architecture-tests"}
MANIFEST = ROOT / "docs/v3/L2_I4_Proof_Manifest.json"

def qualified_cases(xml):
    suite = ET.parse(xml).getroot()
    cases = []
    for case in suite.iter("testcase"):
        state = "FAIL" if case.find("failure") is not None or case.find("error") is not None else "SKIP" if case.find("skipped") is not None else "PASS"
        cases.append({"class": case.get("classname"), "method": case.get("name"), "state": state})
    return cases

def require_methods(cases, required):
    problems = []
    for classname, methods in required.items():
        for method in methods:
            matches = [c for c in cases if c["class"] == classname and c["method"] == method]
            if len(matches) != 1 or matches[0]["state"] != "PASS":
                problems.append(f"{classname}.{method}: {matches or 'MISSING'}")
    return problems

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def require_physical(app, requirements, groups, started):
    problems = []
    for requirement in requirements:
        if requirement["group"] not in groups:
            continue
        path = app / requirement["path"]
        if not path.is_file() or path.stat().st_mtime < started - 1:
            problems.append("Missing/stale physical evidence: " + str(path))
        else:
            content = path.read_text()
            for needle in requirement["contains"]:
                if needle not in content:
                    problems.append("Missing subvariant " + needle + " in " + str(path))
    return problems

def input_hashes(root=ROOT):
    paths = subprocess.check_output(["git", "ls-files", "--", "app", "scripts", "docs/testing/evidence/L2-I4/Performance-inputs.json"], cwd=root, text=True).splitlines()
    return {path: digest(root/path) for path in paths}

def require_sources(root, expected):
    return ["Changed/missing tested source: "+path for path, value in expected.items()
            if not (root/path).is_file() or digest(root/path) != value]

def require_compiled(root, paths, modules):
    problems, binaries = [], {}
    for path in paths:
        if not path.endswith(".java") or not path.startswith("app/"):
            continue
        parts = Path(path).parts
        if parts[1] not in modules:
            continue
        mode = "test-classes" if parts[3] == "test" else "classes"
        target = root / "app" / parts[1] / "target" / mode / Path(*parts[5:]).with_suffix(".class")
        source = root / path
        if not target.is_file() or target.stat().st_mtime < source.stat().st_mtime - 1:
            problems.append("Missing/stale compiled source: "+path)
        else:
            binaries[str(target.relative_to(root))] = digest(target)
    return problems, binaries

def generate_performance_inputs(destination):
    """Independent, deterministic Python vectors; no application or SQL routing code."""
    import uuid
    families = ("COMMAND", "IDENTITY_REQUEST", "POT_EVENT", "INVITATION_EVENT",
                "IDENTITY_EVENT", "COMMAND_COMPLETION_EVENT", "IDENTITY_COMPLETION_EVENT")
    result = {"generator": "Python hashlib.sha256 independent of Java/SQL production routing; UUID integers from 1000000, unsigned native order",
              "seed": 1000000, "families": {}}
    for family in families:
        partitions = {"p0": [], "p1": []}
        value = 1000000
        while min(map(len, partitions.values())) < 10000:
            identifier = uuid.UUID(int=value)
            hashed = hashlib.sha256(b"pocoma-consumable-v1\0" + family.encode() + b"\0" + identifier.bytes).digest()
            partition = int.from_bytes(hashed[:4], "big", signed=True) % 4
            key = "p"+str(partition)
            if key in partitions and len(partitions[key]) < 10000:
                partitions[key].append(str(identifier))
            value += 1
        result["families"][family] = partitions
    destination.write_text(json.dumps(result, indent=1)+"\n")

def measurement_summary(destination):
    """Descriptive quantiles of observed physical lock duration bounds, not SLOs."""
    samples = {"partition": [], "source": []}
    for path in (destination/"physical").rglob("*.txt"):
        for key, lower, upper in re.findall(r"LOCK duration physical=COMMITTED key=(\S+) .*?lowerNs=(\d+) upperNs=(\d+)", path.read_text(errors="replace")):
            samples["partition" if key == "partition" else "source"].append((int(lower), int(upper)))
    result = {}
    for name, values in samples.items():
        if not values:
            continue
        upper = sorted(v[1] for v in values)
        result[name] = {"samples": len(values), "upper_bound_p50_ns": upper[(len(upper)-1)//2],
                        "upper_bound_p95_ns": upper[max(0,(len(upper)*95+99)//100-1)],
                        "upper_bound_max_ns": upper[-1], "lower_bound_max_ns": max(v[0] for v in values),
                        "meaning": "PG clocks bracket actual acquisition and confirmed commit; includes observer/JDBC instrumentation overhead; no universal bound"}
    return result

def run_gate(gate, destination):
    destination.mkdir(parents=True, exist_ok=False)
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = subprocess.check_output(["git", "status", "--porcelain", "--", "app", "scripts", "docs/testing/evidence/L2-I4/Performance-inputs.json"], cwd=ROOT, text=True)
    if dirty:
        raise RuntimeError("Freeze app/scripts in a commit before a certification gate: " + dirty)
    manifest = json.loads(MANIFEST.read_text())
    inputs = input_hashes()
    modes = subprocess.check_output(["git","ls-files","-s","--","app","scripts","docs/testing/evidence/L2-I4/Performance-inputs.json"],cwd=ROOT,text=True).splitlines()
    started = time.time()
    command = ["./mvnw", "-pl", ANCHORS[gate], "-am", "test"]
    logfile = destination / "maven.log"
    with logfile.open("w") as output:
        exit_code = subprocess.run(command, cwd=ROOT / "app", stdout=output, stderr=subprocess.STDOUT).returncode
    cases, files = [], []
    for xml in sorted((ROOT / "app").glob("*/target/surefire-reports/TEST-*.xml")):
        if xml.stat().st_mtime < started - 1:
            continue
        cases.extend(qualified_cases(xml))
        target = destination / "xml" / xml.parent.parent.parent.name / xml.name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(xml, target)
        files.append({"path": str(target.relative_to(destination)), "sha256": digest(target)})
    for module in (ROOT / "app").iterdir():
        if not module.is_dir():
            continue
        for directory in ("v3-i4-evidence", "v3-i3-evidence", "v3-i2-evidence", "v3-pot-evidence", "v3-foundation-evidence", "v3-consumption-evidence"):
            folder = module / "target" / directory
            if not folder.is_dir():
                continue
            for source in folder.rglob("*"):
                if source.is_file() and source.stat().st_mtime >= started - 1:
                    target = destination / "physical" / module.name / directory / source.relative_to(folder)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(source, target)
                    files.append({"path": str(target.relative_to(destination)), "sha256": digest(target)})
    groups = manifest["gate_groups"][gate]
    required = {}
    for group in groups:
        for classname, methods in manifest["required_groups"][group].items():
            required.setdefault(classname, []).extend(methods)
    problems = require_methods(cases, required)
    problems.extend(require_physical(ROOT / "app", manifest["physical_requirements"], groups, started))
    problems.extend(require_sources(ROOT, inputs))
    built_artifacts = set(re.findall(r"^\[INFO\] (pocoma-[\w-]+) \.+ (?:SUCCESS|FAILURE)", logfile.read_text(), re.M))
    modules = set()
    for pom in (ROOT/"app").glob("*/pom.xml"):
        artifact = POM.parse(pom).getroot().find("{http://maven.apache.org/POM/4.0.0}artifactId")
        if artifact is not None and artifact.text in built_artifacts:
            modules.add(pom.parent.name)
    changed = subprocess.check_output(["git","diff","--name-only","489259dbad757e3256572f8ce2041a187adeb38d","HEAD","--","app"],cwd=ROOT,text=True).splitlines()
    if ANCHORS[gate] not in modules: problems.append("Cannot establish compiled gate closure")
    compiled_problems, binaries = require_compiled(ROOT, changed, modules)
    problems.extend(compiled_problems)

    dirty_after = subprocess.check_output(["git", "status", "--porcelain", "--", "app", "scripts", "docs/testing/evidence/L2-I4/Performance-inputs.json"], cwd=ROOT, text=True)
    if dirty_after: problems.append("Code changed during gate: "+dirty_after)
    failures = [c for c in cases if c["state"] == "FAIL"]
    skips = [c for c in cases if c["state"] == "SKIP"]
    for case in skips:
        if not (case["class"].endswith(".TerminalSequencingPostgresTest") and case["method"] == "zExploratoryContentionSeparateFromCorrectness"):
            problems.append("Unexpected skip: "+str(case))
    result = {"measurement_summary": measurement_summary(destination), "manifest_sha256": digest(MANIFEST), "input_sha256": inputs, "git_input_modes": modes, "compiled_sha256": binaries, "gate": gate, "sha": sha, "command": command, "cwd": "app/", "started_epoch": started,
              "elapsed_seconds": round(time.time() - started, 3), "exit_code": exit_code,
              "tests": len(cases), "failures_or_errors": len(failures), "skips": skips,
              "required_methods": sum(map(len, required.values())), "problems": problems,
              "verdict": "PASS" if exit_code == 0 and not failures and not problems else "FAIL",
              "cases": cases, "artifacts": files, "log_sha256": digest(logfile)}
    (destination / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k not in ("cases", "artifacts", "input_sha256", "git_input_modes", "compiled_sha256")}, indent=2))
    return 0 if result["verdict"] == "PASS" else 1

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("gate", choices=(*ANCHORS, "generate-inputs"))
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    if args.gate == "generate-inputs":
        generate_performance_inputs(args.destination)
    else:
        sys.exit(run_gate(args.gate, args.destination))
