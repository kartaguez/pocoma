#!/usr/bin/env python3
"""Static, application-independent coverage check for the L2 documentary manifest."""
from pathlib import Path
import re
import sys
import unicodedata
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parent
manifest = (ROOT / "L2_Certification_Coverage_Manifest.md").read_text()
spec = (ROOT / "SPEC-10_Test_Certification.md").read_text()
consolidation = (ROOT / "L2_R1_R10_Normative_Consolidation.md").read_text()
section = manifest.split("## Allocation scénario par scénario", 1)[1].split("## Contrôle inverse", 1)[0]
records = []
for line in section.splitlines():
    if line.startswith("| L2M") or line.startswith("| C0") or line.startswith("| E0") or line.startswith("| CON-05") or line.startswith("| R") or line.startswith("| L1-"):
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if len(cells) != 14:
            raise SystemExit(f"bad column count {len(cells)}: {line[:100]}")
        records.append(cells)
if not records:
    raise SystemExit("manifest empty")
errors = []
expected = set(re.findall(r"^\| ((?:C0[1-6]|E0[1-3])-[A-P]) \|", spec, re.M))
expected.update(re.findall(r"^\*\*(R\d+-\d+)\*\*", consolidation, re.M))
expected.update(re.findall(r"^\| \*\*(C06-[N-P])\*\* \|", spec, re.M))
expected.update(("CON-05", "L1-C", "L1-F", "L1-F2"))
contract = (ROOT / "L1_C_Transaction_Contract.md").read_text()
expected.update("L1-C:" + x for x in re.findall(r"^\| (c\d{2}) \|", contract, re.M))
expected.update("L1-F:f0" + str(i) for i in range(1, 6))
expected.update("L1-F2:f20" + str(i) for i in range(1, 8))
matrix = (ROOT / "L1_F2_Invariant_Matrix.md").read_text()
f2_section = matrix.split("| Invariant | Domaine pur |", 1)[1].split("## Portée de détection", 1)[0]
for line in f2_section.splitlines():
    if not line.startswith("| "): continue
    cells = [x.strip() for x in line.strip("|").split("|")]
    if len(cells) != 5 or cells[0].startswith("---"): continue
    slug = re.sub("[^a-z0-9]+", "-", unicodedata.normalize("NFKD", cells[0].lower()).encode("ascii", "ignore").decode()).strip("-")
    expected.add("L1-F2:" + slug)
actual = {r[0] for r in records}
for norm in sorted(expected - actual):
    errors.append("missing normative identifier " + norm)
for norm in sorted(actual - expected):
    errors.append("unknown normative identifier " + norm)
ids = [r[1] for r in records]
methods = [r[8] for r in records]
if len(methods) != len(set(methods)):
    errors.append("duplicate planned method")
for ident in sorted({x for x in ids if ids.count(x) > 1}):
    errors.append("duplicate proof identifier " + ident)
required_subcases = {
    "C01-D": {"COMMIT", "ROLLBACK"},
    "C01-I": {"COMMAND-NOSLOT", "COMMAND-EXISTING", "COMMAND-TERMINAL", "IDENTITY-FIXTURE"},
    "C03-J": {"EXPIRE", "TAKEOVER", "CRASH", "UNKNOWN"},
    "C04-I": {"STATUS", "RESULT"},
    "C06-I": {"EFFECT", "DELETE"},
    "C06-C": {"POTVERSION", "BALANCE", "VISIBILITY"},
    "C06-D": {"BALANCE", "VISIBILITY"},
    "E01-E": {"COMMAND-NOOP", "COMMAND-REJECT", "IDENTITY-REJECT"},
    "E02-A": {"CREATE", "UPDATE", "MULTITARGET"},
    "E02-E": {"LINK", "UNLINK", "REJECT"},
    "E02-G": {"COMMIT-ACK", "ROLLBACK-ACK"},
    "E03-J": {"V-THEN-V1", "V1-THEN-V"},
    "E02-F": {"01", "02", "03", "04", "05", "06", "IDENTITY-REAL", "INVITATION-REAL"},
    "E02-I": {"ABS", "UNKNOWN", "WRONG", "IDENTITY-REAL", "INVITATION-REAL"},
    "C06-O": {"COMMAND", "IDENTITY", "COMMAND-REAL", "IDENTITY-REAL"},
    "E01-G": {"POT-COMP", "INV-COMP", "ID-IDCOMP", "INV-COMP-REAL", "ID-IDCOMP-REAL"},
    "CON-05": {"NMINUS1", "NTH", "CRASH", "OLDTOKEN", "PERSIST", "SOFTCAP", "DISCOVERY", "NOPURGE", "REACT", "TWOADMIN", "NOTQ", "REACTCAP", "REACTOUT", "OTHER", "VV1", "VV1-REAL", "HTTP", "CONFIG"},
    "R1-05": {"MISSING", "DOUBLE", "LATE", "WRONG"},
    "C05-H": {"COMMIT-ACK", "ROLLBACK-ACK"},
    "R4-10": {"SKIPPED"},
    "R10-04": {"SKIPPED"},
    "R10-06": {"SATURATION"},
    "R10-08": {"S1S2"},
    "R10-10": {"MW1"},
    "R9-03": {"COMPLETED", "NOTCOMPLETED", "INCONSISTENT", "UNAVAILABLE"},
}
for norm, suffixes in required_subcases.items():
    found = {r[1].split("L2M-" + norm + "-", 1)[1] for r in records if r[0] == norm and r[1].startswith("L2M-" + norm + "-")}
    for suffix in sorted(suffixes - found):
        errors.append("missing subscenario " + norm + "/" + suffix)
# Mechanical checks only. Semantic fit is recorded by human contradictory review.
catalog_path = ROOT / "L2_Independent_Test_Oracles.md"
catalog = catalog_path.read_text() if catalog_path.exists() else ""
if not catalog:
    errors.append("missing oracle catalog")
sections = re.findall(r"^### (OR-[A-Z0-9]+-[A-Z0-9]+)\s*$([\s\S]*?)(?=^### OR-|^## |\Z)", catalog, re.M)
catalog_ids = [ident for ident, _ in sections]
for ident in sorted({x for x in catalog_ids if catalog_ids.count(x) > 1}):
    errors.append("duplicate catalog oracle " + ident)
required_fields = ("Invariant", "Préparation", "Stimulus", "Source indépendante",
                   "Observations", "Assertions", "FAIL", "INCONCLUSIVE", "Provenance")
for ident, body in sections:
    for field in required_fields:
        if not re.search(r"\*\*" + re.escape(field) + r"\s*:\*\*\s*\S", body, re.M):
            errors.append("missing oracle field " + ident + "/" + field)
oracle_refs = []
for r in records:
    refs = re.findall(r"OR-[A-Z0-9]+-[A-Z0-9]+", r[4])
    if not refs:
        errors.append("proof row without oracle " + r[1])
    if len(refs) != len(set(refs)):
        errors.append("duplicate oracle reference in " + r[1])
    if not r[4].endswith("paramètres et injection : colonne Stimulus") or "cas " + r[1].removeprefix("L2M-") + " ;" not in r[4]:
        errors.append("scenario binding absent " + r[1])
    if r[1].endswith("-REPORT") and "rejouer " not in r[4]:
        errors.append("reported scenario replay source absent " + r[1])
    for ref in refs:
        if ref not in catalog_ids:
            errors.append("undefined oracle " + r[1] + "/" + ref)
    oracle_refs.extend(refs)
for ref in sorted(set(catalog_ids) - set(oracle_refs)):
    errors.append("orphan catalog oracle " + ref)
if len(expected) != 253 or len(records) != 380:
    errors.append("POSTAUD-002 baseline 253 identifiers/380 rows changed")
subscenario_count = sum(r[0] in required_subcases and r[1] != "L2M-" + r[0]
                        and not r[1].endswith("-REPORT") for r in records)
if subscenario_count != 87:
    errors.append("POSTAUD-002 baseline 87 subscenario rows changed")
if sum(r[7] == "obligatoire L2" for r in records) != 324 or sum(r[7] == "reporté" for r in records) != 56:
    errors.append("POSTAUD-002 allocation baseline changed")
critical_refs = {
    "L2M-R1-05-MISSING": {"OR-AT-POISON"},
    "L2M-R2-04": {"OR-NB-CAP", "OR-NB-SET"},
    "L2M-R3-01": {"OR-AR-ROUTE"},
    "L2M-R4-10-SKIPPED": {"OR-B-KEYSET"},
    "L2M-R9-03-NOTCOMPLETED": {"OR-RC-FOUR"},
    "L2M-R10-08-S1S2": {"OR-EQ-RENEW", "OR-CL-RENEW"},
    "L2M-R10-10-MW1": {"OR-EQ-CAP"},
    "L2M-CON-05-NMINUS1": {"OR-RT-SUCCESS", "OR-AT-UNIT"},
    "L2M-CON-05-NTH": {"OR-RT-CEDE", "OR-RT-QUAR"},
    "L2M-CON-05-CONFIG": {"OR-RT-CONFIG"},
    "L2M-CON-05-HTTP": {"OR-READ-HTTP"},
    "L2M-C06-I-EFFECT": {"OR-AT-POISON", "OR-AR-BOUND"},
    "L2M-C06-I-DELETE": {"OR-AT-POISON", "OR-AR-BOUND"},
    "L2M-E02-F-02": {"OR-AT-UNIT", "OR-AT-POISON"},
    "L2M-E02-F-04": {"OR-AT-UNIT", "OR-AT-POISON"},
}
by_id = {r[1]: r for r in records}
for proof_id, required in critical_refs.items():
    if proof_id not in by_id or not required.issubset(set(re.findall(r"OR-[A-Z0-9]+-[A-Z0-9]+", by_id[proof_id][4]))):
        errors.append("critical oracle binding missing " + proof_id)
for r in records:
    norm, ident, prop, stimulus, oracle, authority, subject, allocation, method, campaign, gate, report, status, evidence = r
    if not (prop and stimulus and oracle and authority and subject):
        errors.append("empty property/stimulus/oracle/authority/subject " + ident)
    if allocation not in ("obligatoire L2", "reporté"):
        errors.append("invalid allocation " + ident)
    if allocation == "reporté" and not re.match(r"^L[3-8](?:[/–-]L?[3-8])*\s*:", report):
        errors.append("report without receiving lot/justification " + ident)
    if allocation == "obligatoire L2" and report != "—":
        errors.append("L2 row has unexplained report " + ident)
    if not re.fullmatch(r"P[1-8]", campaign) or gate not in ("G1", "G2", "G3"):
        errors.append("missing campaign/gate " + ident)
    if not method.startswith("prévisionnelle : L2ManifestProof."):
        errors.append("method not marked planned " + ident)
    if status != "non implémenté / non exécuté / à qualifier" or not evidence.startswith("à produire"):
        errors.append("fabricated status/evidence " + ident)
    if norm.startswith("L1-") and allocation != "obligatoire L2" and norm != "L1-F:f04":
        errors.append("L1 guarantee deferred " + ident)
# The gate graph is checked against the producer/consumer table in the L2 plan.
plan = (ROOT / "L2_Design_and_Verification_Plan.md").read_text()
dependency = plan.split("### Dépendances des obligations et des gates", 1)[1].split("### Provenance future", 1)[0]
order = ["P0", "G0", "I1", "I2", "I3", "I4", "I5", "G1", "G2", "I6", "I7", "G3"]
index = {x: i for i, x in enumerate(order)}
if len(index) != len(order):
    errors.append("duplicate stage/gate in topological order")
for line in dependency.splitlines():
    if not line.startswith("| ") or line.startswith("| Obligation") or line.startswith("|---"): continue
    cells = [x.strip() for x in line.strip("|").split("|")]
    if len(cells) != 5:
        errors.append("bad dependency table row")
        continue
    producers = re.findall(r"\b(?:P0|I[1-7])\b", cells[2])
    consumers = re.findall(r"\bG[0-3]\b", cells[3])
    for producer in producers:
        for consumer in consumers:
            if index[producer] >= index[consumer]:
                errors.append("dependency cycle: " + producer + " -> " + consumer)
    if "G0" in consumers and any(p != "P0" for p in producers):
        errors.append("future proof required by G0")
# Cross-document status and supersession assertions.
spec07 = (ROOT / "SPEC-07_Transverse_Requirements.md").read_text()
spec09 = (ROOT / "SPEC-09_Performance.md").read_text()
master = (ROOT / "V3_Implementation_Master_Plan.md").read_text()
if "PERF-01 |" not in spec07 or "Objectifs acquis conceptuellement / mesures et qualification V3 attendues ; aucun PASS" not in spec07:
    errors.append("PERF-01 status not aligned in SPEC-07")
if "objectifs conceptuellement validés, non mesurés en V3" not in spec09:
    errors.append("PERF-01 status not aligned in SPEC-09")
if "ACQUIS conceptuellement / À PROUVER en V3-7" not in spec:
    errors.append("PERF-01 status not aligned in SPEC-10")
if "Aucun PASS de performance V3 n’est acquis" not in master:
    errors.append("PERF-01 status not aligned in master plan")
if "I0 |" in plan or "trois tables L2" in plan or "permission technique étroite du guard fermé" in plan or "Recommandation R4" in plan or "faire ratifier cette classification" in plan or "si retenue" in plan:
    errors.append("superseded active wording in L2 plan")
if "transaction manager PRIMARY" in spec or "G0 FRANCHI PAR DÉCISION HUMAINE" not in plan:
    errors.append("CON-06 manager or G0 status inconsistent")
if "Après fermeture, aucun writer métier ni suppression de slot n'est autorisé" not in plan:
    errors.append("R1 closed-publication rule missing")
if "aucune purge de slot incomplet n'est autorisée" not in spec:
    errors.append("CON-05 no-purge rule missing")
# Relative Markdown links and GitHub-style heading anchors across the V3 corpus.
def anchors(path):
    found, seen = set(), {}
    for line in path.read_text().splitlines():
        match = re.match(r"^#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not match: continue
        heading = re.sub(r"\[([^]]+)\]\([^)]+\)", r"\1", match.group(1))
        heading = re.sub(r"<[^>]+>", "", heading)
        heading = re.sub(r"[\x60*_~]", "", heading).lower().strip()
        heading = "".join(ch for ch in heading if ch in " -_" or unicodedata.category(ch)[0] in "LN")
        heading = re.sub(r"\s", "-", heading)
        n = seen.get(heading, 0)
        seen[heading] = n + 1
        found.add(heading if n == 0 else heading + "-" + str(n))
    return found

local_links = anchors_checked = 0
for doc in ROOT.glob("*.md"):
    body = doc.read_text()
    targets = re.findall(r"\]\(([^)]+)\)", body) + re.findall(r"^\[[^]]+\]:\s*(\S+)", body, re.M)
    for target in targets:
        target = target.strip().split(" ", 1)[0].strip("<>")
        if re.match(r"^(?:https?://|mailto:|data:)", target): continue
        raw_path, sep, fragment = target.partition("#")
        destination = (doc.parent / unquote(raw_path)).resolve() if raw_path else doc.resolve()
        local_links += 1
        if not destination.exists():
            errors.append("missing relative link " + str(doc.name) + " -> " + target)
        elif sep and destination.suffix.lower() == ".md":
            anchors_checked += 1
            if unquote(fragment) not in anchors(destination):
                errors.append("missing Markdown anchor " + str(doc.name) + " -> " + target)
if errors:
    print("\n".join(errors), file=sys.stderr)
    raise SystemExit(1)
print(f"OK: {len(expected)} normative identifiers, {len(records)} proof rows, "
      f"{sum(r[7] == 'obligatoire L2' for r in records)} L2 obligations, "
      f"{sum(r[7] == 'reporté' for r in records)} reports, "
      f"{len(catalog_ids)} distinct oracles, {len(oracle_refs)} oracle references, "
      f"{len(set(catalog_ids) - set(oracle_refs))} orphan catalog oracles, "
      f"{sum(not re.findall(r'OR-[A-Z0-9]+-[A-Z0-9]+', r[4]) for r in records)} rows without oracle, "
      f"{subscenario_count} subscenario rows, "
      f"{local_links} relative links, {anchors_checked} anchors. "
      "Mechanical checks only; semantic adequacy requires contradictory review.")
