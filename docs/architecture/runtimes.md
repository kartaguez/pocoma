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

## Consumption & Materialization Workers (T1A Topology)

| Worker | Maven Module | Spring Profile | Actuator | Concurrency (`worker-limit`) | Scope |
| :--- | :--- | :--- | :---: | :---: | :--- |
| `command-worker` | `runtime-worker-process` | `v3-consumption-worker-dev` | `18083` | `4` (`Command=3`, `IdentityRequest=2`) | **Pure JDBC (no JPA/Hibernate)**; business execution on `pocoma_write` + post-COMMIT NATS publisher |
| `critical-read-worker` | `runtime-worker-materialize` | `v3-read-worker` | `18084` | `4` (`MaterializeCommandResult=2`, `MaterializeIdentityRequestResult=2`, `MaterializeCurrentBinding=2`) | Critical low-latency projections (`CommandResult`, `IdentityRequestResult`, `CurrentBinding`) in `pocoma_read` |
| `pot-read-worker` | `runtime-worker-materialize` | `v3-read-worker` | `18085` | `4` (`MaterializePotVersion=2`, `MaterializePotBalance=2`, `MaterializePotVisibility=2`, `MaterializeInvitation=2`) | Pot & Invitation projections in `pocoma_read`, isolated from critical READ projections |

Removing JPA/Hibernate completely from `command-worker` (`ProcessComposeConfiguration`, `ProcessSettings`) reduces its memory footprint and aligns its data access on pure JDBC, matching the lease-coordination layer. Moving `CurrentBinding` onto `critical-read-worker` (topology `T1A`) prevents Pot backlogs from starving identity registration and binding availability.

## Partitioning (36 Partitions) & Recovery Policy

The L2 routing table initialized by [`scripts/v3/bootstrap-l2-routing.sql`](../../scripts/v3/bootstrap-l2-routing.sql) distributes work across **36 partitions** (`0` to `3` per participant):

- **`command-worker` (8 WRITE partitions)**: `Command` (0–3), `IdentityRequest` (0–3).
- **`critical-read-worker` (12 READ partitions)**: `MaterializeCommandResult` (0–3), `MaterializeIdentityRequestResult` (0–3), `MaterializeCurrentBinding` (0–3).
- **`pot-read-worker` (16 READ partitions)**: `MaterializePotVersion` (0–3), `MaterializePotBalance` (0–3), `MaterializePotVisibility` (0–3), `MaterializeInvitation` (0–3).

Source selection and recovery rules were extracted from `ConsumptionEngine` into a dedicated **`RecoverySourcePolicy`** component to decouple recovery strategy from the partition execution loop.

## Core NATS Event-Driven Wakeup & Permanent Polling

Workers combine **Core NATS volatile wakeup notifications** (`io.nats:jnats`, no JetStream, no persistence) with **permanent PostgreSQL polling**:

- **Logical contract**: `WakeupKey(ConsumableFamily, partition)` mapped to subject `pocoma.v3.wakeup.<family>.<partition>` (max `7 × 4 = 28` subjects, **0-byte empty payload**, zero business data).
- **Post-COMMIT emission**: `http-write` emits after `CommandAdmission.admit()` / `IdentityRequestAdmission.admit()` returns; `command-worker` emits through `SpringAttemptTransactions.afterCommit(...)`. Any broker failure or saturation is swallowed and can never abort a PostgreSQL commit or alter an HTTP `202 Accepted` response.
- **Bounded coalescing (`CoalescingWakeupPublisher`)**: lock-free 32-bit `AtomicInteger` bitmask (`IN_FLIGHT_DEDUP` by default, 4 bytes bounded memory) deduplicates concurrent bursts without blocking business threads.
- **Latched waiter & cache invalidation**: `ConditionConsumptionWaiter` latches signals received before `await()` (preventing signal/wait races), and `DiscoveryLoop` clears its per-family empty-checkpoint cache on wakeup or reconnect, while ignoring wakeups for partitions without a local lease (`pocoma.v3.wakeup.ignored.no_lease`).
- **Three operating modes (`pocoma.v3.wakeup.state`)**:
  - **`CONNECTED` (`1`)**: Core NATS wakeup active + permanent slow reconciliation polling (`reconcile-poll-ms=2000`, `consumption-max-poll-ms=500`).
  - **`DEGRADED` (`2`)**: Broker unavailable at startup or disconnected → automatic fallback to fast polling (`fallback-max-poll-ms=20`); reconnection triggers an immediate full reconciliation (`onExternalReconnect`).
  - **`NOOP` (`0`)**: Autonomous polling-only mode (`max-poll=20 ms`), no broker connection required.

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

HikariCP pools for the three T1A workers are explicitly sized in `docker/v3-five-runtimes/*.properties`:

- **`command-worker`**: `write-business=6`, `write-control=10`.
- **`critical-read-worker`**: `write-source-read=6`, `read-business=6`, `read-control=10`.
- **`pot-read-worker`**: `write-source-read=6`, `read-business=6`, `read-control=10`.

This separation ensures a burst of business processing never starves control-plane lease renewals (`*-control`) or upstream HTTP validation.
