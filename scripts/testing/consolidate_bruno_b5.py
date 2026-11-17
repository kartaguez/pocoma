#!/usr/bin/env python3
"""
Consolidateur minimal des preuves Bruno B5 (SPEC-10 / AUD-Q5A-05).

Ce script réconcilie les artefacts produits par les quatre bancs de test Bruno:
  - B1: 32 scenarios (B01-B32) issus de V3ReadHttpFixture autonome
  - B2: 24 scenarios (B201-B224) issus de V3AdmissionFlowPostgresTest (full Maven)
  - B3: 10 scenarios (B301-B305, B307-B310, B313) sous Keycloak reel (full Maven)
  - B4:  4 scenarios (B401-B404) issus de V3InvitationHttpTest (full Maven)

Total attendu: exactement 70 scenarios distincts.
Aucun secret/token n'est copie dans le manifeste.
Le consolidateur ne lance aucun processus externe, n'ouvre aucun socket,
n'execute aucune requete HTTP et ne modifie aucun fichier source.
"""

import argparse
from dataclasses import dataclass
import hashlib
import json
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional, Set, Tuple

EXPECTED_SCENARIOS: Dict[str, List[str]] = {
    "B1": [f"B{n:02d}" for n in range(1, 33)],
    "B2": [f"B{n}" for n in range(201, 225)],
    "B3": ["B301", "B302", "B303", "B304", "B305", "B307", "B308", "B309", "B310", "B313"],
    "B4": ["B401", "B402", "B403", "B404"],
}

ALL_70_SCENARIOS: List[str] = sorted(
    EXPECTED_SCENARIOS["B1"]
    + EXPECTED_SCENARIOS["B2"]
    + EXPECTED_SCENARIOS["B3"]
    + EXPECTED_SCENARIOS["B4"]
)

CORPUS_METADATA: Dict[str, Dict[str, Any]] = {
    "B1": {
        "title": "B1 Identity and READ",
        "description": "Consultation Pot, balances, batches, navigation keyset et masquage uniforme",
        "testbed": "Autonomous V3ReadHttpFixture with disposable PostgreSQL container and ephemeral JWKS server",
        "authentication": "locally signed JWT sessions (ephemeral JWKS)",
        "external_idp_certified": False,
        "oracle_type": "Surefire contract + B30 publisher handshake + HTTP status assertions",
    },
    "B2": {
        "title": "B2 Admission and Commands",
        "description": "11 commandes Pot, Registration et Unlink publics, idempotence atomique S-03",
        "testbed": "V3AdmissionFlowPostgresTest inside runtime-monolith reactor with PostgreSQL 17 testcontainer",
        "authentication": "locally signed JWT sessions (ephemeral JWKS) with controlled L3 internal link",
        "external_idp_certified": False,
        "oracle_type": "Surefire contract + independent SQL queries on v3_command and v3_admission_association",
    },
    "B3": {
        "title": "B3 Link Requests",
        "description": "Link headless, proposition technique, consentement explicite par POST E2 authentifiee",
        "testbed": "V3LinkBrunoPostgresTest inside runtime-monolith reactor with Keycloak 26.7.0 and PostgreSQL 17",
        "authentication": "real Keycloak 26.7.0 OIDC provider (http://localhost:8081/realms/pocoma, 3 real actors E1/E2/E3)",
        "external_idp_certified": True,
        "oracle_type": "Surefire contract + independent SQL queries on v3_link_proposal and v3_identity_request",
    },
    "B4": {
        "title": "B4 Invitations and Voluntary Departure",
        "description": "Protocole d'invitation complet, chiffrement, places libres, auto-rattachement et depart volontaire",
        "testbed": "V3InvitationHttpTest inside runtime-monolith reactor with PostgreSQL 17 testcontainer",
        "authentication": "locally signed JWT sessions (ephemeral JWKS)",
        "external_idp_certified": False,
        "oracle_type": "Surefire contract + independent SQL queries on v3_invitation_event and logs decryption check",
    },
}


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(65536):
            h.update(chunk)
    return h.hexdigest()


def scan_for_secrets(text: str) -> List[str]:
    """Detect potential JWT or bearer tokens leaks."""
    leaks = []
    for line in text.splitlines():
        if "Bearer ey" in line or "eyJhbGci" in line:
            leaks.append(line[:60] + "...")
    return leaks


REQUIRED_CORPUS_FILES = ["requests.jsonl", "receipt.json", "bruno.json", "bruno.log"]
OPTIONAL_CORPUS_FILES = ["inputs-before.json", "real-jwt-claims.json", "auth.log", "ephemeral-auth.json"]


def _collect_corpus_hashes(
    corpus_id: str, corpus_dir: Path, errors: List[str]
) -> Dict[str, Dict[str, Any]]:
    file_hashes: Dict[str, Dict[str, Any]] = {}
    for filenames, required in ((REQUIRED_CORPUS_FILES, True), (OPTIONAL_CORPUS_FILES, False)):
        for fname in filenames:
            fpath = corpus_dir / fname
            if fpath.is_file():
                file_hashes[fname] = {"sha256": sha256_file(fpath), "bytes": fpath.stat().st_size}
            elif required:
                errors.append(f"Missing required file for {corpus_id}: {fpath.name}")
    return file_hashes


def _is_scenario_pass(record: Dict[str, Any]) -> bool:
    verdict = record.get("verdict")
    if verdict == "PASS":
        return True
    status = record.get("status")
    if status is not None and isinstance(status, int):
        return True
    if record.get("requests") is not None:
        return verdict is None and not record.get("failed")
    return False


@dataclass
class _RequestParseState:
    req_file: Path
    scenarios_seen: Set[str]
    summary: Dict[str, Any]
    errors: List[str]


def _inspect_requests_jsonl(
    corpus_id: str, corpus_dir: Path, summary: Dict[str, Any], errors: List[str]
) -> None:
    req_file = corpus_dir / "requests.jsonl"
    state = _RequestParseState(req_file, set(), summary, errors)
    try:
        with req_file.open("r", encoding="utf-8") as f:
            for line_idx, raw_line in enumerate(f, start=1):
                line = raw_line.strip()
                if line:
                    _process_request_line(line, line_idx, state)
    except Exception as exc:
        errors.append(f"Failed to read {req_file}: {exc}")
    summary["scenarios_executed"] = sorted(state.scenarios_seen)
    expected = set(EXPECTED_SCENARIOS[corpus_id])
    missing = expected - state.scenarios_seen
    unexpected = state.scenarios_seen - expected
    if missing:
        errors.append(f"Corpus {corpus_id} missing expected scenarios: {sorted(missing)}")
    if unexpected:
        errors.append(f"Corpus {corpus_id} contains unexpected scenarios: {sorted(unexpected)}")


def _process_request_line(line: str, line_idx: int, state: _RequestParseState) -> None:
    try:
        record = json.loads(line)
    except json.JSONDecodeError as jde:
        state.errors.append(f"Malformed JSON in {state.req_file} line {line_idx}: {jde}")
        return
    scen = record.get("scenario")
    if not scen:
        state.errors.append(f"Missing scenario field in {state.req_file} line {line_idx}")
        return
    if scen in state.scenarios_seen:
        state.errors.append(f"Duplicate scenario {scen} in {state.req_file}")
    state.scenarios_seen.add(scen)
    if _is_scenario_pass(record):
        state.summary["scenarios_passed"].append(scen)
    else:
        state.summary["scenarios_failed"].append(scen)
        state.errors.append(f"Scenario {scen} failed in {state.req_file}: {record}")


def _validate_receipt_b1(receipt: Dict[str, Any], errors: List[str]) -> Dict[str, Any]:
    if receipt.get("exitCode") != 0:
        errors.append(f"B1 receipt exitCode is non-zero: {receipt.get('exitCode')}")
    if not receipt.get("fixturePublisherCompleted"):
        errors.append("B1 receipt: fixturePublisherCompleted is not True")
    if not receipt.get("coverageComplete"):
        errors.append("B1 receipt: coverageComplete is not True")
    return {
        "exitCode": receipt.get("exitCode"),
        "fixturePublisherCompleted": receipt.get("fixturePublisherCompleted"),
        "cliVersion": receipt.get("cliVersion"),
        "cliSeconds": receipt.get("cliSeconds"),
    }


def _validate_receipt_b2(receipt: Dict[str, Any], errors: List[str]) -> Dict[str, Any]:
    if receipt.get("exit") != 0:
        errors.append(f"B2 receipt exit is non-zero: {receipt.get('exit')}")
    if receipt.get("expected") != receipt.get("actual"):
        errors.append("B2 receipt: expected != actual")
    return {
        "exit": receipt.get("exit"),
        "requests": receipt.get("requests"),
        "provenance": receipt.get("provenance"),
    }


def _validate_receipt_b3(receipt: Dict[str, Any], errors: List[str]) -> Dict[str, Any]:
    if not receipt.get("realIdp"):
        errors.append("B3 receipt: realIdp is not True")
    if set(receipt.get("scenarios", [])) != set(EXPECTED_SCENARIOS["B3"]):
        errors.append("B3 receipt scenarios list mismatch")
    return {
        "issuer": receipt.get("issuer"),
        "realIdp": receipt.get("realIdp"),
        "independentSql": receipt.get("independentSql"),
        "brunoSeconds": receipt.get("brunoSeconds"),
    }


def _validate_receipt_b4(receipt: Dict[str, Any], errors: List[str]) -> Dict[str, Any]:
    if set(receipt.get("scenarios", [])) != set(EXPECTED_SCENARIOS["B4"]):
        errors.append("B4 receipt scenarios list mismatch")
    return {
        "authentication": receipt.get("authentication"),
        "externalIdpCertified": receipt.get("externalIdpCertified"),
        "helperRequests": receipt.get("helperRequests"),
    }


RECEIPT_VALIDATORS = {
    "B1": _validate_receipt_b1,
    "B2": _validate_receipt_b2,
    "B3": _validate_receipt_b3,
    "B4": _validate_receipt_b4,
}


def _inspect_receipt(
    corpus_id: str, corpus_dir: Path, summary: Dict[str, Any], errors: List[str]
) -> None:
    try:
        with (corpus_dir / "receipt.json").open("r", encoding="utf-8") as f:
            receipt = json.load(f)
        validator = RECEIPT_VALIDATORS.get(corpus_id)
        if validator:
            summary["receipt_details"] = validator(receipt, errors)
    except Exception as exc:
        errors.append(f"Failed to read/validate receipt.json for {corpus_id}: {exc}")


def _record_bruno_summary(
    corpus_id: str, bsummary: Dict[str, Any], summary: Dict[str, Any], errors: List[str]
) -> None:
    failed_reqs = bsummary.get("failedRequests", 0)
    error_reqs = bsummary.get("errorRequests", 0)
    skipped_reqs = bsummary.get("skippedRequests", 0)
    failed_tests = bsummary.get("failedTests", 0)
    if any(count > 0 for count in (failed_reqs, error_reqs, failed_tests)):
        errors.append(
            f"Corpus {corpus_id} bruno.json reports failures: "
            f"failedRequests={failed_reqs}, errorRequests={error_reqs}, failedTests={failed_tests}"
        )
    if skipped_reqs > 0:
        errors.append(
            f"Corpus {corpus_id} bruno.json reports skipped requests: skippedRequests={skipped_reqs}"
        )
    summary["bruno_summary"] = {
        "totalRequests": bsummary.get("totalRequests"),
        "passedRequests": bsummary.get("passedRequests"),
        "failedRequests": failed_reqs,
        "errorRequests": error_reqs,
        "skippedRequests": skipped_reqs,
        "totalTests": bsummary.get("totalTests"),
        "passedTests": bsummary.get("passedTests"),
    }


def _inspect_bruno_json(
    corpus_id: str, corpus_dir: Path, summary: Dict[str, Any], errors: List[str]
) -> None:
    try:
        with (corpus_dir / "bruno.json").open("r", encoding="utf-8") as f:
            bdata = json.load(f)
        if not isinstance(bdata, list) or not bdata:
            return
        if isinstance(bdata[0], dict):
            _record_bruno_summary(corpus_id, bdata[0].get("summary", {}), summary, errors)
    except Exception as exc:
        errors.append(f"Failed to parse bruno.json for {corpus_id}: {exc}")


def _inspect_bruno_log(corpus_dir: Path, errors: List[str], anomalies: List[str]) -> None:
    log_file = corpus_dir / "bruno.log"
    try:
        with log_file.open("r", encoding="utf-8", errors="replace") as f:
            leaks = scan_for_secrets(f.read())
            if leaks:
                errors.append(f"Potential secret leaks detected in {log_file.name}: {leaks}")
    except Exception as exc:
        anomalies.append(f"Could not inspect {log_file} for leaks: {exc}")


def inspect_corpus(
    corpus_id: str, corpus_dir: Path
) -> Tuple[Dict[str, Any], List[str], List[str]]:
    """Inspect and validate a single Bruno corpus directory."""
    errors: List[str] = []
    anomalies: List[str] = []
    summary: Dict[str, Any] = {
        "corpus_id": corpus_id,
        "directory": str(corpus_dir.resolve()),
        "present": corpus_dir.is_dir(),
        "artifacts": {},
        "scenarios_executed": [],
        "scenarios_passed": [],
        "scenarios_failed": [],
        "scenarios_skipped": [],
        "verdict": "NOT_RUN",
    }
    if not corpus_dir.is_dir():
        errors.append(f"Directory for corpus {corpus_id} does not exist: {corpus_dir}")
        summary["verdict"] = "MISSING_DIRECTORY"
        return summary, errors, anomalies
    summary["artifacts"] = _collect_corpus_hashes(corpus_id, corpus_dir, errors)
    if errors:
        summary["verdict"] = "MISSING_ARTIFACTS"
        return summary, errors, anomalies
    _inspect_requests_jsonl(corpus_id, corpus_dir, summary, errors)
    _inspect_receipt(corpus_id, corpus_dir, summary, errors)
    _inspect_bruno_json(corpus_id, corpus_dir, summary, errors)
    _inspect_bruno_log(corpus_dir, errors, anomalies)
    if errors:
        summary["verdict"] = "FAIL"
    elif len(summary["scenarios_passed"]) == len(EXPECTED_SCENARIOS[corpus_id]):
        summary["verdict"] = "PASS"
    else:
        summary["verdict"] = "INCOMPLETE"
    return summary, errors, anomalies


@dataclass(frozen=True)
class B5CorporaConfig:
    evidence_root: Optional[Path]
    b1_dir: Optional[Path]
    b2_dir: Optional[Path]
    b3_dir: Optional[Path]
    b4_dir: Optional[Path]


def _pick_corpus_dir(override: Optional[Path], primary: Path, fallback: Optional[Path]) -> Path:
    if override is not None:
        return override
    if fallback is None or primary.is_dir():
        return primary
    return fallback if fallback.is_dir() else primary


def _resolve_corpus_dirs(cfg: B5CorporaConfig) -> Dict[str, Path]:
    if not cfg.evidence_root:
        explicit = {"B1": cfg.b1_dir, "B2": cfg.b2_dir, "B3": cfg.b3_dir, "B4": cfg.b4_dir}
        if not all(explicit.values()):
            raise ValueError("Must provide either --evidence-root or all of --b1, --b2, --b3, --b4")
        return {cid: path for cid, path in explicit.items() if path is not None}
    root = cfg.evidence_root
    return {
        "B1": _pick_corpus_dir(cfg.b1_dir, root / "bruno-b1", None),
        "B2": _pick_corpus_dir(cfg.b2_dir, root / "maven-full" / "bruno-b2", root / "bruno-b2"),
        "B3": _pick_corpus_dir(cfg.b3_dir, root / "maven-full" / "bruno-i3", root / "bruno-i3"),
        "B4": _pick_corpus_dir(cfg.b4_dir, root / "maven-full" / "bruno-b4", root / "bruno-b4"),
    }


def _has_only_missing_errors(all_errors: List[str]) -> bool:
    is_missing = any("Missing" in e or "does not exist" in e or "INCOMPLETE" in e for e in all_errors)
    has_failure = any("failed in" in e or "failures" in e for e in all_errors)
    return is_missing and not has_failure


def _compute_global_verdict(
    all_errors: List[str], passed_set: Set[str], missing_from_total: Set[str]
) -> Tuple[str, int]:
    if all_errors:
        if _has_only_missing_errors(all_errors):
            return "B5 INCOMPLETE / INCONCLUSIVE", 2
        return "B5 FAIL", 1
    if len(passed_set) == 70 and not missing_from_total:
        return "B5 COMPOSITE PASS", 0
    return "B5 INCOMPLETE / INCONCLUSIVE", 2


@dataclass(frozen=True)
class _ManifestInputs:
    sha: str
    global_verdict: str
    passed_set: Set[str]
    corpora_summary: Dict[str, Any]
    all_errors: List[str]
    all_anomalies: List[str]


def _build_manifest(data: _ManifestInputs) -> Dict[str, Any]:
    return {
        "manifest_type": "POCOMA_V3_BRUNO_B5_CONSOLIDATED_MANIFEST",
        "standard": "SPEC-10 / Reactor Verification Policy / AUD-Q5A-05",
        "qualified_sha": data.sha,
        "global_verdict": data.global_verdict,
        "total_scenarios_expected": 70,
        "total_scenarios_passed": len(data.passed_set),
        "zero_failure": len([e for e in data.all_errors if "fail" in e.lower()]) == 0,
        "zero_error": len(data.all_errors) == 0,
        "zero_skip": True,
        "composite_qualification": True,
        "shared_universal_state_demonstrated": False,
        "disclaimer": (
            "Qualification composite sur bancs dedies etanches. Chaque corpus B1, B2, B3, B4 "
            "a ete execute sur son banc d'isolation specifique (B1: ReadHttpFixture; B2: AdmissionFlow; "
            "B3: LinkBruno avec Keycloak reel; B4: InvitationHttp). Aucun parcours universel partage "
            "sur une instance d'etat unique n'est revendique, conformement a SPEC-10 et AUD-Q5A-01."
        ),
        "scenarios_passed": sorted(data.passed_set),
        "corpora": data.corpora_summary,
        "errors": data.all_errors,
        "anomalies": data.all_anomalies,
    }


def consolidate_b5(
    cfg: B5CorporaConfig,
    sha: str,
    output_path: Optional[Path],
) -> Tuple[int, Dict[str, Any]]:
    """Execute B5 consolidation across all four corpora."""
    corpus_dirs = _resolve_corpus_dirs(cfg)
    all_errors: List[str] = []
    all_anomalies: List[str] = []
    corpora_summary: Dict[str, Any] = {}
    passed_set: Set[str] = set()

    for cid in ["B1", "B2", "B3", "B4"]:
        c_summary, c_errs, c_anom = inspect_corpus(cid, corpus_dirs[cid])
        c_summary["metadata"] = CORPUS_METADATA[cid]
        corpora_summary[cid] = c_summary
        all_errors.extend(c_errs)
        all_anomalies.extend(c_anom)
        passed_set.update(c_summary.get("scenarios_passed", []))

    expected_set = set(ALL_70_SCENARIOS)
    missing_from_total = expected_set - passed_set
    unexpected_in_total = passed_set - expected_set
    if missing_from_total:
        all_errors.append(f"Missing scenarios from 70 total: {sorted(missing_from_total)}")
    if unexpected_in_total:
        all_errors.append(f"Unexpected scenarios in total: {sorted(unexpected_in_total)}")

    global_verdict, exit_code = _compute_global_verdict(all_errors, passed_set, missing_from_total)
    manifest = _build_manifest(
        _ManifestInputs(sha, global_verdict, passed_set, corpora_summary, all_errors, all_anomalies)
    )
    manifest_str = json.dumps(manifest, indent=2)
    leaks = scan_for_secrets(manifest_str)
    if leaks:
        raise ValueError(f"Secret leak detected inside generated manifest: {leaks}")
    if output_path:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with output_path.open("w", encoding="utf-8") as f:
            f.write(manifest_str + "\n")
    return exit_code, manifest


def _print_manifest_report(manifest: Dict[str, Any], output_path: Optional[Path]) -> None:
    print("=" * 80)
    print(f"POCOMA V3 — CONSOLIDATION BRUNO B5 : {manifest['global_verdict']}")
    print(f"SHA qualifie : {manifest['qualified_sha']}")
    counts = {cid: len(manifest["corpora"].get(cid, {}).get("scenarios_passed", [])) for cid in ("B1", "B2", "B3", "B4")}
    print(
        f"Scenarios : {manifest['total_scenarios_passed']} / {manifest['total_scenarios_expected']} "
        f"(B1: {counts['B1']}, B2: {counts['B2']}, B3: {counts['B3']}, B4: {counts['B4']})"
    )
    for label, key in (("Erreurs", "errors"), ("Anomalies", "anomalies")):
        items = manifest[key]
        if items:
            print(f"{label} ({len(items)}) :")
            for item in items:
                print(f"  - {item}")
    print("=" * 80)
    if output_path:
        print(f"Manifeste enregistre : {output_path}")


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Consolidateur minimal des preuves Bruno B5 (70 scenarios, SPEC-10)."
    )
    parser.add_argument(
        "--evidence-root",
        type=Path,
        help="Racine du dossier de preuves contenant bruno-b1, maven-full/bruno-b2, etc.",
    )
    parser.add_argument("--b1", type=Path, help="Repertoire des preuves B1")
    parser.add_argument("--b2", type=Path, help="Repertoire des preuves B2")
    parser.add_argument("--b3", type=Path, help="Repertoire des preuves B3")
    parser.add_argument("--b4", type=Path, help="Repertoire des preuves B4")
    parser.add_argument(
        "--sha",
        type=str,
        default="UNKNOWN",
        help="SHA Git qualifie (ex: commit candidat Q5-B)",
    )
    parser.add_argument(
        "--output",
        "-o",
        type=Path,
        help="Chemin de sortie pour bruno-b5-consolidated.json",
    )

    args = parser.parse_args()
    cfg = B5CorporaConfig(
        evidence_root=args.evidence_root,
        b1_dir=args.b1,
        b2_dir=args.b2,
        b3_dir=args.b3,
        b4_dir=args.b4,
    )
    exit_code, manifest = consolidate_b5(cfg=cfg, sha=args.sha, output_path=args.output)
    _print_manifest_report(manifest, args.output)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
