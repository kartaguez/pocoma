# Architecture Overview

Pocoma is an asynchronous transactional platform built on the **CQRS** (*Command Query Responsibility Segregation*) pattern: it strictly separates write-intent admission, business execution, read-view materialization, and read-only HTTP queries.

## 1. Domain Concepts

Pocoma manages two primary domains:

- **Identity & Binding (`User`, `ExternalIdentity`, `Binding`)**: an external identity authenticated via OIDC (Keycloak) is registered (`Registration`) to create an internal user and an application binding token (`bindingToken`). A user can unlink (`Unlink`), revoking their current `bindingToken` while preserving historical command traceability.
- **Pot (`Pot`, `Shareholder`, `Expense`, `Invitation`)**: a versioned pot groups participants (`Shareholder` with rational weights), expenses (`Expense` with zero floating-point math, computed in exact rationals), and balances whose sum is always zero. Access to a pot is governed by active or historical membership and private invitations (`pocoma://invitation/<secret>`).

## 2. WRITE / READ Separation & Asynchronous Flow

Processing is split into four stages executed by distinct processes:

1. **HTTP Admission (`http-write`)**: validates the OIDC token, `Idempotency-Key` header, and `bindingToken`, persists the admission in `pocoma_write`, and immediately responds `202 Accepted` with a `requestId`.
2. **Asynchronous Business Execution (`command-worker`)**: acquires leases on 8 WRITE partitions (`Command` and `IdentityRequest`), executes transactional logic in pure JDBC (no JPA/Hibernate), and records decisions and results in `pocoma_write`.
3. **Projection Materialization (`results-worker` and `business-read-worker`)**: reads events and terminal results from `pocoma_write` (via a read-only `pocoma_write_source_read` role) and updates materialized views in `pocoma_read` across 28 dedicated partitions.
4. **HTTP Read Exposure (`http-read`)**: validates the HTTP request and OIDC token **before** acquiring a concurrency permit (`Q=8`), then queries `pocoma_read` exclusively.

```text
                        ┌────────────────────────────────────┐
                        │        Keycloak 26.7.0             │
                        │    OIDC Auth Code + PKCE S256      │
                        └─────────────────▲──────────────────┘
                                          │ JWT validation
        POST / commands (/api/v3)         │         GET / queries (/api/v3)
      ┌───────────────────────────────────┴───────────────────────────────────┐
      ▼                                                                       ▼
┌───────────┐                                                           ┌───────────┐
│http-write │                                                           │ http-read │
│ :8080     │                                                           │  :8082    │
└─────┬─────┘                                                           └─────┬─────┘
      │ SQL write                                                    SQL read │ (Q=8)
      ▼                                                                       ▼
┌───────────────────────────┐    source read      ┌───────────────────────────┐
│  PostgreSQL WRITE         │ ──────────────────▶ │  PostgreSQL READ          │
│  db: pocoma_write         │  results-worker (8) │  db: pocoma_read          │
│  schema: v3_write         │  business-read (20) │  schema: v3_read          │
└─────────────▲─────────────┘                     └─────────────▲─────────────┘
              │ 30s leases + renewBatch                         │ 30s leases + renewBatch
        ┌─────┴──────────┐                         ┌────────────┴──────────────┐
        │ command-worker │                         │ results-worker            │
        │  8 partitions  │                         │ business-read-worker      │
        └────────────────┘                         └───────────────────────────┘
```

## 3. The Five Runtimes & Local Topology (9 Containers)

The [`./pocoma-local`](../reference/cli.md) orchestrator deploys **9 isolated Docker containers** per instance:

| Role | Container | Image / Module | Primary Responsibility |
| :--- | :--- | :--- | :--- |
| Database | `postgres` | `postgres:17.6-alpine` | Hosts `pocoma_write` and `pocoma_read` (no host port published) |
| Identity | `keycloak` | `quay.io/keycloak/keycloak:26.7.0` | Local OIDC PKCE S256 realm and test accounts |
| Metrics | `prometheus` | `prom/prometheus:v3.5.0` | Scrapes `/actuator/prometheus` every `2 s` |
| Dashboards | `grafana` | `grafana/grafana:12.3.2` | 2 read-only provisioned dashboards |
| HTTP WRITE | `http-write` | `runtime-web-write` (JDK 21) | Command and identity request admission (`/api/v3`) |
| HTTP READ | `http-read` | `runtime-web-read` (JDK 21) | Read-only HTTP queries (`/api/v3`) |
| WRITE Worker | `command-worker` | `runtime-worker-process` (JDK 21) | Command execution in pure JDBC (no JPA/Hibernate) |
| READ Results Worker | `results-worker` | `runtime-worker-materialize` (JDK 21) | `CommandResult` and `IdentityRequestResult` projections |
| READ Business Worker | `business-read-worker` | `runtime-worker-materialize` (JDK 21) | `PotVersion`, `PotBalance`, `PotVisibility`, `CurrentBinding`, `Invitation` projections |

## 4. Partitions, Leases, Fencing, and Security

- **Partitioning and leases**: asynchronous work is distributed across **36 partitions** (`8` WRITE, `8` results, `20` business projections). Each partition is protected by a SQL lease (`30 s`), renewed in batches (`renewBatch` every `10 s`) with **strict fencing**: a worker that loses a lease immediately removes the partition from its active set and never overwrites a new owner.
- **Security**: Bearer JWT authentication (Keycloak), mandatory `Binding`, HMAC idempotency at admission, pre-semaphore validation on `http-read`, and opaque privacy (`404` non-discriminating responses).

To explore each layer further:
- [Maven Modules (42 modules)](modules.md)
- [JVM Runtimes & Concurrency](runtimes.md)
- [PostgreSQL Persistence](persistence.md)
- [Security](security.md)
- [Public API `/api/v3` Reference](../reference/api.md)
