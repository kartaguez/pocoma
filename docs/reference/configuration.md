# Runtime Configuration Reference

Default properties for the five Java services in the local distribution are defined under [`docker/v3-five-runtimes/`](../../docker/v3-five-runtimes/http-write.properties) and orchestrated by [`scripts/local/runtime.py`](../../scripts/local/runtime.py).

## Configuration of the 5 JVM Runtimes

All JVM runtimes use the **`eclipse-temurin:21.0.8_9-jre`** image, JVM flags **`-Xms64m -Xmx384m -XX:+UseSerialGC`**, and Docker container limits of **`640m`** / **`1` CPU** each:

| Runtime | Module & Spring Profile | Internal Ports (HTTP / Actuator) | Key Concurrency, Wakeup & JDBC Pool Settings | Source File |
| :--- | :--- | :---: | :--- | :--- |
| `http-write` | `runtime-web-write`<br>`v3-http-admission` | `8080` / `18080` | Tomcat `maxThreads=4`, `pocoma.v3.http.admission.capacity=4`, Core NATS wakeup publisher (`mode=nats`, `IN_FLIGHT_DEDUP`) | [`http-write.properties`](../../docker/v3-five-runtimes/http-write.properties) |
| `http-read` | `runtime-web-read`<br>`v3-http-read` | `8082` / `18082` | Tomcat `maxThreads=4`, `http-permits=8` (`Q=8`), `sql-permits=8`, main pool `maximum-pool-size=8` / `minimum-idle=8`, maintenance pool `1` | [`http-read.properties`](../../docker/v3-five-runtimes/http-read.properties) |
| `command-worker` | `runtime-worker-process`<br>`v3-consumption-worker-dev` | — / `18083` | **Pure JDBC (no JPA/Hibernate)**; `worker-limit=4`, `Command=3`, `IdentityRequest=2`, `write-business=6`, `write-control=10`, Core NATS subscriber+publisher (`reconcile-poll=2000ms`, `fallback=20ms`) | [`command-worker.properties`](../../docker/v3-five-runtimes/command-worker.properties) |
| `critical-read-worker` | `runtime-worker-materialize`<br>`v3-read-worker` | — / `18084` | **T1A critical READ**: `worker-limit=4`, `MaterializeCommandResult=2`, `MaterializeIdentityRequestResult=2`, `MaterializeCurrentBinding=2`, `write-source-read=6`, `read-business=6`, `read-control=10`, Core NATS subscriber | [`critical-read-worker.properties`](../../docker/v3-five-runtimes/critical-read-worker.properties) |
| `pot-read-worker` | `runtime-worker-materialize`<br>`v3-read-worker` | — / `18085` | **T1A pot READ**: `worker-limit=4`, `MaterializePotVersion=2`, `MaterializePotBalance=2`, `MaterializePotVisibility=2`, `MaterializeInvitation=2`, `write-source-read=6`, `read-business=6`, `read-control=10`, Core NATS subscriber | [`pot-read-worker.properties`](../../docker/v3-five-runtimes/pot-read-worker.properties) |

## Core NATS Wakeup Properties (`pocoma.v3.wakeup.*`)

All wakeup parameters are independently configurable (defaults in `docker/v3-five-runtimes/*.properties`):

| Property | Default | Role |
| :--- | :---: | :--- |
| `pocoma.v3.wakeup.mode` | `nats` (or `noop`) | `nats` enables Core NATS wakeup; `noop` disables broker usage (autonomous polling). In `pocoma-local`, the chosen mode is persisted in `manifest.json` and can be switched non-destructively via `start --instance <name> --wakeup-mode {nats,noop}` (controlled restart of the 4 wakeup runtimes, zero PostgreSQL/Keycloak recreation). |
| `pocoma.v3.wakeup.url` | `nats://nats:4222` | Internal Core NATS broker URL |
| `pocoma.v3.wakeup.auth-token` | *(per-instance secret)* | Technical auth token matching `nats.conf` (`authorization { token: ... }`) |
| `pocoma.v3.wakeup.reconcile-poll-ms` | `2000` | Permanent slow reconciliation polling interval in `CONNECTED` state |
| `pocoma.v3.wakeup.fallback-max-poll-ms` | `20` | Automatic fast polling interval in `DEGRADED` state (broker unavailable) |
| `pocoma.v3.wakeup.consumption-max-poll-ms` | `500` | Consumption loop upper bound in `CONNECTED` (preserves retry/claim responsiveness) |
| `pocoma.v3.wakeup.coalesce-strategy` | `IN_FLIGHT_DEDUP` | Lock-free 32-bit bitmask coalescing (`IN_FLIGHT_DEDUP` or `WINDOWED`) |
| `pocoma.v3.wakeup.coalesce-window-ms` | `1` | Coalescing window when `WINDOWED` strategy is selected |
| `pocoma.v3.wakeup.connection-timeout-ms` | `2000` | Initial NATS TCP/auth connection timeout |
| `pocoma.v3.wakeup.reconnect-wait-ms` | `250` | Delay between `jnats` reconnect attempts |
| `pocoma.v3.wakeup.ping-interval-ms` / `max-pings-out` | `2000` / `2` | Heartbeat detection (`≤ 4 s` silent-partition detection) |
| `pocoma.v3.wakeup.connect-retry-delay-ms` | `500` | Background retry interval when broker is absent at startup |
| `pocoma.v3.wakeup.reconnect-buffer-bytes` | `65536` | Bounded reconnect buffer (64 KiB max) |

## Leases, Concurrency, and Effective Recovery Delays

Coordination parameters defined across `ProcessSettings`, `MaterializeSettings`, and `.properties` files:

| Parameter | Nominal Value | Unit / Role |
| :--- | :---: | :--- |
| `lease-duration-ms` | `30000` | SQL partition lease validity TTL (**30 s**) |
| `renewal-interval-ms` | `10000` | `renewBatch` periodic renewal cadence (**10 s**) |
| `claim-duration-ms` | `60000` | Slot claim duration (**60 s**) |
| `batch-size` | `32` | Maximum batch size |
| `retry-max-failures` | `3` | Maximum failures before deferral |
| `retry-delay-ms` | `30000` | Retry backoff delay (**30 s**) |
| `drain-timeout` | `90000` | Graceful shutdown drain timeout (**90 s**) |
| Worker polling (`min-poll` / `max-poll` / `error-poll`) | `2` / `20` / `50` | Polling intervals configured in local `.properties` (**in milliseconds**, parsed via `Duration.ofMillis(...)`) |

> [!IMPORTANT]
> **`30 s` = SQL lease TTL, not end-to-end recovery SLA**: reducing leases to `30000 ms` lowers maximum wait on an expired lease, but effective recovery after an ungraceful stop also depends on worker polling/acquisition cycles and `claim-duration-ms=60000` if a slot was claimed. Locally, `./pocoma-local` enforces a bounded recovery observation budget of **`270 s`**.

## Environment Variables, Internal Ports, and Infrastructure Containers

### Infrastructure Containers

| Service | Image | Docker Limits | Key Settings |
| :--- | :--- | :--- | :--- |
| `postgres` | `postgres:17.6-alpine` | `1024m` / `1.5` CPU | Databases `pocoma_write` (`v3_write`) and `pocoma_read` (`v3_read`); **no host port published** |
| `keycloak` | `quay.io/keycloak/keycloak:26.7.0` | `896m` / `1.5` CPU | JVM `-Xms128m -Xmx512m`, realm `docker/keycloak/pocoma-realm.json` |
| `nats` | `nats:2.15.3-alpine` | `128m` / `0.25` CPU | Core NATS wakeup broker (`docker/v3-five-runtimes/nats.conf`, `jetstream: false`, per-instance token, **no host port published**, no persistent volume) |
| `prometheus` | `prom/prometheus:v3.5.0` | `256m` / `0.5` CPU | `scrape_interval: 2s` on `:18080`–`:18085` |
| `grafana` | `grafana/grafana:12.3.2` | `384m` (`128m` `tmpfs`) / `0.5` CPU | UID `472`, read-only provisioning & dashboards |

### Shell & Maven Environment Restrictions

Per [`scripts/local/prerequisites.py`](../../scripts/local/prerequisites.py):

- **Automatically isolated shell variables**: `MAVEN_ARGS`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, `_JAVA_OPTIONS` (stripped automatically from `./pocoma-local` build and probe subprocesses).
- **Forbidden in `app/.mvn/`**: `maven.config`, `jvm.config`, `extensions.xml` (`PRE-UNREVIEWED-MAVEN-CONFIG`).
- **Checked versions (`scripts/local/toolchain.json`)**: Python ≥ `3.11`, Java `21`, Node ≥ `22` (preferred `24`), npm ≥ `7`, Bruno CLI `4.3.0`.

## Historical Nomenclature (Retained Technical Identifiers)

For stability (to avoid breaking external contracts, SQL schemas, or existing `demo` instance fingerprints), the following technical identifiers deliberately retain the historical `v3` marker:

| Category | Retained Identifiers | Reason |
| :--- | :--- | :--- |
| **HTTP contracts** | `/api/v3/...` routes | Public API contract exposed to clients and Bruno collections |
| **Persistent SQL identifiers** | `v3_write` / `v3_read` schemas, `pocoma_v3_lease_local`, `pocoma_v3_worker_state` tables | Active V1 SQL baselines and runtime/observability compatibility |
| **Spring profiles & Java properties** | `v3-http-admission`, `v3-http-read`, `v3-consumption-worker-dev`, `v3-read-worker`, application property prefixes | Loaded directly by `runtime-*` modules and targeted tests |
| **Tooling paths** | `docker/v3-five-runtimes/`, `scripts/v3/`, `scripts/operations/setup-v3-database.py`, `scripts/bruno-v3/` | Referenced directly in `runtime.py`, `prerequisites.py`, and tests |

Any renaming of these technical identifiers requires a dedicated step with coordinated updates across scripts, tests, and instance fingerprints.
