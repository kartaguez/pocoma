#!/usr/bin/env python3
"""Recheck existing I1/L2 evidence; never launches Maven, Docker or a campaign.

Run from the repository root. --capture preserves surviving auxiliary I1 files
once; normal mode needs only Git and the archived evidence in this repository.
"""
import argparse
import gzip
import hashlib
import json
import re
import subprocess
import tarfile
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
OUT = Path(__file__).resolve().parent
AUDITED = "02c7bfbf85936f1f1a633e9097bc2b49f878708e"
BASE = "140d13c3101a4e297bdea5141d167d189b7dcd02"
HIST = "a2133b6b43a74999374bc26a222f70c7500fadda"
WORKER = "app/runtime-command-consumption-worker"
XML = "TEST-com.kartaguez.pocoma.runtime.command.v3.V3WorkerResourcesPostgresTest.xml"
W1 = "temporal-v2-W1-B1-MW4-final.txt"
W2 = "temporal-v3-W2-B32-MW4-final.txt"


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def blob(ref, path):
    return git("show", ref + ":" + path)


def load(path):
    return json.loads((ROOT / path).read_text())


def dump(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--capture", action="store_true")
args = parser.parse_args()
if args.capture:
    assert not (OUT / "auxiliary-index.json").exists(), "Capture already exists"
    (OUT / "auxiliary").mkdir()
    entries = []
    for source in [f"{WORKER}/target/surefire-reports/{XML}",
                   f"{WORKER}/target/v3-i5-evidence/{W1}",
                   f"{WORKER}/target/v3-i5-evidence/{W2}"]:
        p = ROOT / source
        raw = p.read_bytes()
        dest = OUT / "auxiliary" / (p.name + ".gz")
        dest.write_bytes(gzip.compress(raw, mtime=0))
        assert gzip.decompress(dest.read_bytes()) == raw
        entries.append(dict(source=source, original_mtime_ns=p.stat().st_mtime_ns,
                            file=str(dest.relative_to(OUT)), raw_sha256=sha(raw),
                            compressed_sha256=sha(dest.read_bytes())))
    dump(OUT / "auxiliary-index.json", dict(
        attribution="Surviving I1 target files captured after audit; not originally sealed in Verification.json. XML cases match original sealed observations and log. Physical traces are auxiliary, not a new execution.",
        files=entries))

aux = load(str((OUT / "auxiliary-index.json").relative_to(ROOT)))
raws = {}
for entry in aux["files"]:
    data = (OUT / entry["file"]).read_bytes()
    raw = gzip.decompress(data)
    assert sha(data) == entry["compressed_sha256"]
    assert sha(raw) == entry["raw_sha256"]
    raws[Path(entry["source"]).name] = raw

original = load("docs/testing/evidence/L3-I1/Verification.json")
checks = []
for run in original["runs"]:
    data = (ROOT / "docs/testing/evidence/L3-I1" / run["compressed_log"]).read_bytes()
    raw = gzip.decompress(data)
    assert sha(data) == run["compressed_sha256"]
    assert sha(raw) == run["log_sha256"]
    text = raw.decode()
    assert "BUILD " + run["build"] in text
    rows = re.findall(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+).*? -- in ([^\r\n]+)", text)
    totals = dict(zip(["tests", "failures", "errors", "skipped"],
                      [sum(int(row[i]) for row in rows) for i in range(4)]))
    assert totals == run["totals"]
    checks.append(dict(name=run["name"], command=run["command"],
                       build=run["build"], totals=totals, archive_sha256=sha(data)))
    if run["name"] == "gates":
        gate_log = text
        assert re.search(r"architecture-tests[^\n]*SKIPPED", text)

cases_path = ROOT / "docs/testing/evidence/L3-I1/testcases.json.gz"
assert sha(cases_path.read_bytes()) == original["testcases"]["sha256"]
cases = json.loads(gzip.decompress(cases_path.read_bytes()))
assert len(cases) == original["testcases"]["count"]
for suite, count in original["required_i1_tests"].items():
    subset = [c for c in cases if c["run"] == "gates" and c["suite"] == suite]
    assert len(subset) == count and all(c["status"] == "PASS" for c in subset)
for path, expected in original["source_sha256"].items():
    assert sha(blob(AUDITED, path)) == expected
    assert (ROOT / path).read_bytes() == blob(AUDITED, path)

changed = git("diff", "--name-status", BASE, AUDITED, "--", "app").decode().splitlines()
assert len(changed) == 13
assert [s for s in changed if s.startswith("M\t")] == ["M\tapp/engine-consume-registration/pom.xml"]
assert all(s.startswith("A\t") or s.startswith("M\t") for s in changed)
assert not git("diff", AUDITED, "--", "app")
unchanged = {}
for path in git("ls-tree", "-r", "--name-only", BASE, "app").decode().splitlines():
    if path != "app/engine-consume-registration/pom.xml":
        before = blob(BASE, path)
        assert before == blob(AUDITED, path), path
        unchanged[path] = sha(before)

current = ET.fromstring(raws[XML])
failure_details = {}
for method in ["i5_r05", "i5_r12"]:
    case = next(c for c in current.findall("testcase") if c.get("name") == method)
    sealed = next(c for c in cases if c["run"] == "gates" and c["name"] == method)
    failure = case.find("failure")
    assert failure is not None
    assert failure.get("message") == sealed["detail"]
    assert case.get("time") == sealed["time"]
    assert failure.get("message") in gate_log
    failure_details[method] = dict(seconds=case.get("time"), message=failure.get("message"),
                                   stack=failure.text)
    stdout = case.findtext("system-out", "")
    trace = raws[W1 if method == "i5_r05" else W2].decode()
    schema = re.search(r"current_schema=([^,]+),", trace).group(1)
    assert "currentSchema=" + schema in stdout
    failure_details[method]["physical_schema"] = schema
    if method == "i5_r12":
        assert "FATAL: terminating connection due to transaction timeout" in stdout

histories = []
for folder, frozen in [("L2-I5-temporal-v2", "5eab2e94379ef40c3f9ff54c14a4d04a8f13dcbd"),
                       ("L2-I5-temporal-final", HIST)]:
    q = load("docs/testing/evidence/" + folder + "/qualification.json")
    archive = ROOT / "docs/testing/evidence" / folder / "qualification-proof.tar.gz"
    expected = q["archive"]["sha256"] if "archive" in q else q["artifacts"][archive.name]["sha256"]
    assert sha(archive.read_bytes()) == expected
    with tarfile.open(archive) as tf:
        xmls = []
        for m in tf.getmembers():
            if m.name.endswith(XML):
                data = tf.extractfile(m).read()
                root = ET.fromstring(data)
                xmls.extend(dict(method=c.get("name"), seconds=c.get("time"),
                                 status="FAIL" if c.find("failure") is not None else "PASS")
                            for c in root.findall("testcase"))
                if folder.endswith("final"):
                    historical_xml = root
                    assert root.find("testcase/failure").get("message") == failure_details["i5_r12"]["message"]
        names = [m.name for m in tf.getmembers()]
    histories.append(dict(folder=folder, tested_sha=frozen, archive_sha256=expected, cases=xmls,
                          verdict=q.get("classification", q.get("verdict")), members=names))

ns = {"m": "http://maven.apache.org/POM/4.0.0"}
for path in original["unchanged_l2_sources"]:
    assert blob(BASE, path) == blob(AUDITED, path) == blob(HIST, path)
    assert sha(blob(AUDITED, path)) == original["unchanged_l2_sources"][path]

semantic_inputs = {}
for path in [
    WORKER + "/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5PhysicalWindow.java",
    WORKER + "/src/test/java/com/kartaguez/pocoma/runtime/command/v3/I5TemporalV2Test.java",
    "app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerSettings.java",
    "docs/testing/evidence/L2-I5-temporal-v2/temporal-contract-v2.properties",
    "docs/testing/evidence/L2-I5-temporal-final/temporal-contract-v3.properties",
]:
    assert blob(BASE, path) == blob(AUDITED, path) == blob(HIST, path)
    semantic_inputs[path] = sha(blob(AUDITED, path))


def classpath(root):
    props = {p.get("name"): p.get("value") for p in root.findall("./properties/property")}
    entries = props["java.class.path"].split(":")
    return props, [e[e.index("/.m2/"):] for e in entries if "/.m2/" in e], [
        re.sub(r"^.*/app/", "", e) for e in entries if "/app/" in e]


props, jars, modules = classpath(current)
oldprops, oldjars, oldmodules = classpath(historical_xml)
assert jars == oldjars and modules == oldmodules
toolchain = {k: [oldprops.get(k), props.get(k)] for k in [
    "java.version", "java.vendor", "os.name", "os.version", "os.arch", "user.language", "file.encoding"]}
assert all(v[0] == v[1] for v in toolchain.values())

w1 = raws[W1].decode()
key = "98f7268d-d340-581c-b83d-010926223e14/1"
availability = next(l for l in w1.splitlines() if l.startswith("AVAILABILITY key=" + key + " "))
start, end = map(int, re.search(r"window=(\d+)\.\.(\d+)", availability).groups())
assert "physicalFree=false" in availability
attempt = next(a for a in re.findall(r"Attempt\[.*?(?=, Attempt\[|\n|$)", w1)
               if f"store={start}..{end}" in a)
lease_pattern = r"PartitionLease\[consumptionId=ConsumptionId\[value=([^\]]+)\], partitionId=(\d+), ownerToken=([^,]+), ownedUntil=([^\]]+)\]"
candidate, returned = [re.findall(lease_pattern, x) for x in attempt.split("returned=")]
assert len(candidate) == len(returned) == 1
assert candidate[0][:3] == returned[0][:3]
assert datetime.fromisoformat(returned[0][3]) > datetime.fromisoformat(candidate[0][3])
tx_pattern = r"Tx\[(WRITE|READ),([^,]+),(\d+),(\d+),guarded=(true|false),renewal=(true|false),injected=(true|false),pid=(\d+),xid=([^,]*),([^,]+),keys=\[([^\]]*)\]\]"
overlaps = [t for t in re.findall(tx_pattern, w1)
            if t[0] == "WRITE" and key in t[10] and int(t[2]) < end and int(t[3]) > start]
assert any(t[1] == "v3-discovery-0" for t in overlaps)
assert re.findall(r"^PG stage=(\d+)", w1, re.M) == ["1", "1"]

w2 = raws[W2].decode()
assert "JOURNAL SEALED all admitted owners resolved and attempts complete" in w2
assert "V3 QUALIFICATION PASS W2-B32-MW4" in w2
assert w2.count("C-EXCESS key=") == 4
assert re.findall(r"^PG stage=(\d+)", w2, re.M) == ["1", "1", "2", "2"]
before = next(l for l in w2.splitlines() if l.startswith("PG stage=1 before=" )).split(" after=")[0]
prepared_by = max(datetime.fromisoformat(t) for t in re.findall(r"now=(2026-[0-9T:.Z-]+)", before))
final_clock = re.findall(r"clock_timestamp=(2026-[0-9 :.-]+),", w2)[-1]
pre_release = datetime.fromisoformat(final_clock).replace(tzinfo=timezone(timedelta(hours=1)))
retention = (pre_release - prepared_by).total_seconds()
assert retention > 65
config = "app/composition-runtime-spring/src/main/java/com/kartaguez/pocoma/runtime/consumption/v3/V3ConsumptionWorkerConfiguration.java"
assert blob(BASE, config) == blob(HIST, config) == blob(AUDITED, config)

result = dict(
    verdict="I1 NOT CLOSED", audited_sha=AUDITED, baseline=BASE,
    new_maven_executions=0, new_temporal_campaigns=0,
    integrity="PASS for listed evidence checks; not an application gate PASS",
    original_runs=checks, required_i1_tests=original["required_i1_tests"],
    failures=failure_details, auxiliary=aux,
    baseline_comparison=dict(changed_files=changed, unchanged_tracked_app_files=len(unchanged),
        unchanged_files_digest=sha(json.dumps(unchanged, sort_keys=True).encode()),
        new_execution_on_baseline="NOT RUN; no formal scheduling equivalence claimed"),
    histories=histories, unchanged_historical_semantic_inputs_sha256=semantic_inputs,
    historical_to_I1_app_delta=git("diff", "--name-status", HIST, AUDITED, "--", "app").decode().splitlines(),
    historical_classpath_comparison=dict(jar_entries=len(jars), module_entries=len(modules),
        ordered_jars_equal=True, ordered_module_paths_equal_after_worktree_normalization=True,
        toolchain=toolchain, limit="Same ordered paths and versions, not a hash proof of JAR or class bytes; process load, scheduling and full-suite order are not equivalent."),
    r05=dict(key=key, stage=1, store_ns=[start, end], attempt=attempt,
             overlapping_transactions=overlaps, candidate=candidate[0], returned=returned[0],
             conclusion="Required independent availability witness missing; same-token extension observed. Envelope overlap is not proof of a row lock at the exact CAS."),
    r12=dict(stages=[1, 2], excessive_witnesses=4, journal_sealed=True,
             prepared_before_or_at=prepared_by.isoformat(), before_release=pre_release.isoformat(),
             preparation_retention_lower_bound_seconds=retention, business_transaction_timeout_ms=65000,
             business_config_sha256=sha(blob(AUDITED, config)),
             conclusion="Fixture retains preparations beyond physical lifetime; transaction-timeout errors and STOPPED deadline failure observed. Exact residual worker responsibility after release not captured."),
    required_gate="COMMAND + global architecture, unfiltered; prior FAIL and architecture SKIPPED unchanged",
    full_reactor="NOT REQUIRED / NOT RUN", database="No new execution or migration",
    unexpected_slice_crossing="NONE", independent_audit="User-supplied CODE REVIEW PASS; no separate complete audit artifact found in inspected local files")
dump(OUT / "Analysis.json", result)
print(json.dumps(dict(verdict=result["verdict"], integrity=result["integrity"],
                      unchanged_baseline_files=len(unchanged), r12_retention_seconds=retention,
                      new_maven_executions=0), ensure_ascii=False))
