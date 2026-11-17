#!/usr/bin/env python3
"""Independently verify delivered post-audit archives, required cases, freshness and code SHA."""
import hashlib
import json
import subprocess
import tarfile
from pathlib import Path
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
manifest = json.loads((ROOT / "docs/v3/L2_I3_Post_Audit_Proof_Manifest.json").read_text())
index = json.loads((HERE / "Gate-index.json").read_text())
for supplementary in index["supplementary_artifacts"]:
    path = HERE / supplementary["path"]
    assert hashlib.sha256(path.read_bytes()).hexdigest() == supplementary["sha256"], path
checks = []
for gate in index["gates"]:
    name = gate["gate"]
    archive = HERE / gate["archive"]
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == gate["sha256"], name
    with tarfile.open(archive, "r:gz") as tar:
        def read(relative):
            return tar.extractfile(name + "/" + relative).read()
        result_bytes = read("result.json")
        assert hashlib.sha256(result_bytes).hexdigest() == gate["result_sha256"], name
        result = json.loads(result_bytes)
        assert result["sha"] == index["tested_code_sha"]
        assert result["command"] == manifest["gate_commands"][name]
        assert result["exit_code"] == 0 and result["verdict"] == "PASS"
        assert not result["problems"] and result["failures_or_errors"] == 0
        assert hashlib.sha256(read("maven.log")).hexdigest() == result["log_sha256"]
        cases = []
        for artifact in result["artifacts"]:
            data = read(artifact["path"])
            assert hashlib.sha256(data).hexdigest() == artifact["sha256"], artifact["path"]
            member = tar.getmember(name + "/" + artifact["path"])
            assert member.mtime >= result["started_epoch"] - 1, artifact["path"]
            if artifact["path"].startswith("xml/"):
                for case in ET.fromstring(data).iter("testcase"):
                    state = "FAIL" if case.find("failure") is not None or case.find("error") is not None else "SKIP" if case.find("skipped") is not None else "PASS"
                    cases.append({"class": case.get("classname"), "method": case.get("name"), "state": state})
        assert cases == result["cases"]
        assert len(cases) == result["tests"] == gate["tests"]
        assert [case for case in cases if case["state"] == "SKIP"] == result["skips"]
        for group in manifest["gate_groups"][name]:
            for classname, methods in manifest["required_groups"][group].items():
                for method in methods:
                    matching = [c for c in cases if c["class"] == classname and c["method"] == method]
                    assert len(matching) == 1 and matching[0]["state"] == "PASS", (name, classname, method)
        requirements = 0
        for requirement in manifest["physical_requirements"]:
            if requirement["group"] not in manifest["gate_groups"][name]:
                continue
            module, suffix = requirement["path"].split("/target/", 1)
            content = read("physical/" + module + "/" + suffix).decode()
            assert all(marker in content for marker in requirement["contains"]), requirement["path"]
            requirements += 1
        checks.append({"gate": name, "sha": result["sha"], "tests": len(cases),
                       "artifacts": len(result["artifacts"]), "physical_requirements": requirements,
                       "required_methods": result["required_methods"], "verdict": "PASS"})
assert {g["gate"] for g in checks} == {"command", "read", "monolith", "architecture"}
subprocess.run(["git", "diff", "--exit-code", index["tested_code_sha"], "HEAD", "--", "app", "scripts"], cwd=ROOT, check=True, capture_output=True)
for file in json.loads((HERE / "Code-freeze.json").read_text())["files"]:
    if file["path"].startswith(("app/", "scripts/")):
        assert hashlib.sha256((ROOT / file["path"]).read_bytes()).hexdigest() == file["sha256"]
output = {"tested_code_sha": index["tested_code_sha"], "code_unchanged_since_gates": True,
          "all_four_unfiltered_gates_verified": True, "checks": checks,
          "limits": "Local bounded corrections only; global L2 and I5 resources remain INCONCLUSIVE"}
(HERE / "Integrity-check.json").write_text(json.dumps(output, indent=2) + "\n")
print(json.dumps(output, indent=2))
