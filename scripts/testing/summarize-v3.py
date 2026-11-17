#!/usr/bin/env python3
"""Run one declared L0 gate and archive fresh, complete evidence. No V3 certification."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET


def sha256(content):
    return hashlib.sha256(content).hexdigest()


def _record_test_case(case, suite_name, state):
    identity = (case.get("classname", suite_name), case.get("name", ""))
    state["cases"][identity] += 1
    failed = case.find("failure") is not None or case.find("error") is not None
    outcome = "FAIL" if failed else ("SKIPPED" if case.find("skipped") is not None else "PASS")
    state["executions"].append(dict(classname=identity[0], method=identity[1], outcome=outcome))
    if case.find("skipped") is not None:
        state["skips"].append(dict(classname=identity[0], method=identity[1],
                                   reason=case.find("skipped").get("message", "")))


def _parse_suite_xml(content):
    suite = ET.fromstring(content)
    if suite.tag != "testsuite":
        raise ValueError("Expected a Surefire testsuite")
    return suite


def _parse_suite_report(name, content, state):
    try:
        suite = _parse_suite_xml(content)
    except (ET.ParseError, ValueError) as failure:
        state["problems"].append(f"{name}: invalid report: {failure}")
        return
    items = suite.findall("testcase")
    declared = {key: int(suite.get(key, "0")) for key in state["counts"]}
    actual = dict(
        tests=len(items),
        failures=sum(c.find("failure") is not None for c in items),
        errors=sum(c.find("error") is not None for c in items),
        skipped=sum(c.find("skipped") is not None for c in items),
    )
    if declared != actual:
        state["problems"].append(f"{name}: inconsistent suite counters")
    for key in state["counts"]:
        state["counts"][key] += actual[key]
    suite_name = suite.get("name", "")
    for case in items:
        _record_test_case(case, suite_name, state)


def _validate_expected_counts(expected, state):
    cases, counts, problems = state["cases"], state["counts"], state["problems"]
    problems.extend(
        f"Expected one fresh execution of {identity[0]}#{identity[1]}, found {cases[identity]}"
        for identity in sorted(expected)
        if cases[identity] != 1
    )
    problems.extend(
        f"Duplicate execution evidence: {identity[0]}#{identity[1]}"
        for identity, number in cases.items()
        if number > 1
    )
    if not counts["tests"]:
        problems.append("No fresh tests executed")
    if counts["skipped"]:
        problems.append("Skipped tests prevent complete gate coverage")


def assess_reports(reports, expected, started_ns, exit_code):
    """reports: (name, bytes, mtime_ns). Missing/stale/invalid evidence never passes."""
    state = dict(
        counts=dict(tests=0, failures=0, errors=0, skipped=0),
        cases=collections.Counter(),
        skips=[],
        problems=[],
        executions=[],
    )
    fresh, stale = [], []
    for name, content, modified_ns in reports:
        if modified_ns < started_ns:
            stale.append(name)
            continue
        fresh.append(name)
        _parse_suite_report(name, content, state)
    _validate_expected_counts(expected, state)
    counts, problems = state["counts"], state["problems"]
    has_failure = bool(exit_code or counts["failures"] or counts["errors"])
    verdict = "FAIL" if has_failure else ("INCONCLUSIVE" if problems else "PASS")
    return dict(verdict=verdict, counts=counts, problems=problems, skips=state["skips"],
                fresh_reports=fresh, excluded_stale_reports=stale, executions=state["executions"])


def capture(command, cwd):
    try:
        result = subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False)
    except OSError as failure:
        return dict(command=command, exit_code=127, stdout="", stderr=str(failure))
    return dict(command=command, exit_code=result.returncode, stdout=result.stdout, stderr=result.stderr)


def source_manifest(root):
    result = subprocess.run(["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
                            cwd=root, capture_output=True, check=True)
    files = {}
    for name in sorted(set(result.stdout.decode().split("\0"))):
        if not (name.startswith(("app/", "docs/", "scripts/", ".github/")) or name == "AGENTS.md"):
            continue
        path = root / name
        files[name] = sha256(path.read_bytes()) if path.is_file() else "DELETED"
    return files


def _execute_gate_command(command, app_root, log_path):
    with log_path.open("wb") as log:
        try:
            return subprocess.run(
                command, cwd=app_root, stdout=log, stderr=subprocess.STDOUT, check=False
            ).returncode
        except OSError as failure:
            log.write(str(failure).encode())
            return 127


def _build_proof_results(manifest, definition, executions):
    proof_results = {}
    for proof_id in definition["suites"]:
        proof = manifest["proofs"][proof_id]
        observed = [item for item in executions if item["classname"] == proof["class"]]
        outcomes = [item["outcome"] for item in observed]
        names = collections.Counter(item["method"] for item in observed)
        complete = all(names[method] == 1 for method in proof["methods"])
        if "FAIL" in outcomes:
            verdict = "FAIL"
        elif complete and all(value == "PASS" for value in outcomes):
            verdict = "PASS"
        else:
            verdict = "INCONCLUSIVE"
        proof_results[proof_id] = dict(
            verdict=verdict,
            spec=proof["spec"],
            classname=proof["class"],
            expected_methods=proof["methods"],
            executions=observed,
        )
    return proof_results


def _archive_artifacts(evidence, reports, fresh_reports):
    artifacts = {}
    fresh_set = set(fresh_reports)
    for name, content, _ in reports:
        if name not in fresh_set:
            continue
        target = evidence / "reports" / Path(name).relative_to("app")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(content)
        artifacts[str(target.relative_to(evidence))] = sha256(content)
    for name in ("maven.log", "source-manifest.json", "proof-manifest.json"):
        artifacts[name] = sha256((evidence / name).read_bytes())
    return artifacts


def run_gate(root, gate):
    manifest_path = root / "docs/v3/L0_Proof_Manifest.json"
    manifest = json.loads(manifest_path.read_text())
    definition = manifest["gates"][gate]
    command = definition["command"]
    expected = {
        (manifest["proofs"][proof]["class"], method)
        for proof in definition["suites"]
        for method in manifest["proofs"][proof]["methods"]
    }
    run_id = str(uuid.uuid4())
    evidence = root / "app/infra-tx-spring/target/v3-l0-evidence" / run_id
    evidence.mkdir(parents=True, exist_ok=False)
    print(f"L0 {gate}: {' '.join(command)}\nEvidence: {evidence}", flush=True)
    before = source_manifest(root)
    (evidence / "source-manifest.json").write_text(json.dumps(before, indent=2) + "\n")
    shutil.copy2(manifest_path, evidence / "proof-manifest.json")
    environment = dict(
        java=capture(["java", "-version"], root),
        docker=capture(["docker", "version", "--format", "{{.Server.Version}}"], root),
        python=capture(["python3", "--version"], root),
    )
    revision = capture(["git", "rev-parse", "HEAD"], root)["stdout"].strip()
    status = capture(["git", "status", "--short"], root)["stdout"]
    started_ns = time.time_ns()
    exit_code = _execute_gate_command(command, root / "app", evidence / "maven.log")
    reports = [
        (str(path.relative_to(root)), path.read_bytes(), path.stat().st_mtime_ns)
        for path in sorted((root / "app").glob("*/target/surefire-reports/TEST-*.xml"))
    ]
    result = assess_reports(reports, expected, started_ns, exit_code)
    proof_results = _build_proof_results(manifest, definition, result["executions"])
    if source_manifest(root) != before:
        result["problems"].append("Sources changed while the gate ran")
        if result["verdict"] == "PASS":
            result["verdict"] = "INCONCLUSIVE"
    artifacts = _archive_artifacts(evidence, reports, result["fresh_reports"])
    result.update(
        schema_version=1,
        run_id=run_id,
        gate=gate,
        scope=manifest["scope"],
        certification=False,
        command=command,
        cwd=str(root / "app"),
        commit=revision,
        working_tree=status,
        started_ns=started_ns,
        finished_ns=time.time_ns(),
        exit_code=exit_code,
        environment=environment,
        proof_ids=definition["suites"],
        artifacts=artifacts,
    )
    result["proof_results"] = proof_results
    (evidence / "result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(dict(verdict=result["verdict"], counts=result["counts"], evidence=str(evidence))))
    return 0 if result["verdict"] == "PASS" else (1 if result["verdict"] == "FAIL" else 2)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--gate", choices=("foundation", "architecture", "architecture-focus"), default="foundation")
    args = parser.parse_args()
    raise SystemExit(run_gate(args.root.resolve(), args.gate))
