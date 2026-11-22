"""Audit the fresh reports from the interrupted prefix and two resume phases."""

from collections import Counter
from hashlib import file_digest
import json
from pathlib import Path
import re
import sys
import tarfile
from xml.etree import ElementTree

ROOT = Path.cwd()
RUN = Path(sys.argv[1])
DEST = Path(sys.argv[2])
TESTED = RUN / "source"
COUNTERS = ("tests", "failures", "errors", "skipped")
PREFIX = "cd app && ./mvnw --batch-mode --no-transfer-progress -pl runtime-worker-materialize,runtime-web-write,runtime-web-read,architecture-tests -am test"
PHASES = ("resume-install", "resume-worker-metrics", "resume-canonical", "replay-monolith")


def xml_results(data, module):
    doc = ElementTree.fromstring(data)
    cases_list = [
        ((module, case.attrib["classname"], case.attrib["name"]),
         next((tag for tag in ("failure", "error", "skipped") if case.find(tag) is not None), "passed"))
        for case in doc.findall("testcase")
    ]
    cases = dict(cases_list)
    expected = [int(doc.get(counter)) for counter in COUNTERS]
    counted = Counter(cases.values())
    actual = [len(cases), counted["failure"], counted["error"], counted["skipped"]]
    if len(cases) != len(cases_list) or expected != actual:
        raise ValueError(f"Invalid Surefire counters for {module}/{doc.get('name')}")
    return cases


def add_cases(target, cases):
    overlap = target.keys() & cases.keys()
    if overlap:
        raise ValueError(f"Duplicate Surefire identities {sorted(overlap)!r}")
    target.update(cases)


def sha(path):
    with path.open("rb") as file:
        return file_digest(file, "sha256").hexdigest()


old_inventory = {}
golden = ROOT / "docs/testing/evidence/L7-C3/complement/maven-gate.tar.gz"
with tarfile.open(golden) as previous:
    for member in previous.getmembers():
        parts = member.name.split("/")
        if len(parts) == 3 and parts[0] == "reports" and parts[2].endswith(".xml"):
            add_cases(old_inventory, xml_results(previous.extractfile(member).read(), parts[1]))
expected_tests = set(old_inventory)
additional_test = next((TESTED / "app/runtime-worker-materialize/src/test/java").rglob("ReadAvailabilityPostgresTest.java"))
for method in re.findall(r"@Test\s+void (\w+)\(", additional_test.read_text()):
    expected_tests.add(("runtime-worker-materialize", "com.kartaguez.pocoma.runtime.task.ReadAvailabilityPostgresTest", method))

initial = (RUN / "maven.log").read_text(errors="replace")
order = re.search(r"Reactor Build Order:.*?^\[INFO\] -{10,}", initial, re.M | re.S)
expected_modules = re.findall(r"^\[INFO\] (pocoma[\w-]*)\s+\[(?:pom|jar)\]$", order[0], re.M)
split = expected_modules.index("pocoma-runtime-worker-materialize")
prefix_modules, suffix_modules = expected_modules[:split], expected_modules[split:]
observed_prefix = list(dict.fromkeys(re.findall(
    r"^\[INFO\] Building (pocoma[\w-]*) .+\[\s*\d+/\d+\]$", initial, re.M)))
def module_status(filename):
    log = (RUN / filename).read_text(errors="replace")
    return dict(re.findall(r"^\[INFO\] (pocoma[\w-]*)\s+.*?\b(SUCCESS|FAILURE|SKIPPED)\b",
                           log.split("Reactor Summary")[-1], re.M))


first_resume = module_status("resume-canonical.log")
suffix_summary = {"pocoma-runtime-worker-materialize": first_resume.get("pocoma-runtime-worker-materialize")}
suffix_summary.update(module_status("replay-monolith.log"))

phases = []
archive_paths = {name: RUN / name for name in ("maven.log", "app-inputs.json", "app-inputs-final.json",
                                                                 "status.txt", "head.txt", "resume.sh",
                                                                 "replay-monolith.sh", "old-monolith-xml.tar.gz",
                                                                 "invitation-v5-fix.log")}
for phase in PHASES:
    code = int((RUN / (phase + ".exit")).read_text())
    phases.append({"phase": phase, "command": (RUN / (phase + ".command")).read_text().strip(), "exit": code})
    for ext in (".exit", ".command", ".log"):
        archive_paths[phase + ext] = RUN / (phase + ext)

observed = {}
by_module = {}
for artifact in expected_modules:
    if artifact == "pocoma":
        continue
    module = artifact.removeprefix("pocoma-")
    cases = {}
    for path in sorted((TESTED / f"app/{module}/target/surefire-reports").glob("TEST-*.xml")):
        start = RUN / ("replay-monolith.start" if module in {"runtime-monolith", "architecture-tests"} else "start")
        if path.stat().st_mtime_ns <= start.stat().st_mtime_ns:
            raise ValueError(f"Stale report {path}")
        add_cases(cases, xml_results(path.read_bytes(), module))
        archive_paths[f"reports/{module}/{path.name}"] = path
    add_cases(observed, cases)
    by_module[module] = {"tests": len(cases), **{key: sum(case == key for case in cases.values())
                                                  for key in ("failure", "error", "skipped")}}

assert "BUILD SUCCESS" not in initial and "BUILD FAILURE" not in initial
bruno_cases = {}
for method, corpus in (("brunoB2PublicProtocol", "b2"), ("brunoB4StandaloneCorpus", "b4")):
    invocations = [case for key, case in observed.items() if key[2] == method]
    evidence = list((TESTED / "app/runtime-monolith/target").glob(f"bruno-{corpus}*/receipt.json"))
    data = []
    for path in evidence:
        data.append(json.loads(path.read_text()))
        for file in (path, path.with_name("bruno.json")):
            if file.is_file():
                archive_paths[f"bruno/{file.parent.name}/{file.name}"] = file
    metadata_valid = (any(row.get("exit") == 0 and row.get("actual") and row["actual"] == row.get("expected")
                          for row in data) if corpus == "b2" else
                      any(row.get("scenarios") == ["B401", "B402", "B403", "B404"] and row.get("primaryRequests") == 4
                          for row in data))
    bruno_cases[method] = {"status": invocations, "receiptValidated": metadata_valid}

errors = sorted((key, result) for key, result in observed.items() if result != "passed")
missing = sorted(expected_tests - observed.keys())
extra = sorted(observed.keys() - expected_tests)
initial_inputs = json.loads((RUN / "app-inputs.json").read_text())
inputs = json.loads((RUN / "app-inputs-final.json").read_text())
fixture_only_changes = sorted(path for path in inputs if inputs[path] != initial_inputs[path])
known_fixture_paths = sorted([
    "app/runtime-monolith/src/test/java/com/kartaguez/pocoma/InvitationReadPostgresTest.java",
    "app/runtime-monolith/src/test/java/com/kartaguez/pocoma/ReadWorkerResourcesPostgresTest.java",
])
changed_inputs = [p for p, expected in inputs.items() if sha(TESTED / p) != expected or sha(ROOT / p) != expected]
expected_suffix = suffix_summary == dict.fromkeys(suffix_modules, "SUCCESS")
prefix_ok = observed_prefix == prefix_modules
worker_metrics = by_module["runtime-worker-process"]["tests"] == sum(
    module == "runtime-worker-process" for module, _, _ in expected_tests)
architecture = by_module["architecture-tests"]["tests"] == 52
bruno_ok = all(v["status"] == ["passed"] and v["receiptValidated"] for v in bruno_cases.values())
coverage_ok = (len(expected_modules) == 43 and prefix_ok and expected_suffix and worker_metrics and architecture and bruno_ok
               and not (errors or missing or extra or changed_inputs) and fixture_only_changes == known_fixture_paths
               and all(phase["exit"] == (1 if phase["phase"] == "resume-canonical" else 0) for phase in phases))
for report in (TESTED / "app/runtime-worker-materialize/target/l4-i1-evidence").glob(
        "development/pocoma-runtime-worker-materialize/physical/*"):
    if "-c3-" in report.name or report.name.startswith("c3-measure-"):
        archive_paths[f"physical/{report.name}"] = report

with tarfile.open(DEST / "maven-gate.tar.gz", "w:gz") as archive:
    for name, path in sorted(archive_paths.items()):
        archive.add(path, arcname=name, recursive=False)

receipt = {
    "canonicalPrefix": {"command": PREFIX, "outcome": "INTERRUPTED", "completedModulesBeforeWorkerMetrics": prefix_modules[:-1],
                        "interruptedModule": prefix_modules[-1], "lastStartedClass": "WorkerMetricsTest"},
    "resumePhases": phases, "firstResumeModuleStatuses": first_resume, "resumedModuleStatuses": suffix_summary,
    "fixtureOnlyChangesAfterFirstResume": fixture_only_changes,
    "coverage": "COMPLETE" if coverage_ok else "INCOMPLETE_OR_FAILED",
    "expectedModules": len(expected_modules), "expectedTests": len(expected_tests), "observedTests": len(observed),
    "testProblems": errors, "missingTests": missing, "unexpectedTests": extra, "changedAppInputs": changed_inputs,
    "bruno": bruno_cases, "byModule": by_module,
    "checks": dict(prefix=prefix_ok, suffix=expected_suffix, workerMetrics=worker_metrics, architecture=architecture),
    "rawArchive": {"sha256": sha(DEST / "maven-gate.tar.gz"),
                   "members": {name: sha(path) for name, path in archive_paths.items()}},
}
(DEST / "maven-gate.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2) + "\n")
print(json.dumps({key: receipt[key] for key in ("coverage", "expectedTests", "observedTests", "checks")}))
