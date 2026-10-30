# I3 — corrective evidence, distinct from the preserved contradictory audit

Report: [resolution](../../../v3/L3_I3_Findings_Resolution_Report.md).
Delivery hashes: [manifest.json](manifest.json).
Architecture population: [all 28 test classes](Architecture-population.json).
Original audit: [CT dossier](../L3-I3-CT/README.md), unchanged.

Inputs: [Proof-inputs.json](Proof-inputs.json). Audited application/SQL sources
`2b3567b1f77051c399772ee7ac787f7dd61ba407`; documentary publication
`00cdc16253ef9900224c7c6be90a0a814d2baf73`; frozen correction code
`c1172bf4a758a9077415d827ca88b359b6360ebb`.
The before checkout's actual HEAD is the documentary publication; all 49 entries
of the audit source inventory were freshly hash-checked against that checkout.
Application classes there were reused, not claimed as a new complete Maven build.

- [Exact correction diff](Correction-code.diff.patch.gz): `git diff --binary
  00cdc162 c1172bf4`, including the initial scope declaration and runbook. Later
  documentary evidence/report changes are a separate commit; Git preserves the
  complete delivery diff. Gzip restoration is byte-for-byte (`gzip -dc`); it preserves
  required blank context lines without whitespace checks interpreting the nested patch
  as new source. No code/test input changes after this code freeze.
- [Commands](Commands.json): exact Maven invocations, external harness modes,
  expected negative exits and final outcomes.
- `before-external.tar.gz`: adapted original harness sources, scripts and new
  experimental logs. Nonempty views/definer accepted by old preflight; actual
  Worker starts; two destructive committed TRUNCATEs on synthetic data only.
- `after-external.tar.gz`: same probes with final guard compiled to an isolated
  overlay, independently of Maven target writes. Three original exposures/root
  startup refused. Administrative excess grants refused, legitimate grants
  reapplied, event history retained. Logging markers checked in memory only.
- `before-external-manifest.json` / `after-external-manifest.json`: exact members,
  lengths and SHA-256. No exported raw server log, application dependency jars or
  generated `.class` files. Constant markers in the benchmark source are synthetic.
- `feedback-intermediate.tar.gz`: first focused feedback, 73 tests, no failure,
  error or skip. This was an unfrozen intermediate worktree; its input hashes were
  not recorded at execution. It is not attributed to the final code SHA and is
  not a canonical gate. XML and logs remain separate from final executions.

The logging path newly qualified is PostgreSQL stderr -> Docker log collector,
plus Java exception redaction. There is no claim about an untested production
collector, tracing/audit extension or arbitrary client logger. The administrative
owner, provisioner, extensions and superuser remain trusted.

## Reproduce safely

Read AGENTS.md and the Reactor policy first. Use a fresh disposable PostgreSQL
17.11 server, the image digest recorded in each container-provenance.json, and
new synthetic database/role identities. The *before* terminal probes intentionally
commit TRUNCATE and corrupt that synthetic dataset; never point them at existing
data, and never reuse that dataset for positive functional proof.

The archived scripts deliberately preserve their executed absolute paths,
container names and localhost ports. Adapt those identities consistently for a
new run, record the adaptation, and preserve the originals. No credentials beyond
synthetic disposable test identities are required by the independent harness.

For the before run, follow the original [reproduction protocol](../L3-I3-CT/reproduction/README.md),
then use the archived modes/order in Commands.json. Compile the external harness
with a classpath captured from a freshly generated Worker Surefire XML when
reproducing elsewhere; the recorded absolute classpath is provenance, not a
portable dependency bundle. The observed before run reused compiled application
classes from the audit checkout and freshly compiled the external harness.

For after, checkout the frozen correction SHA, run the focused tests named in
Commands.json to build the actual modules, and capture `java.class.path` from
`V3IdentityRequestCompositionPostgresTest`'s fresh XML. Compile the archived Java
harness with that classpath. The observed independent run compiled just the changed
guard to an external overlay and placed it first in the recorded first-feedback
runtime classpath; it never overwrote Maven outputs during the canonical run.

Launch the after PostgreSQL with:

```sh
-c 'log_line_prefix=%m [%p] xid=%x state=%e '
```

The adapted after `prepare.py` applies the new logging database defaults before
its delivered grant stages. Execute normal, seed, collision, permissions, then
`after_checks.py`; `boot-view` must exit 1 with the specific indirect-exposure
refusal. READ SQL may still read an intentionally overgranted view before an
operator revokes its grant: preflight refusal is not SQL ACL remediation.
The new root tests explicitly revoke grants/remove intermediaries and prove the
resulting PostgreSQL refusal and legitimate startup.

The before/after containers created by this resolution were removed after final
metadata/cardinality collection. Historical I1/I2/I3 FAILs and reserves remain
unchanged. No canonical PASS, production qualification or I4 authorization.

## Final verification receipts

- `canonical.tar.gz` / `canonical-summary.json` / `canonical-members.json`:
  one unfiltered composed gate, 1,867 tests, one failure (`i5_r12`), no error,
  one native skip, Architecture SKIPPED. Duration 49:04. Sources at the frozen
  SHA, preexisting output directories; source/bytecode qualification below.
- `architecture-stale-output.tar.gz`: 162 controls, two failures, no error/skip.
  This failure is retained. `Bytecode-qualification.json` and paired `*-javap.txt`
  identify an old declared class package and an incompatible precompiled lambda
  name. No assertion or production source was changed to make these controls pass.
- `fresh-feedback.tar.gz` / `fresh-feedback-summary.json` / `fresh-feedback-members.json`:
  fresh checkout exactly at the frozen SHA, clean Git status, newly compiled
  application classes, 232 successful focused tests (70 Identity/DEV + 162
  Architecture), no failure/error/skip. Duration 04:14. This contribution repairs
  the verification context; it does not replace the failed unfiltered gate.
- `collect.py`: positive collection on the actual runs, plus the deliberate
  stale-XML negative in `collector-negative.txt`. It refuses a proof with no
  freshly executed XML. Collection was sequential; no concurrent Maven target
  writers were introduced by this mission.
- `Preserved-primary-status.txt`: read-only primary-checkout snapshot. A concurrent
  external operations-document edit was observed and preserved. This is not a
  claim that concurrent file contents were frozen or byte-identical.

The unfiltered archive's embedded summary records the source SHA at execution;
its later-discovered bytecode qualification is supplemental and explicit in
`Bytecode-qualification.json`. Neither its failure nor the failed architecture
contribution is rewritten after the clean focused build. Long L2 tests were not
repeated after that discovery. The fresh source/bytecode-coherent contribution is
bounded; there is no new canonical PASS or I4 authorization.
