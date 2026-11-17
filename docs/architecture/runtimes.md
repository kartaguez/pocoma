# JVM Runtimes & Concurrency

Pocoma's five Java services run on `eclipse-temurin:21.0.8_9-jre` in containers capped at `640m` memory and `1` CPU, with JVM options `-Xms64m -Xmx384m -XX:+UseSerialGC`.

## HTTP Runtimes (WRITE Admission & READ Queries)

### `http-write` (`runtime-web-write`)

- **Spring profile**: `v3-http-admission` (`docker/v3-five-runtimes/http-write.properties`)
- **Internal ports**: HTTP `8080`, Actuator `18080`
- **Concurrency & pool**: Tomcat `maxThreads=4`, `pocoma.v3.http.admission.capacity=4` (sizes both the `AdmissionTransport` semaphore and the `v3-http-admission` Hikari pool to `maximumPoolSize=4` / `minimumIdle=4`).
- **Responsibility**: hosts `AdmissionHttpConfiguration`, validates OIDC authentication, checks idempotency, and admits commands into `pocoma_write`.

### `http-read` (`runtime-web-read`)

- **Spring profile**: `v3-http-read` (`docker/v3-five-runtimes/http-read.properties`)
- **Internal ports**: HTTP `8082`, Actuator `18082`
- **Pre-semaphore validation & `Q=8` concurrency**:
  - `ReadHttpConfiguration`, `ReadFacadeComposition`, and `ReadTransport` validate the HTTP request and tokens **before** acquiring a concurrency permit.
  - READ concurrency limits are aligned to **`Q=8`**: `http-permits=8`, `sql-permits=8`, Tomcat `maxThreads=4`, main JDBC pool `maximum-pool-size=8` / `minimum-idle=8` (plus a maintenance pool of size `1`).
- **SQL optimization**: the `openComponent` query (pot history in `PotHistoryRows`) was optimized during consolidation to bound read cost.

## Consumption & Materialization Workers

| Worker | Maven Module | Spring Profile | Actuator | Concurrency (`worker-limit`) | Scope |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `command-worker` | `runtime-worker-process` | `v3-consumption-worker-dev` | `18083` | `2` (`Command=2`, `IdentityRequest=2`) | **Pure JDBC (no JPA/Hibernate)**; business execution on `pocoma_write` |
| `results-worker` | `runtime-worker-materialize` | `v3-read-worker` | `18084` | `2` (`MaterializeCommandResult=2`, `MaterializeIdentityRequestResult=2`) | Terminal result materialization to `pocoma_read` |
| `business-read-worker` | `runtime-worker-materialize` | `v3-read-worker` | `18085` | `3` (5 participants at `2` each) | Business view materialization in `pocoma_read` |

Removing JPA/Hibernate completely from `command-worker` (`ProcessComposeConfiguration`, `ProcessSettings`) reduces its memory footprint and aligns its data access on pure JDBC, matching the lease-coordination layer.

## Partitioning (36 Partitions) & Recovery Policy

The L2 routing table initialized by [`scripts/v3/bootstrap-l2-routing.sql`](../../scripts/v3/bootstrap-l2-routing.sql) distributes work across **36 partitions** (`0` to `3` per participant):

- **`command-worker` (8 WRITE partitions)**: `Command` (0–3), `IdentityRequest` (0–3).
- **`results-worker` (8 READ partitions)**: `MaterializeCommandResult` (0–3), `MaterializeIdentityRequestResult` (0–3).
- **`business-read-worker` (20 READ partitions)**: `MaterializePotVersion` (0–3), `MaterializePotBalance` (0–3), `MaterializePotVisibility` (0–3), `MaterializeCurrentBinding` (0–3), `MaterializeInvitation` (0–3).

Source selection and recovery rules were extracted from `ConsumptionEngine` into a dedicated **`RecoverySourcePolicy`** component to decouple recovery strategy from the partition execution loop.

## SQL Leases (30 s), Batched Renewal (`renewBatch`), and Recovery

Workers rely on database-backed leases (`pocoma_v3_lease_local`, `pocoma_v3_worker_state`) with the following default parameters:

| Parameter | Nominal Value | Meaning |
| :--- | :---: | :--- |
| `lease-duration-ms` | `30000` (30 s) | Partition lease validity (TTL) |
| `renewal-interval-ms` | `10000` (10 s) | Batched renewal cadence |
| `claim-duration-ms` | `60000` (60 s) | Slot claim duration |
| `batch-size` | `32` | Maximum batch size processed |
| `retry-max-failures` | `3` | Maximum retries before deferral |
| `retry-delay-ms` | `30000` (30 s) | Retry backoff delay |
| `drain-timeout` | `90000` (90 s) | Graceful shutdown drain timeout |
| Worker polling (`min-poll` / `max-poll` / `error-poll`) | `2` / `20` / `50` | Polling intervals in **milliseconds** (`Duration.ofMillis(...)`) configured in local `.properties` |

### How `renewBatch` Works

Batched renewal (`renewBatch` across `PartitionOwnershipStore`, `JdbcPartitionOwnershipStore`, `ConsumptionProtocol`, `AbstractConsumptionProtocol`, `ConsumptionEngine`, and `RenewalService`) enforces **strict fencing**:

- Only leases still owned by the calling worker have their deadline extended.
- An expired lease already claimed by another worker is never overwritten or modified.
- Lost leases are returned and identified **individually**, so the worker immediately removes those partitions from its active set.

> [!IMPORTANT]
> A **30 s** lease TTL (`lease-duration-ms=30000`) does not mean full end-to-end crash recovery always completes in 30 s. Effective recovery combines lease expiration (`30 s`), worker polling/acquisition cycles, and `claim-duration-ms=60000` if a slot was claimed. After an ungraceful stop, `pocoma-local` applies a bounded observation budget of **270 s**. See [Crash Recovery & Lifecycle](../operations/recovery.md) and [Runtime Configuration](../reference/configuration.md).

## JDBC Pool & Memory Sizing

HikariCP pools for the three workers are explicitly sized in `docker/v3-five-runtimes/*.properties`:

- **`command-worker`**: `write-business=4`, `write-control=8`.
- **`results-worker`**: `write-source-read=4`, `read-business=4`, `read-control=4`.
- **`business-read-worker`**: `write-source-read=5`, `read-business=5`, plus a dedicated `read-control` pool.

This separation ensures a burst of business processing never starves control-plane lease renewals (`*-control`) or upstream HTTP validation.
