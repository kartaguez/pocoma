#!/usr/bin/env python3
"""Run the exact declared I2 gate; retain fresh XML, physical evidence and failed runs."""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
ANCHORS = {"command": "runtime-command-consumption-worker", "transactions": "infra-tx-spring",
           "monolith": "runtime-monolith", "architecture": "architecture-tests"}
MANIFEST = ROOT / "docs/v3/L2_I2_Proof_Manifest.json"

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

def run_gate(gate, destination):
    destination.mkdir(parents=True, exist_ok=False)
    sha = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    dirty = subprocess.check_output(["git", "status", "--porcelain", "--", "app", "scripts"], cwd=ROOT, text=True)
    if dirty:
        raise RuntimeError("Freeze app/scripts in a commit before a certification gate: " + dirty)
    manifest = json.loads(MANIFEST.read_text())
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
        for directory in ("v3-i2-evidence", "v3-pot-evidence", "v3-foundation-evidence", "v3-consumption-evidence"):
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
    failures = [c for c in cases if c["state"] == "FAIL"]
    skips = [c for c in cases if c["state"] == "SKIP"]
    result = {"gate": gate, "sha": sha, "command": command, "cwd": "app/", "started_epoch": started,
              "elapsed_seconds": round(time.time() - started, 3), "exit_code": exit_code,
              "tests": len(cases), "failures_or_errors": len(failures), "skips": skips,
              "required_methods": sum(map(len, required.values())), "problems": problems,
              "verdict": "PASS" if exit_code == 0 and not failures and not problems else "FAIL",
              "cases": cases, "artifacts": files, "log_sha256": digest(logfile)}
    (destination / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k not in ("cases", "artifacts")}, indent=2))
    return 0 if result["verdict"] == "PASS" else 1

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("gate", choices=ANCHORS)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    sys.exit(run_gate(args.gate, args.destination))
