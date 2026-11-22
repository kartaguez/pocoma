# Architecture Overview

Pocoma is an asynchronous transactional platform built on the **CQRS** (*Command Query Responsibility Segregation*) pattern: it strictly separates write-intent admission, business execution, read-view materialization, and read-only HTTP queries.

## 1. Domain Concepts

Pocoma manages two primary domains:

- **Identity & Binding (`User`, `ExternalIdentity`, `Binding`)**: an external identity authenticated via OIDC (Keycloak) is registered (`Registration`) to create an internal user and an application binding token (`bindingToken`). A user can unlink (`Unlink`), revoking their current `bindingToken` while preserving historical command traceability.
- **Pot (`Pot`, `Shareholder`, `Expense`, `Invitation`)**: a versioned pot groups participants (`Shareholder` with rational weights), expenses (`Expense` with zero floating-point math, computed in exact rationals), and balances whose sum is always zero. Access to a pot is governed by active or historical membership and private invitations (`pocoma://invitation/<secret>`).

## 2. WRITE / READ Separation & Asynchronous Flow

Processing is split into four stages executed by distinct processes:

1. **HTTP Admission (`http-write`)**: validates the OIDC token, `Idempotency-Key` header, and `bindingToken`, persists the admission in `pocoma_write`, immediately responds `202 Accepted` with a `requestId`, and emits a best-effort post-COMMIT Core NATS wakeup hint (`pocoma.v3.wakeup.<family>.<partition>`, 0-byte payload).
2. **Asynchronous Business Execution (`command-worker`)**: acquires leases on 8 WRITE partitions (`Command` and `IdentityRequest`), executes transactional logic in pure JDBC (no JPA/Hibernate), records decisions and results in `pocoma_write`, and emits post-COMMIT Core NATS wakeup hints via `SpringAttemptTransactions.afterCommit`.
3. **T1A Projection Materialization (`critical-read-worker` and `pot-read-worker`)**: reads events and terminal results from `pocoma_write` (via a read-only `pocoma_write_source_read` role) and updates materialized views in `pocoma_read` across 28 dedicated partitions, isolating critical low-latency projections (`CommandResult`, `IdentityRequestResult`, `CurrentBinding` on `critical-read-worker`) from Pot/Invitation projections (`PotVersion`, `PotBalance`, `PotVisibility`, `Invitation` on `pot-read-worker`).
4. **HTTP Read Exposure (`http-read`)**: validates the HTTP request and OIDC token **before** acquiring a concurrency permit (`Q=8`), then queries `pocoma_read` exclusively.

> [!IMPORTANT]
> **PostgreSQL remains the sole authority** on work, slots, checkpoints, claims, leases, fencing, and effects. Core NATS carries only volatile, zero-payload wakeup hints (no JetStream, no persistence, no business data). Periodic polling stays permanently active (`reconcile-poll=2 000 ms` in `CONNECTED`, automatic `fallback-max-poll=20 ms` in `DEGRADED`, or autonomous `NOOP` mode without broker), so any lost notification or broker outage is transparently compensated.

```text
                        ┌────────────────────────────────────┐
                        │        Keycloak 26.7.0             │
                        │    OIDC Auth Code + PKCE S256      │
                        └─────────────────▲──────────────────┘
                                          │ JWT validation
        POST / commands (/api/v3)         │         GET / queries (/api/v3)
      ┌───────────────────────────────────┴───────────────────────────────────┐
      ▼                                                                       ▼
┌───────────┐   post-COMMIT hint    ┌──────────────────┐                ┌───────────┐
│http-write │ ────────────────────▶ │ Core NATS 2.15.3 │                │ http-read │
│ :8080     │                       │ (internal only)  │                │  :8082    │
└─────┬─────┘                       └────┬───────┬─────┘                └─────┬─────┘
      │ SQL write              wakeup    │       │ wakeup            SQL read │ (Q=8)
      ▼                        hints     │       │ hints                      ▼
┌───────────────────────────┐            │       │  ┌───────────────────────────┐
│  PostgreSQL WRITE         │ ───────────┼───────┼▶ │  PostgreSQL READ          │
│  db: pocoma_write         │  critical-read (12)│  │  db: pocoma_read          │
│  schema: v3_write         │  pot-read (16)     │  │  schema: v3_read          │
└─────────────▲─────────────┘            │       │  └─────────────▲─────────────┘
              │ 30s leases + renewBatch  ▼       ▼                │ 30s leases + renewBatch
        ┌─────┴──────────┐ ◀─────────────┘       └─▶ ┌────────────┴──────────────┐
        │ command-worker │ (post-COMMIT hint → NATS) │ critical-read-worker (12) │
        │  8 partitions  │                           │ pot-read-worker (16)      │
        └────────────────┘                           └───────────────────────────┘
```

## 3. The Five Runtimes & Local T1A + Core NATS Topology (10 Containers)

The [`./pocoma-local`](../reference/cli.md) orchestrator deploys **10 isolated Docker containers** per instance by default (or 9 containers in `--wakeup-mode noop`):

| Role | Container | Image / Module | Primary Responsibility |
| :--- | :--- | :--- | :--- |
| Database | `postgres` | `postgres:17.6-alpine` | Hosts `pocoma_write` and `pocoma_read` (no host port published) |
| Identity | `keycloak` | `quay.io/keycloak/keycloak:26.7.0` | Local OIDC PKCE S256 realm and test accounts |
| Wakeup Broker | `nats` | `nats:2.15.3-alpine` | Core NATS volatile wakeup hints (internal network only, `jetstream: false`, per-instance auth token) |
| Metrics | `prometheus` | `prom/prometheus:v3.5.0` | Scrapes `/actuator/prometheus` every `2 s` |
| Dashboards | `grafana` | `grafana/grafana:12.3.2` | 2 read-only provisioned dashboards (including Core NATS wakeup panels) |
| HTTP WRITE | `http-write` | `runtime-web-write` (JDK 21) | Command and identity request admission (`/api/v3`) + post-COMMIT wakeup publisher |
| HTTP READ | `http-read` | `runtime-web-read` (JDK 21) | Read-only HTTP queries (`/api/v3`) |
| WRITE Worker | `command-worker` | `runtime-worker-process` (JDK 21) | Command & IdentityRequest execution in pure JDBC + wakeup subscriber/publisher |
| Critical READ Worker (T1A) | `critical-read-worker` | `runtime-worker-materialize` (JDK 21) | `CommandResult`, `IdentityRequestResult`, and `CurrentBinding` projections (12 partitions) |
| Pot READ Worker (T1A) | `pot-read-worker` | `runtime-worker-materialize` (JDK 21) | `PotVersion`, `PotBalance`, `PotVisibility`, and `Invitation` projections (16 partitions) |

> [!NOTE]
> The historical `T0` topology (`results-worker` + `business-read-worker`) remains documented as a historical baseline for `v3.0.0-l7-local` (`afec69f8`). `T1A` is now the target local reference topology.

## 4. Partitions, Leases, Fencing, and Security

- **Partitioning and leases**: asynchronous work is distributed across **36 partitions** (`8` WRITE, `12` critical READ, `16` pot/invitation READ). Each partition is protected by a SQL lease (`30 s`), renewed in batches (`renewBatch` every `10 s`) with **strict fencing**: a worker that loses a lease immediately removes the partition from its active set and never overwrites a new owner.
- **Security**: Bearer JWT authentication (Keycloak), mandatory `Binding`, HMAC idempotency at admission, pre-semaphore validation on `http-read`, opaque privacy (`404` non-discriminating responses), and internal-only token-authenticated Core NATS.

To explore each layer further:
- [Maven Modules (43 modules)](modules.md)
- [JVM Runtimes & Concurrency](runtimes.md)
- [PostgreSQL Persistence](persistence.md)
- [Security](security.md)
- [Public API `/api/v3` Reference](../reference/api.md)
