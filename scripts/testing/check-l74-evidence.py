#!/usr/bin/env python3
"""Verify retained L7.4 bytes, metric references, final E1/E3 and source attribution.

Integrity and bounded local evidence only; never a SPEC-09/L7 certificate.
"""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/"scripts/performance"))
import spec09_bench as bench


def check(directory):
    manifest = json.loads((directory/"archive-manifest.json").read_text())
    raw = {}
    for entry in manifest["artifacts"]:
        encoded = (directory/entry["storedPath"]).read_bytes()
        if hashlib.sha256(encoded).hexdigest() != entry["storedSha256"]:
            raise ValueError("stored bytes changed: " + entry["storedPath"])
        data = gzip.decompress(encoded) if entry["encoding"] == "gzip" else encoded
        if len(data) != entry["rawBytes"] or hashlib.sha256(data).hexdigest() != entry["rawSha256"]:
            raise ValueError("original bytes changed: " + entry["originalPath"])
        if entry["originalPath"] in raw:
            raise ValueError("duplicate artifact")
        raw[entry["originalPath"]] = data
    receipts = {name: json.loads(data) for name, data in raw.items()
                if name.endswith("/receipt.json") and name.startswith("e1")}
    if len(receipts) != 6:
        raise ValueError("six original E1/E3 receipts required")
    for path, receipt in receipts.items():
        if receipt["cleanup"] != "REMOVED_OWN_PROJECT" or receipt["contractual"] != "NOT_RUN":
            raise ValueError("cleanup/contract attribution differs")
        for snapshot in receipt["snapshots"]:
            for name, sha in snapshot["metricFiles"].items():
                data = raw[str(Path(path).parent/name)]
                if hashlib.sha256(data).hexdigest() != sha:
                    raise ValueError("metric reference changed")
    final = receipts["e1-e3-06/receipt.json"]
    if final["status"] != "PASS" or final["provenance"]["sha"] != bench.command(["git", "rev-parse", "230feb05"], cwd=ROOT):
        raise ValueError("final tested SHA/status differs")
    if len(final["observations"]) != 12 or final["summary"]["observed"] != 12:
        raise ValueError("final E1 population differs")
    expected = bench.summarize(final["observations"], 12, 12)
    if expected != final["summary"] or not final["reconciliation"]["complete"]:
        raise ValueError("E1 summary/reconciliation differs")
    bench.validate_progress(final["snapshots"][0], final["snapshots"][-1])
    crash = final["crashCampaign"]
    if crash["status"] != "PASS" or len(crash["cases"]) != 2:
        raise ValueError("two actual E3 subjects required")
    for case in crash["cases"]:
        if case["beforeKill"] != dict(status="PENDING", results=0, events=0, completions=0):
            raise ValueError("missing before-kill barrier")
        process = case["process"]
        if (process["exitCode"], process["runningAfterKill"], process["pidAfterKill"]) != (137, False, 0):
            raise ValueError("actual process crash not established")
        if not process["runningAfterRestart"] or process["priorStartedAt"] == process["restartedAt"]:
            raise ValueError("new process generation missing")
    command, identity = crash["cases"]
    if not command["reconciliation"]["complete"] or command["observation"]["status"] != "OBSERVED":
        raise ValueError("Command recovery missing")
    if identity["write"] != dict(user=identity["read"]["user"], terminal=1, result=1, users=1, events=2, completions=1):
        raise ValueError("Identity uniqueness differs")
    if identity["read"]["results"] != 1 or not identity["authorizedEmptyNavigation"]:
        raise ValueError("Identity READ usability missing")
    for path, sha in final["provenance"]["inputs"].items():
        if bench.digest(ROOT/path) != sha:
            raise ValueError("current input differs from final tested source/artifact: " + path)
    # Preserve identical seed descriptors across the fixed-seed trials.
    seeds = [json.loads(raw[name+"/seed.json"])["fingerprintSha256"] for name in ("e1-03", "e1-e3-05", "e1-e3-06")]
    if len(set(seeds)) != 1:
        raise ValueError("fixed seed changed")
    contribution = json.loads(raw["l73-contribution.json"])
    if (contribution["officialPartial"], contribution["closureCandidates"], contribution["automaticPromotions"]) != (15, 1, 0):
        raise ValueError("L7.3 attribution differs")
    contractual = json.loads(raw["contractual-results.json"])
    if len(contractual["campaigns"]) != 13 or any(c["status"] != "NOT_RUN" for c in contractual["campaigns"]):
        raise ValueError("local evidence promoted to contractual PASS")
    return dict(status="PASS", scope="artifact integrity and bounded E1/E3 attribution only",
                artifacts=len(raw), originalReceipts=len(receipts), finalTestedSha=final["provenance"]["sha"],
                finalInputsCompared=len(final["provenance"]["inputs"]), contractual="NOT_RUN", automaticPromotions=0)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, default=ROOT/"docs/testing/evidence/L7-I4-technical")
    args = parser.parse_args()
    try:
        print(json.dumps(check(args.evidence), indent=2))
    except (OSError, ValueError, KeyError, AssertionError) as error:
        print(json.dumps(dict(status="FAIL", error=str(error))))
        sys.exit(2)
