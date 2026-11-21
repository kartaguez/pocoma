"""Cross-check the historical C3 XML and attribute its suites to the surviving Maven logs."""
from collections import Counter
from hashlib import sha256
import json
from pathlib import Path
import re
import sys
import tarfile
import xml.etree.ElementTree as ET

closure = Path("docs/testing/evidence/L7-C3/closure")
historical = Path(sys.argv[1])
receipt = json.loads((closure / "maven-gate.json").read_bytes())
runs = ["maven", "resume-install", "resume-worker-process", "resume-canonical",
        "replay-joint-incremental", "resume-monolith"]
build = re.compile(r"^\[INFO\] .* @ (pocoma[^ ]*) ---$")
result = re.compile(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+), .* -- in (\S+)")
reactor_row = re.compile(r"^\[INFO\] (pocoma\S*)\s+\.+\s+(SUCCESS|FAILURE|SKIPPED)\b")
run_notes, observations = {}, {}
for run in runs:
    base = historical / run
    log = base.with_suffix(".log")
    command = base.with_suffix(".command")
    exit_code = base.with_suffix(".exit")
    metadata = {"command": command.read_text().strip(), "exit": int(exit_code.read_text()) if exit_code.exists() else None,
                "logSha256": sha256(log.read_bytes()).hexdigest(), "reactor": {}, "markers": []}
    run_notes[run] = metadata
    module = None
    with log.open(errors="replace") as stream:
        for line in stream:
            if match := build.match(line):
                module = match[1]
            if match := reactor_row.match(line):
                metadata["reactor"][match[1]] = match[2]
            if "Reactor Summary" in line or line.startswith(("[INFO] BUILD ", "[INFO] Finished at:")):
                metadata["markers"].append(line.strip())
            if match := result.search(line):
                total, failures, errors, skipped, name = match.groups()
                observations.setdefault((module, name), {})[run] = list(map(int, (total, failures, errors, skipped)))
    metadata["logTests"] = sum(result[run][0] for result in observations.values() if run in result)
    metadata["logSuccessfulTests"] = sum(result[run][0] - sum(result[run][1:]) for result in observations.values() if run in result)

xml_identities, xml_paths, suites = set(), set(), []
with tarfile.open(closure / "maven-gate.tar.gz") as archive:
    for member in archive.getmembers():
        if not member.name.startswith("reports/") or not member.name.endswith(".xml"):
            continue
        raw = archive.extractfile(member).read()
        digest = sha256(raw).hexdigest()
        if digest != receipt["reportHashes"][member.name] or member.name in xml_paths:
            raise ValueError(member.name)
        xml_paths.add(member.name)
        xml = ET.fromstring(raw)
        props = {p.get("name"): p.get("value") for p in xml.findall("properties/property")}
        module, producer = member.name.split("/")[1:3]
        for key, expected in {"module": module, "producer": producer.removesuffix("-reports"),
                              "id": receipt["invocationId"]}.items():
            assert props[f"pocoma.verification.{key}"] == expected, member.name
        signature = [int(xml.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")]
        cases = xml.findall("testcase")
        assert len(cases) == signature[0] and signature[1:] == [0, 0, 0]
        for case in cases:
            identity = (module, case.get("classname"), case.get("name"))
            assert identity not in xml_identities and not any(
                child.tag in {"failure", "error", "skipped"} for child in case), identity
            xml_identities.add(identity)
        key = (module, xml.get("name"))
        results = observations.get(key, {})
        latest = next(reversed(results), None)
        suites.append({"report": member.name, "sha256": digest, "tests": len(cases),
                       "observedInLogs": results,
                       "latestMatchingLog": latest if results.get(latest) == signature else None})
assert xml_paths == set(receipt["reportHashes"])
assert len(xml_identities) == receipt["observedTests"]
reference = Path("docs/testing/evidence/L7-C3/generalization/maven-gate.tar.gz")
reference_identities = set()
with tarfile.open(reference) as archive:
    for member in archive.getmembers():
        if member.name.startswith("reports/") and member.name.endswith(".xml"):
            module = "pocoma-" + member.name.split("/")[1]
            xml = ET.fromstring(archive.extractfile(member).read())
            for case in xml.findall("testcase"):
                identity = (module, case.get("classname"), case.get("name"))
                assert identity not in reference_identities
                reference_identities.add(identity)
print(json.dumps({
    "receiptSha256": sha256((closure / "maven-gate.json").read_bytes()).hexdigest(),
    "reportArchiveSha256": sha256((closure / "maven-gate.tar.gz").read_bytes()).hexdigest(),
    "previousReportInventorySha256": sha256(reference.read_bytes()).hexdigest(),
    "historicalRuns": run_notes,
    "xmlSuites": len(suites), "xmlTestModules": len({item[0] for item in xml_identities}),
    "xmlUniqueTests": len(xml_identities),
    "testsByLatestMatchingLog": dict(Counter({
        run: sum(item["tests"] for item in suites if item["latestMatchingLog"] == run)
        for run in [*runs, None]})),
    "onlyInPreviousSuiteInventory": sorted(reference_identities - xml_identities),
    "onlyInClosureSuiteInventory": sorted(xml_identities - reference_identities),
    "reportAttribution": suites,
}, indent=2))
