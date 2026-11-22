# Local Development & Bruno

This guide covers daily local development tools for Pocoma: isolated instances, interactive and automated Bruno testing, Grafana observability, and the Java inner-loop / Maven slice workflow.

## 1. Managing Multiple Local Instances

By default, `./pocoma-local` targets the `demo` instance. You can create isolated instances (names matching `[a-z][a-z0-9-]{0,30}`) to test a branch or start from a clean database without touching `demo`:

```bash
./pocoma-local start --instance dev-feature
./pocoma-local status --instance dev-feature
./pocoma-local stop --instance dev-feature
```

Each instance stores its metadata, input fingerprints, local secrets, and execution logs under `.pocoma-local/instances/<instance>/`.

> [!NOTE]
> If you modify Java code, `.properties` files, or SQL scripts between restarts of the same instance, `./pocoma-local start` refuses to start against potentially incompatible volumes (`PRE-INPUT-DRIFT` / `DB-INPUT-DRIFT`). Use `reset --confirm RESET:<instance>` or create a new `--instance <name>`.

## 2. Testing with Bruno (GUI and CLI)

The product collection lives in `scripts/bruno-playground/` (**Pocoma V3 Playground**, `171` visible requests including `118` steps across 3 end-to-end journeys).

> [!NOTE]
> The repository also contains `scripts/bruno/` (legacy) and `scripts/bruno-v3/` (specialized qualification fixtures). **Always use `scripts/bruno-playground/`** for local exploration and playground testing.

### Interactive Mode (Bruno GUI)

```bash
./pocoma-local gui --instance demo
```

In Bruno:
1. Open the `scripts/bruno-playground` folder.
2. Select the **`Instance-demo`** environment in the top-right corner (`Local` is reserved for the CLI).
3. Enable **`Developer Mode`** in the `</>` menu (*JavaScript Sandbox*).
4. Run `00 - Getting Started / E00 Environment Check`, then authenticate `00 Login Owner` (`third` / `pocoma`) and `01 Login Guest` (`guest` / `pocoma`) via **Auth → Get Access Token**.
5. Run a full journey under `90 - End-to-End Journeys` (**Run**) or send individual requests by domain.

### Automated Mode (Bruno CLI 4.3.0)

```bash
./pocoma-local bruno --instance demo
```

Executes the **3 journeys (`J01`, `J02`, `J03`)** — **118 requests and 236 named tests**:
- **`J01 Complete Ledger and Invitation`** (`66` steps): registration, Pot creation, Guest invitation, rational expenses (`10/3`, `20/3`), exact balances, history, voluntary leave, and deletions.
- **`J02 Errors Privacy and Idempotency`** (`31` steps): idempotent replay, `409` conflict, pagination, opaque `400`/`401`/`404` rejections, and business rejections.
- **`J03 Unlink Re-register and Historical Ownership`** (`21` steps): `Unlink`, invalidation of the old `bindingToken`, re-registration, and preservation of historical results.

## 3. Local Observability (Prometheus & Grafana)

- Prometheus scrapes `/actuator/prometheus` on all 5 runtimes (`:18080` to `:18085`) every `2 s`.
- Grafana (`12.3.2`, credentials `admin` / `pocoma`, URL printed by `./pocoma-local status`) exposes two read-only dashboards:
  - **`Pocoma runtime`**: JVM health, HikariCP pools, HTTP concurrency, and worker state (`draining`, leases).
  - **`Pocoma V3 - Architecture & Observability Overview`**: WRITE/READ throughput, latencies, and partition counters.
- To refresh only Grafana dashboards without resetting your data:

```bash
./pocoma-local upgrade-grafana --instance demo
```

## 4. Modifying & Testing Java Code (`app/`)

The Maven reactor under `app/` contains **43 child modules** and one aggregator. Before editing Java code, review [Modular Architecture (43 modules)](../architecture/modules.md) to identify which layer owns your change.

### Recommended Java Inner Loop

1. **Fast feedback (seconds, no full stack rebuild)**: iterate using targeted Maven slice tests (or `verify.py FAST`) backed by Testcontainers PostgreSQL.
2. **Checking runtime composition**: if your change touches Spring wiring or multiple slices, run tests in `runtime-monolith` or the affected `runtime-*` slice.
3. **Reloading the 9-container playground without losing data (Java-only changes)**: because `./pocoma-local start` enforces `PRE-INPUT-DRIFT` to protect existing instances from silent mismatches, use `reload` when you only modified Java code (no SQL migrations, `bootstrap.py`, Keycloak, or Docker config changes):

   ```bash
   ./pocoma-local reload --instance dev-feature
   # Or rebuild/recreate only the runtime(s) you changed:
   ./pocoma-local reload --instance dev-feature --runtime http-write
   ```

   `reload` rebuilds the 5 `runtime-*` JARs, recreates only the 5 JVM runtime containers (`postgres`, `keycloak`, `prometheus`, `grafana`, and your database volumes stay intact), verifies that the PostgreSQL schema did not drift (`DB-CATALOGUE-DRIFT`), and re-qualifies the instance.
4. **Full reset (when SQL schemas, migrations, or infra config changed)**: `reload` refuses with `RELOAD_RESET_REQUIRED` if SQL or infrastructure inputs changed. In that case, reset your dev instance or spin up a fresh one:

   ```bash
   ./pocoma-local reset --instance dev-feature --confirm RESET:dev-feature
   # or
   ./pocoma-local start --instance dev-v2
   ```

### IDE & Fast Debugging Workflow

- **Opening in IntelliJ IDEA / VS Code**: import `app/pom.xml` as a Maven project using **JDK 21**.
- **Running & debugging tests directly from the IDE**: integration tests (`*PostgresTest`) use **Testcontainers PostgreSQL** automatically (Docker Desktop must be running), so you can set breakpoints and run/debug any JUnit 5 test class directly without starting `./pocoma-local`.
- **Fast domain feedback from the CLI**: use `verify.py FAST <domain>` from the repository root (for example `python3 -B scripts/testing/verify.py FAST identity`) before running the full slice command.
- **Monolith composition checks**: `app/runtime-monolith/src/main/resources/` contains the Spring profiles (`application.properties`, `application-v3-consumption-worker-dev.properties`, `application-v3-read-worker.properties`) used when testing combined runtime wiring in `runtime-monolith`.

### Canonical Maven Verification Slices

Per the normative [Reactor_Verification_Policy.md](../testing/Reactor_Verification_Policy.md), **do not run `./mvnw test` across the whole reactor by default**; run the slice matching your change from `app/`:

| Slice | Canonical command (from `app/`) | Scope |
| :--- | :--- | :--- |
| **WEB** | `./mvnw -pl runtime-web-write,runtime-web-read -am test` | WRITE admission, READ queries, HTTP security |
| **COMMAND** | `./mvnw -pl runtime-worker-process -am test` | Command/identity execution, fencing, recovery |
| **PROJECTION** | `./mvnw -pl runtime-worker-materialize -am test` | Result & business READ materialization |
| **FOUNDATION** | `./mvnw -pl infra-tx-spring -am test` | Transaction contexts & isolation |

For CI rules and the global architecture gate (`architecture-tests`), see [Continuous Integration (CI)](../development/ci.md).
