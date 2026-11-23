# Pocoma

[![Pocoma CI](https://github.com/kartaguez/pocoma/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kartaguez/pocoma/actions/workflows/ci.yml)

Pocoma is an asynchronous transactional platform built on the **CQRS** (*Command Query Responsibility Segregation*) pattern in Java 21 / Spring Boot and PostgreSQL 17. It strictly separates HTTP command admission, asynchronous command execution, read-projection materialization, and read-only HTTP queries, with **OIDC** authentication (Keycloak) and **Prometheus / Grafana** observability.

## Key Capabilities

- **Idempotent HTTP admission (`http-write`)**: exposes the `/api/v3` write API, validates OIDC tokens and application bindings (`Binding`), enforces idempotency, acknowledges requests with `202 Accepted`, and emits best-effort post-COMMIT Core NATS wakeup hints.
- **Partitioned asynchronous execution (`command-worker`)**: processes commands and identity requests using pure JDBC on PostgreSQL, with partition leases (`30 s`), batched renewal (`renewBatch`), strict fencing, and post-COMMIT wakeup hints.
- **T1A projection materialization (`critical-read-worker`, `pot-read-worker`)**: asynchronously projects critical low-latency views (`CommandResult`, `IdentityRequestResult`, `CurrentBinding` on `critical-read-worker`) isolated from pot/invitation views (`PotVersion`, `PotBalance`, `PotVisibility`, `Invitation` on `pot-read-worker`) into `pocoma_read`.
- **Core NATS event-driven wakeup with permanent polling fallback**: volatile, zero-payload wakeup hints (`pocoma.v3.wakeup.<family>.<partition>`) accelerate discovery while PostgreSQL remains the sole authority and periodic polling (`reconcile-poll=2 000 ms`, `DEGRADED` fallback `20 ms`, autonomous `NOOP` mode) guarantees completeness even on 100 % notification loss.
- **Protected HTTP reads (`http-read`)**: validates requests and OIDC tokens **before** acquiring concurrency permits (`Q=8`), then serves materialized views with opaque privacy (`404`).
- **Security and observability**: OIDC authentication (Authorization Code + PKCE S256 via Keycloak), internal-only Core NATS with per-instance auth token (no public port, no JetStream), isolated local secrets redacted from logs, and Actuator metrics scraped every `2 s` by Prometheus for Grafana.

## Architecture at a Glance

The local orchestrator [`./pocoma-local`](docs/reference/cli.md) deploys **10 containers** by default (5 JVM runtimes in **T1A** topology and 5 infrastructure services including Core NATS; or 9 containers in `--wakeup-mode noop`):

```text
Client / Bruno ──(OIDC PKCE S256)──▶ Keycloak (26.7.0)
      │
      ├── HTTP write (/api/v3) ──▶ http-write (:8080 internal) ──▶ PostgreSQL WRITE (pocoma_write)
      │                                  │ (post-COMMIT hint)                ▲
      │                                  ▼                                   │
      │                           Core NATS (2.15.3) ◀──── command-worker ───┘ (8 partitions)
      │                            (internal only,                           │
      │                             volatile hints)                          │
      │                             ├──▶ critical-read-worker (12 parts) ◀───┤ (source WRITE → READ)
      │                             └──▶ pot-read-worker (16 parts)      ◀───┘
      │                                                                      ▼
      └── HTTP read  (/api/v3) ──▶ http-read  (:8082 internal) ──▶ PostgreSQL READ  (pocoma_read)

Actuator metrics (:18080–:18085) ──▶ Prometheus (v3.5.0, 2 s) ──▶ Grafana (12.3.2)
```

## Essential Prerequisites

Automatically verified by `./pocoma-local` ([`scripts/local/toolchain.json`](scripts/local/toolchain.json)):

- **Docker Engine / Docker Desktop** running (≥ 8 GB RAM recommended for the 10 containers)
- **Python ≥ 3.11**
- **JDK 21** (`java` 21)
- **Node ≥ 22** and **npm ≥ 7** (for Bruno CLI `4.3.0`)
- Clean Maven wrapper config: no `maven.config` / `jvm.config` / `extensions.xml` files under `app/.mvn/` (global `MAVEN_ARGS`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, and `_JAVA_OPTIONS` shell variables are automatically isolated during builds)

For full details and the complete checklist: [Installation](docs/getting-started/installation.md).

## Quick Start

Create, start, and verify the default local instance (`demo`):

```bash
./pocoma-local start --instance demo
./pocoma-local status --instance demo
```

Run the automated end-to-end journeys or open Bruno with OIDC authentication:

```bash
./pocoma-local bruno --instance demo
./pocoma-local gui --instance demo
```

Stop the instance cleanly while preserving all data:

```bash
./pocoma-local stop --instance demo
```

To run your first end-to-end API call: [First Steps](docs/getting-started/first-steps.md).

## Documentation

### Recommended 30-Minute Onboarding Path

New to Pocoma? Read these six short documents in order (no historical build reports required):

1. **[Architecture Overview](docs/architecture/overview.md)** — domain concepts, WRITE/READ separation, Core NATS wakeup, and the 10-container T1A topology.
2. **[Installation & Prerequisites](docs/getting-started/installation.md)** — workstation check (`./pocoma-local doctor`), startup, and cleanup.
3. **[First Steps](docs/getting-started/first-steps.md)** — end-to-end HTTP walkthrough: OIDC login → `Registration` → `CreatePot` → command result → READ projection.
4. **[Maven Modules (43 modules)](docs/architecture/modules.md)** — Domain / Contract / Port / Engine / Supra / Infra / Compose / Runtime boundaries and where to place code.
5. **[JVM Runtimes & Concurrency](docs/architecture/runtimes.md)** & **[PostgreSQL Persistence](docs/architecture/persistence.md)** — the 5 T1A runtimes, 36 partitions, Core NATS wakeup + permanent polling, SQL leases, fencing, and `pocoma_write` / `pocoma_read` isolation.
6. **[CONTRIBUTING.md](CONTRIBUTING.md)** & **[Local Development & Bruno](docs/guides/local-development.md)** — daily inner loop, Bruno playground, and targeted Maven slice verification.

| Topic | Entry Point |
| :--- | :--- |
| Documentation portal | [docs/README.md](docs/README.md) |
| Installation & prerequisites | [docs/getting-started/installation.md](docs/getting-started/installation.md) |
| First steps (auth → command → read) | [docs/getting-started/first-steps.md](docs/getting-started/first-steps.md) |
| Practical guides (auth, commands, queries, local dev) | [docs/guides/README.md](docs/guides/README.md) |
| Architecture (CQRS, 43 modules, T1A + NATS, runtimes, persistence, security) | [docs/architecture/overview.md](docs/architecture/overview.md) |
| Reference (CLI, configuration, `/api/v3` API) | [docs/reference/README.md](docs/reference/README.md) |
| Operations (troubleshooting, crash recovery) | [docs/operations/README.md](docs/operations/README.md) |

## Qualification Scope & Platform Status

- **Current reference status**: **`GO_LOCAL_WITH_RESERVES`** (L7.5) — functional, security, architectural, and PostgreSQL 17 transactional compliance are certified locally (`1 222/1 222` Maven reactor tests `PASS`, `52/52` `architecture-tests` `PASS`, `143/143` Python CLI tests `PASS`, Bruno E2E `118/118` `PASS`). Multi-node HA, TLS/mTLS, `SPEC-09` dedicated Config A/B infrastructure, `50 cmd/s` sustained peak, and `10k/1M`–`100k/10M` global volumes remain explicitly deferred to L8 / target production infrastructure. See [L7.5 Final Closure Dossier](docs/v3/L7_5_Final_Closure_Dossier.md) (and the historical L7 baseline in [L7 Final Global Qualification Dossier](docs/v3/L7_Final_Global_Qualification_Dossier.md)).
- **Verified host platforms**: local `./pocoma-local` installation and runtime orchestration have been verified on **macOS (Apple Silicon / ARM64)** with Docker Desktop. Automated CI runs the Maven reactor and Testcontainers on **Linux (`ubuntu-24.04`)**. Local `./pocoma-local` usage on Linux and Windows (WSL2) has not been independently qualified yet.

## Contributing, Security, and License

Focused, well-tested contributions that respect the documented module boundaries are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) and follow the [Code of Conduct](CODE_OF_CONDUCT.md).

Please do not publicly disclose an exploitable vulnerability: follow the private reporting instructions in [SECURITY.md](SECURITY.md).

Pocoma is licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution details.
