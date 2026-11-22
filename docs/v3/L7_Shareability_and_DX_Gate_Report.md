# Pocoma V3 — L7 Shareability & Developer Experience Gate Report

- **Date**: 2026-11-22
- **Reference branch**: `feat/v3-l7`
- **Starting HEAD**: `12ca8767` (with `CERT-FULL` qualified on `f589d95c`, `1 173/1 173 PASS`, `50/50 architecture-tests PASS`)
- **Upstream L7 qualification verdict**: `L7_LOCAL_CERTIFIED_WITH_RESERVES` ([L7_Final_Global_Qualification_Dossier.md](L7_Final_Global_Qualification_Dossier.md))
- **Host platform tested**: macOS (Darwin 27.0.0, Apple Silicon ARM64, Docker Desktop 8 GB RAM / 6 CPUs)
- **Overall Shareability Gate Verdict**: **`PASS WITH RESERVATIONS`** (all 4 dimensions `PASS` or `PASS WITH RESERVATIONS`; `0` `BLOCKED`)

---

## 1. Summary of the Four Independent Verdicts

| Dimension | Verdict | Summary |
| :--- | :--- | :--- |
| **`INSTALLABLE`** | **`PASS WITH RESERVATIONS`** | Full clean-clone install, Maven build, 9-container startup, DB setup, OIDC PKCE login, WRITE→READ flow, Bruno 118/118, stop/restart, targeted test, and destroy verified on macOS ARM64. Reservations: Linux/Windows local `./pocoma-local` unverified; cold-cache network downloads (Maven/Docker/npm) not isolated from host caches. |
| **`UNDERSTANDABLE`** | **`PASS`** | Clear public entry points (`README.md`, `docs/README.md`), progressive 30-minute onboarding path covering all 8 architectural pillars, zero broken links across 30 public docs, historical/L7 reports cleanly separated into `docs/internal/` and `docs/v3/`. |
| **`READABLE`** | **`PASS WITH RESERVATIONS`** | Clean 42-module layered taxonomy (`domain-*`, `contract-*`, `port-*`, `engine-*`, `orchestrator-*`, `supra-*`, `infra-*`, `compose-*`, `runtime-*`) enforced by ArchUnit; predictable command-to-projection naming. Reservations: a few large technical classes (`BootstrapGuard` 1 140 L, `ConsumptionEngine` 1 079 L, `JdbcPotVersionStore` 956 L, `ConsumptionWorker` 897 L) and compact single-line formatting in some controllers (`ReadController`). |
| **`CONTRIBUTABLE`** | **`PASS`** | Simulated end-to-end contribution on a throwaway branch (`sim/contrib-review`) in a clean clone succeeded cleanly (`verify.py FAST identity` 54/54 PASS in 2.53 s; `./mvnw -pl domain-identity test` 21/21 PASS in 0.79 s; `git diff --check` clean). `CONTRIBUTING.md`, PR template, `Reactor_Verification_Policy.md`, and `docs/development/ci.md` are aligned. |

---

## 2. Detailed Gate Assessment

### 2.1 `INSTALLABLE` — `PASS WITH RESERVATIONS`

**Checks actually executed** (in an isolated clean clone `/tmp/pocoma-clean-review` at `12ca8767`, using dedicated `--instance review-clean`, without touching main workspace data or volumes):

1. **Workstation check**: `./pocoma-local doctor` → `READY` (`exit=0`, `readOnly=true`, `buildCached=false`, `brunoCliInstalled=false`, `dockerMemoryMiB=7934`, `diskSufficient=true`).
2. **Clean-clone build & 9-container startup**: `./pocoma-local start --instance review-clean` → `READY` (`exit=0`, ~57 s):
   - Compiled Maven artifacts from scratch via `app/mvnw` (JDK 21).
   - Initialized PostgreSQL 17.6 (`pocoma_write` / `v3_write`, `pocoma_read` / `v3_read`, 36 routing partitions, V1–V5 READ availability schema, least-privilege grants).
   - Started Keycloak 26.7.0, 5 JVM runtimes (`http-write`, `http-read`, `command-worker`, `results-worker`, `business-read-worker`), Prometheus v3.5.0, Grafana 12.3.2.
   - All `20/20` readiness checks `PASS` + functional canary `PROVEN` (`potId=8e6f48ce-932f-5c4c-98b2-4620bea3b398`, `version=2`).
3. **Diagnostics, stop, restart**: `./pocoma-local status` (`READY`, `exit=0`), `./pocoma-local stop` (`STOPPED`, `volumes=PRESERVED`, `forcedKill=[]`), `./pocoma-local start` (`READY`, ~24 s).
4. **Automated E2E suite from clean clone**: `./pocoma-local bruno --instance review-clean` → auto-installed Bruno CLI `4.3.0` and passed all 3 journeys (`BRUNO_PASS`, `exit=0`, 118 requests / 236 tests in 12 s).
5. **Manual WRITE → READ journey** (following `docs/getting-started/first-steps.md`): OIDC PKCE login (`third`/`pocoma`) → `GET /api/v3/me/binding` (`200`) → `POST /api/v3/commands` `CreatePot` (`202`) → `GET /api/v3/commands/{id}/result` (`200`, `COMPLETED`, `resultingPotVersion=1`) → `GET /api/v3/pots/{id}?minVersion=1` (`200`, `potVersion=1`).
6. **Targeted test reproduction**: `python3 -B scripts/testing/verify.py FAST identity` → `PASS` (`53/53`, `2.28 s`).
7. **Cleanup**: `./pocoma-local destroy --instance review-clean --confirm DESTROY:review-clean --dry-run` (`DESTROY_PLAN`, `exit=0`) and `--confirm DESTROY:review-clean` (`DESTROYED`, `exit=0`, zero remaining `review-clean` containers/volumes). Temporary clone `/tmp/pocoma-clean-review` deleted.

**Difficulties encountered & corrections applied**:
- `docs/getting-started/installation.md`, `docs/reference/cli.md`, and `docs/operations/recovery.md` showed `reset --dry-run` and `destroy --dry-run` **without** `--confirm RESET:<instance>` / `--confirm DESTROY:<instance>`, which actually exits with code `2` (`DESTROY-EXPLICIT-INSTANCE-AND-CONFIRMATION-REQUIRED`). **Fixed** in all three documents.
- `docs/getting-started/installation.md`, `docs/reference/cli.md`, and `docs/operations/troubleshooting.md` did not yet document the `PRE-DISK` check added in `f589d95c`, and `troubleshooting.md`/`configuration.md` still stated `Node 24` and "forbidden env vars" instead of `Node >= 22` and automatic env var stripping (`clean_toolchain_env`). **Fixed**.

**Remaining non-blocking reservations**:
- **Implicit host caches**: Maven `~/.m2` and the 5 Docker base images were already present on the macOS verification host; a completely cold workstation requires internet access to download Maven artifacts, ~1.5–2 GB of Docker images, and `npm` packages for Bruno CLI. Documented explicitly in `docs/getting-started/installation.md`.
- **Platform scope**: local `./pocoma-local` orchestration is verified on **macOS ARM64** only (CI tests Maven + Testcontainers on `ubuntu-24.04`). Linux and Windows (WSL2) local `./pocoma-local` usage remains unverified.

---

### 2.2 `UNDERSTANDABLE` — `PASS`

**Checks executed**:
- Audited all 30 public documentation files (`README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `CODE_OF_CONDUCT.md`, `docs/README.md`, `docs/getting-started/*`, `docs/architecture/*`, `docs/guides/*`, `docs/reference/*`, `docs/operations/*`, `docs/development/*`).
- Verified progressive coverage of all 8 core concepts: role & use cases, CQRS principles, WRITE/READ split, commands/engines/consumptions, projections/materialization/availability, transactional guarantees (leases, fencing, idempotency, exact rationals), 5 runtimes + PostgreSQL 17, and HTTP command-to-projection trace.
- Executed automated link check across all 30 public docs: **`0` broken links**.

**Corrections applied**:
- Added a **Recommended 30-Minute Onboarding Path** to `README.md` and `docs/README.md`.
- Added explicit **Qualification Scope & Platform Status** to `README.md`.
- Fixed subcommand count (`9` → `11`) in `docs/README.md` and `docs/reference/README.md`.
- Updated local load envelope in `docs/operations/troubleshooting.md` (`25 cmd/s` → `35 cmd/s` sustained / `36.83 cmd/s` local plateau post-C4).

**Remaining non-blocking reservations**:
- Product documentation is in English while internal L7 qualification reports (`docs/v3/`) are in French; this is acceptable since internal reports are secondary and not required for onboarding.

---

### 2.3 `READABLE` — `PASS WITH RESERVATIONS`

**Checks executed**:
- Audited all 42 Maven modules in `app/pom.xml`, main/test file counts, largest classes, runtime entry points (`PocomaWebWriteApplication`, `PocomaWebReadApplication`, `PocomaProcessWorkerApplication`, `PocomaMaterializeWorkerApplication`, `PocomaRuntimeMonolithApplication`), HTTP controllers (`AdmissionController`, `ReadController`, `InvitationController`), and end-to-end command-to-projection path (`AdmissionController` → `CommandAdmission` → `ExecutePotCommand` → `MaterializeCommandResult` / `MaterializePotVersion` → `ReadPot` → `ReadController`).
- Verified strict Domain / Contract / Port / Engine / Orchestrator / Supra / Infra / Compose / Runtime separation (enforced by 50 ArchUnit tests in `architecture-tests`).

**Remaining non-blocking reservations** (deferred — refactoring out of mandate):
- Several technical core classes exceed 700 lines (`BootstrapGuard` 1 140 L, `ConsumptionEngine` 1 079 L, `JdbcPotVersionStore` 956 L, `ConsumptionWorker` 897 L, `StageHandoffTracker` 783 L, `Radix16Tree` 733 L). They are cohesive and well-tested, but represent the steepest learning curve for newcomers.
- Compact multi-statement line style in a few web classes (e.g., `ReadController.java`) reduces scanability compared to standard multi-line Java formatting.

---

### 2.4 `CONTRIBUTABLE` — `PASS`

**Checks executed**:
- Simulated a real contribution on throwaway branch `sim/contrib-review` inside `/tmp/pocoma-clean-review`: located `domain-identity`, added a unit test in `IdentityValueContractsTest`, ran `git diff --check`, `python3 -B scripts/testing/verify.py FAST identity` (`54/54 PASS`, `2.53 s`), and `./mvnw -pl domain-identity test` (`21/21 PASS`, `0.79 s`). Discarded the throwaway clone without modifying production code.
- Audited `CONTRIBUTING.md`, `.github/PULL_REQUEST_TEMPLATE.md`, `docs/development/ci.md`, `.github/workflows/ci.yml`, `docs/testing/Reactor_Verification_Policy.md`, `LICENSE`, `NOTICE`, `SECURITY.md`, `CODE_OF_CONDUCT.md`.
- Scanned tracked files for secrets and personal paths.

**Corrections applied**:
- Removed hardcoded personal machine path (`/Users/julien.guezennec/...`) from `.claude/helpers/graft-hooks.cjs` and `.claude/helpers/graft-statusline.cjs` (now `const BAKED = null;` with dynamic package/global fallback).
- Updated `docs/development/ci.md` required branch check names to match `.github/workflows/ci.yml` (`core-web-and-architecture` + the 7 `heavy-modules` matrix jobs instead of retired `build-and-test`).

**Remaining non-blocking reservations**:
- Historical reports under `docs/internal/` and `docs/v3/` retain historical machine paths or `file:///` links as audit traces; no secrets or tokens were found in tracked files.
- `scripts/testing/` and `scripts/performance/` contain many historical L1–L7 qualification scripts alongside daily tools (`scripts/local/`, `scripts/testing/verify.py`); newcomers should rely on `./pocoma-local` and `CONTRIBUTING.md`.

---

## 3. Normative `Reactor_Verification_Policy.md` Report

```text
Start HEAD: 12ca876726a2a86555e84fd3cba631501f5b57aa
Expected production impact (app/): NONE (documentation + .claude helper hygiene only)
Observed production impact (app/): NONE
Declared slices:
- DOCS / HYGIENE (no production Java or SQL changes; CERT-FULL on f589d95c remains valid)
Verification commands executed:
- Clean-clone end-to-end installation, Bruno E2E (118/118), manual WRITE->READ, stop/restart, destroy (macOS ARM64)
- Clean-clone contribution simulation: verify.py FAST identity (54/54 PASS), ./mvnw -pl domain-identity test (21/21 PASS)
- Public documentation link check (30 files, 0 broken links)
- node -c .claude/helpers/graft-hooks.cjs .claude/helpers/graft-statusline.cjs
- git diff --check
Unexpected slice crossings: NONE
Global architecture gate: NOT REQUIRED (no app/ changes; 50/50 PASS already certified in CERT-FULL f589d95c)
Full reactor: NOT REQUIRED (no app/ changes; 1173/1173 PASS already certified in CERT-FULL f589d95c)
Database verification: Verified live via clean-clone ./pocoma-local start (PostgreSQL 17.6, v3_write + v3_read V1-V5)
```
