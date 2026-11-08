# Pocoma V3 — Performance Bench & Bounded Local Qualification Tooling

## 1. Overview and Architecture

This directory provides the canonical, composable, and non-intrusive performance benchmarking tooling for **Pocoma V3** (`kartaguez/pocoma`).

The tooling strictly adheres to:
- **SPEC-09** (Performance budgets and profiles)
- **SPEC-10** (Test certification protocols, scenarios F01-A..J and OPS-01 O01-A..N)
- **C-03** (End-to-End Availability and Balances milestones)
- **Reactor Verification Policy** (`docs/testing/Reactor_Verification_Policy.md`)

```
scripts/
├── k6/
│   ├── v3-performance.js       # Core k6 performance scenario runner (coupled & decoupled)
│   └── lib/
│       ├── v3-api.js           # Low-level asynchronous V3 API client
│       └── v3-oracles.js       # C-03 oracle assertions (Pot, Balance, Delete, Identity)
└── performance/
    ├── v3-preflight.py         # Mandatory system safety and environment preflight gate
    ├── v3_token.py             # Proactive OIDC Keycloak PKCE/refresh token manager & server
    ├── v3_observer.py          # Decoupled coordinator, concurrent observers & SQL reconciler
    ├── v3-seed.py              # Reproducible SPEC-09 dataset & fixture generator
    ├── v3-run.py               # Master orchestrator & dual budget watchdogs (R2 / R4)
    ├── v3-collect.py           # Metrology collector (k6, Engine, System, PostgreSQL)
    ├── v3-plot.py              # SVG chart generator & CSV datasets exporter (8 charts)
    ├── test_bench.py           # Bench qualification test suite (26 mandatory tests)
    ├── test_r4_observer.py     # R4 concurrent observer & SQL reconciler test suite (9 tests)
    └── README.md               # Architecture, usage, and operational guidelines
```

---

## 2. Mandatory Preflight (`v3-preflight.py`)

No benchmark run may execute without passing the preflight check. It enforces:
1. **Target Host Authorization**: Rejects non-local / non-test targets (strictly `127.0.0.1`, `localhost`).
2. **Git SHA Identification**: Records active commit SHA and repository branch.
3. **Database Consistency**: Verifies presence of `v3_write` tables, `v3_read` tables, and all 28 discovery checkpoints.
4. **Service Readiness**: Validates Monolith Actuator (`:18081`), Monolith HTTP API (`:8080`), Prometheus (`:9090`), and Grafana (`:3000`).
5. **Indispensable Metrics**: Ensures essential Prometheus metrics (`pocoma_v3_*`, `jvm_*`, `hikaricp_*`) are actively exposed.
6. **READ Maintenance State**: Ensures state is `READY` (fail-closed check).
7. **Host Resources**: Verifies CPU cores and free disk space.

```bash
# Execute preflight check:
python3 scripts/performance/v3-preflight.py
```

---

## 3. Dataset & Seed Generator (`v3-seed.py`)

Generates traceable, deterministic datasets with cryptographic SHA-256 fingerprints.

### Supported Profiles (SPEC-09):
- **`small`**: 5 shareholders, 20 expenses
- **`medium`**: 20 shareholders, 200 expenses
- **`large`**: 100 shareholders, 2,000 expenses
- **`historical-10k`**, **`historical-1m`**, **`historical-10m`**: Normative volume profiles (strictly disabled by default during local qualification; guarded by `--enable-large-scale`).

```bash
# Generate small profile seed:
python3 scripts/performance/v3-seed.py --profile small --save

# Generate medium profile seed with single-pot contention:
python3 scripts/performance/v3-seed.py --profile medium --contention --save
```

---

## 4. Benchmark Execution & Watchdog (`v3-run.py`)

The master orchestrator wraps k6 injection with an active background watchdog:
- **Global Budget Ceiling**: Strict 70 minutes (4200 seconds) cumulative across the entire qualification mission.
- **Scenario Timeouts**: Per-scenario ceilings (e.g. 2 min smoke, 5 min small).
- **Incident Abort**: Instantly kills k6 injection if timeout, memory runaway, or database instability occurs.
- **Diagnostics Preservation**: Automatically collects k6 summary, Actuator metrics, and PostgreSQL telemetry upon completion or abort.
- **Zero Automated Retries**: Never re-attempts an aborted run.

```bash
# Check cumulative qualification budget:
python3 scripts/performance/v3-run.py --budget-status

# Run smoke test:
python3 scripts/performance/v3-run.py smoke --duration 20s

# Run nominal small profile:
python3 scripts/performance/v3-run.py small --duration 30s --rate 1

# Run single-pot contention scenario:
python3 scripts/performance/v3-run.py F01-H --duration 20s
```

---

## 5. Metrology & Telemetry Collection (`v3-collect.py`)

Consolidates multi-tier observations into structured JSON artifacts stored in `docs/testing/evidence/L6-I3/`:
- **k6 Client-side**: Offered throughput, triggered throughput, dropped iterations, admissions, terminal results, p50/p95/p99 latencies for Admission, Command Result, Balance Available, and Pot Complete Read.
- **Pocoma Engine**: Admission requests, committed vs retried attempts, READ quota permits, and worker capacity.
- **System**: JVM heap and non-heap RSS, GC pause frequency and total duration, process and host CPU usage, live threads, and HikariCP connection pool states.
- **PostgreSQL**: Active sessions, lock waits, pending and quarantined consumption slots, and READ maintenance state.

```bash
# Run standalone collection on existing run summary:
python3 scripts/performance/v3-collect.py --scenario small --run-id run-small-001
```

---

## 6. Bench & Oracle Qualification Suite (`test_bench.py`)

Validates the bench against all 14 mandatory discriminant failure modes:
1. Admission refused (HTTP non-202).
2. Command accepted but uncompleted (polling timeout).
3. Terminal result without new version (`resultingPotVersion == 0`).
4. `Balance(P,V)` absent.
5. Incomplete Pot (missing shareholders or balances).
6. READ lag ($W < V$).
7. READ under maintenance (HTTP 503 fail-closed with `no-store`).
8. Unusable identity binding.
9. `DeletePot` unmaterialized (HTTP 200 returned instead of 404).
10. Asynchronous branch timeout (HTTP 408).
11. Indispensable metric missing from telemetry.
12. Dropped iterations detected in k6 summary.
13. Inconsistent or tampered dataset.
14. Watchdog trigger and process termination.

```bash
# Run oracle test suite:
python3 scripts/performance/test_bench.py
```

---

## 7. R4 Metrological Tooling & Concurrent Observers (`v3_observer.py`, `test_r4_observer.py`)

Addressing the 7 audit findings of L6/I3-R3 (F-01..F-07), the R4 metrological architecture introduces:
1. **Concurrent Observer Pools (`TerminalObserver`, `ReadObserver`)**:
   - `TerminalObserver` (12 worker threads): polls `/v3/commands/{id}` concurrently with exponential backoff on HTTP 404 (100ms..2000ms), eliminating observer starvation at high admission rates.
   - `ReadObserver` (8 worker threads): polls `/v3/pots/{id}` strictly post-terminalization, ensuring zero contention with the write stage and zero READ 503 semaphore exhaustion.
2. **Dual-Axis Backlog Monitoring**:
   - `upstreamPending`: counts uncommitted commands in `v3_write.v3_command WHERE status = 'PENDING'`.
   - `slotBufferTotal`: counts work units in `v3_write.consumption_slot` (ventilated by state: `AVAILABLE`, `QUARANTINED`, `CLAIMED`).
   - Differentiates ingestion queuing from the internal `DiscoveryEngine.softCap` (256 slots across 4 partitions).
3. **Read-Only Server-Side SQL Reconciliation (`SqlReconciler`)**:
   - Compares client-observed outcomes with database ground truth.
   - Asserts exact conservation: $\text{serverAdmitted} = \text{serverApplied} + \text{serverCasRejected} + \text{serverPending} + \text{serverFailed}$.
4. **R4 Test Suite (`test_r4_observer.py`)**:
   - 9 unit tests validating concurrent observations, exact conservation equations (without timeout double-counting), backlog dual-axis polling, SQL reconciliation, and quantile computations.

```bash
# Run R4 observer test suite:
python3 scripts/performance/test_r4_observer.py

# Run R4 qualification suite with dedicated 1800s watchdog:
python3 scripts/performance/v3-run.py --r4

# Collect R4 metrology into dedicated evidence directory:
python3 scripts/performance/v3-collect.py --scenario q5-ramp-50 --evidence-dir docs/testing/evidence/L6-I3/R4

# Generate R4 SVG charts and CSV datasets:
python3 scripts/performance/v3-plot.py --evidence-dir docs/testing/evidence/L6-I3/R4
```
