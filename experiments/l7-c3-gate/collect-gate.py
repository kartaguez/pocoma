#!/usr/bin/env python3
"""Collect this unfiltered Maven gate from one checkout with a single Maven owner."""

import collections
import hashlib
import json
import os
import re
import sys
import tarfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TESTED_ROOT = Path(os.environ.get("POCOMA_C3_TESTED_ROOT", ROOT)).resolve()
RUN = Path(sys.argv[1]).resolve()
DEST = Path(sys.argv[2]).resolve()
OLD = ROOT / "docs/testing/evidence/L7-C3/command-result-pilot-gates.tar.gz"
COUNTERS = ("tests", "failures", "errors", "skipped")
CASE_TYPE = tuple[str, str, str]
COMMAND = "cd app && ./mvnw --fail-at-end -pl runtime-worker-materialize,architecture-tests -am test"


def module_key(artifact):
    return artifact.removeprefix("pocoma-")


def inventory(data, module):
    suite = ET.fromstring(data)
    cases = {(
        module, node.attrib["classname"], node.attrib["name"]
    ): next((tag for tag in ("failure", "error", "skipped") if node.find(tag) is not None), "passed")
        for node in suite.findall("testcase")}
    counts = [int(suite.attrib[name]) for name in COUNTERS]
    actual = [len(cases)] + [sum(x == tag for x in cases.values()) for tag in ("failure", "error", "skipped")]
    if counts != actual:
        raise ValueError(f"Suite counters differ from cases: {module} {suite.attrib['name']} {counts} != {actual}")
    return counts, cases


log = (RUN / "maven.log").read_text(errors="replace")
exit_code = int((RUN / "maven.exit").read_text())
start = (RUN / "start").stat().st_mtime_ns
modules = list(dict.fromkeys(re.findall(r"^\[INFO\] Building (pocoma[\w-]*) .+\[\s*\d+/\d+\]$", log, re.M)))
reactor_order = re.search(r"Reactor Build Order:.*?^\[INFO\] -{10,}", log, re.S | re.M)
if reactor_order is None:
    raise ValueError("Missing Maven build order")
expected = re.findall(r"^\[INFO\] (pocoma[\w-]*)\s+\[(?:pom|jar)\]$", reactor_order[0], re.M)
summary = log.split("Reactor Summary")[-1]
statuses = dict(re.findall(r"^\[INFO\] (pocoma[\w-]*)\s+.*?\b(SUCCESS|FAILURE|SKIPPED)\b", summary, re.M))
if len(expected) != 43 or set(expected) != set(statuses):
    raise ValueError(f"Reactor summary incomplete: {len(expected)} declared, {len(statuses)} statuses")

baseline = set()
with tarfile.open(OLD) as previous:
    for member in previous.getmembers():
        if member.name.startswith("reports/") and member.name.endswith(".xml"):
            artifact = member.name.split("/")[1]
            _, cases = inventory(previous.extractfile(member).read(), module_key(artifact))
            baseline.update(cases)

followup = json.loads((OLD.parent / "command-result-pilot-followup.json").read_text())
baseline.update(("architecture-tests", cls, case) for cls, case in followup["runs"]["architecture"]["cases"])

observed: dict[CASE_TYPE, str] = {}
entries = {artifact: dict(zip(COUNTERS, [0] * 4), status=statuses[artifact], suites=0, entered=artifact in modules)
           for artifact in expected}
members = {}
for filename in ("maven.log", "maven.exit", "toolchain.txt", "git-status-before.txt",
                 "untracked-before.sha256", "app-inputs.sha256", "gate-environment.txt", "surefire-before.sha256", "checkout.log", "fresh-bytecode.txt",
                 "historical-inputs-match.json"):
    path = RUN / filename
    if path.is_file():
        members[filename] = path

for artifact in expected:
    module = module_key(artifact)
    entry = entries[artifact]
    if artifact == "pocoma":
        continue
    target = TESTED_ROOT / "app" / module / "target"
    for report in sorted((target / "surefire-reports").glob("TEST-*.xml")):
        if report.stat().st_mtime_ns <= start:
            continue
        counts, cases = inventory(report.read_bytes(), module)
        if observed.keys() & cases.keys():
            raise ValueError(f"Duplicate fresh cases in {report}")
        observed.update(cases)
        entry["suites"] += 1
        for name, count in zip(COUNTERS, counts):
            entry[name] += count
        members[f"reports/{module}/{report.name}"] = report

for report in (TESTED_ROOT / "app/runtime-worker-materialize/target/l4-i1-evidence").glob(
        "development/pocoma-runtime-worker-materialize/physical/*"):
    if report.stat().st_mtime_ns > start and ("-c3-" in report.name or report.name.startswith("c3-measure-")):
        members[f"physical/{report.name}"] = report

bruno = {}
for case, corpus in (("brunoB2PublicProtocol", "b2"), ("brunoB4StandaloneCorpus", "b4")):
    match = [key for key in observed if key[2] == case]
    receipts = []
    for receipt in sorted((TESTED_ROOT / "app/runtime-monolith/target").glob(f"bruno-{corpus}*/receipt.json")):
        if receipt.stat().st_mtime_ns > start:
            data = json.loads(receipt.read_text())
            receipts.append({"directory": receipt.parent.name, "command": data.get("command"),
                             "metadata": {k: v for k, v in data.items()
                                          if k in {"cliVersion", "exit", "expected", "actual", "requests",
                                                   "scenarios", "helperRequests", "primaryRequests",
                                                   "externalIdpCertified"}}})
            for name in ("receipt.json", "bruno.json"):
                path = receipt.with_name(name)
                if path.is_file():
                    members[f"bruno/{receipt.parent.name}/{name}"] = path
    if corpus == "b2":
        validated = any(
            r["metadata"].get("exit") == 0
            and r["metadata"].get("actual") == r["metadata"].get("expected")
            and bool(r["metadata"].get("actual")) for r in receipts
        )
    else:
        validated = any(
            r["metadata"].get("scenarios") == ["B401", "B402", "B403", "B404"]
            and r["metadata"].get("primaryRequests") == 4 for r in receipts
        )
    bruno[case] = {"outcome": observed.get(match[0]) if len(match) == 1 else None,
                   "receipts": receipts, "validated": validated}

missing = sorted(baseline - observed.keys())
unexpected = sorted(observed.keys() - baseline)
totals = {name: sum(entry[name] for entry in entries.values()) for name in COUNTERS}
with tarfile.open(DEST / "maven-gate.tar.gz", "w:gz") as archive:
    for name, path in sorted(members.items()):
        archive.add(path, arcname=name, recursive=False)

problem_cases = [{"module": module, "class": cls, "method": case, "outcome": outcome}
                 for (module, cls, case), outcome in observed.items() if outcome != "passed"]
architecture = entries["pocoma-architecture-tests"]
success = (exit_code == 0 and "BUILD SUCCESS" in log and not missing
           and not any(totals[k] for k in COUNTERS[1:]) and all(s == "SUCCESS" for s in statuses.values())
           and architecture["tests"] == 52
           and any(k[2] == "migrationBytesAndResourceOwnershipAreConserved"
                   and v == "passed" for k, v in observed.items())
           and all(value["outcome"] == "passed" and value["validated"] for value in bruno.values()))
receipt = {
    "command": COMMAND, "maven_exit": exit_code,
    "verdict": "PASS" if success else ("FAIL" if exit_code or any(totals[k] for k in COUNTERS[1:3]) else "INCONCLUSIVE"),
    "selection_exclusions": [],
    "historical_inventory_missing": missing, "new_cases_vs_historical_inventory": unexpected,
    "modules": entries, "totals": totals, "bruno": bruno, "problem_cases": problem_cases,
    "maven_total_time": re.findall(r"^\[INFO\] Total time:\s+(.+)", log, re.M)[-1],
    "archive": {"sha256": hashlib.file_digest((DEST / "maven-gate.tar.gz").open("rb"), "sha256").hexdigest(),
                "members": {name: hashlib.file_digest(path.open("rb"), "sha256").hexdigest()
                            for name, path in sorted(members.items())}},
}
(DEST / "maven-gate.json").write_text(json.dumps(receipt, indent=2, ensure_ascii=False) + "\n")
print(receipt["verdict"], totals)
