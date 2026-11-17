# Pocoma

[![Pocoma CI](https://github.com/kartaguez/pocoma/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/kartaguez/pocoma/actions/workflows/ci.yml)

Pocoma is an asynchronous transactional platform built on the **CQRS** (*Command Query Responsibility Segregation*) pattern in Java 21 / Spring Boot and PostgreSQL 17. It strictly separates HTTP command admission, asynchronous command execution, read-projection materialization, and read-only HTTP queries, with **OIDC** authentication (Keycloak) and **Prometheus / Grafana** observability.

## Key Capabilities

- **Idempotent HTTP admission (`http-write`)**: exposes the `/api/v3` write API, validates OIDC tokens and application bindings (`Binding`), enforces idempotency, and acknowledges requests with `202 Accepted`.
- **Partitioned asynchronous execution (`command-worker`)**: processes commands and identity requests using pure JDBC on PostgreSQL, with partition leases (`30 s`), batched renewal (`renewBatch`), and strict fencing.
- **Projection materialization (`results-worker`, `business-read-worker`)**: asynchronously projects terminal results and business views (pots, shareholders, expenses, exact rational balances, invitations) into the read database.
- **Protected HTTP reads (`http-read`)**: validates requests and OIDC tokens **before** acquiring concurrency permits (`Q=8`), then serves materialized views with opaque privacy (`404`).
- **Security and observability**: OIDC authentication (Authorization Code + PKCE S256 via Keycloak), isolated local secrets redacted from logs, and Actuator metrics scraped every `2 s` by Prometheus for Grafana.

## Architecture at a Glance

The local orchestrator [`./pocoma-local`](docs/reference/cli.md) deploys **9 containers** (5 JVM runtimes and 4 infrastructure services):

```text
Client / Bruno ──(OIDC PKCE S256)──▶ Keycloak (26.7.0)
      │
      ├── HTTP write (/api/v3) ──▶ http-write (:8080 internal) ──▶ PostgreSQL WRITE (pocoma_write)
      │                                                                      ▲
      │                                                     command-worker ──┘ (8 partitions)
      │                                                                      │
      │                             results-worker (8 partitions) ◀──────────┤ (source WRITE → READ)
      │                             business-read-worker (20 partitions) ◀───┘
      │                                                                      ▼
      └── HTTP read  (/api/v3) ──▶ http-read  (:8082 internal) ──▶ PostgreSQL READ  (pocoma_read)

Actuator metrics (:18080–:18085) ──▶ Prometheus (v3.5.0, 2 s) ──▶ Grafana (12.3.2)
```

## Essential Prerequisites

Automatically verified by `./pocoma-local` ([`scripts/local/toolchain.json`](scripts/local/toolchain.json)):

- **Docker Engine / Docker Desktop** running (≥ 8 GB RAM recommended for the 9 containers)
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

| Topic | Entry Point |
| :--- | :--- |
| Documentation portal | [docs/README.md](docs/README.md) |
| Installation & prerequisites | [docs/getting-started/installation.md](docs/getting-started/installation.md) |
| First steps (auth → command → read) | [docs/getting-started/first-steps.md](docs/getting-started/first-steps.md) |
| Practical guides (auth, commands, queries, local dev) | [docs/guides/README.md](docs/guides/README.md) |
| Architecture (CQRS, 42 modules, runtimes, persistence, security) | [docs/architecture/overview.md](docs/architecture/overview.md) |
| Reference (CLI, configuration, `/api/v3` API) | [docs/reference/README.md](docs/reference/README.md) |
| Operations (troubleshooting, crash recovery) | [docs/operations/README.md](docs/operations/README.md) |

## Contributing, Security, and License

Focused, well-tested contributions that respect the documented module boundaries are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) and follow the [Code of Conduct](CODE_OF_CONDUCT.md).

Please do not publicly disclose an exploitable vulnerability: follow the private reporting instructions in [SECURITY.md](SECURITY.md).

Pocoma is licensed under the [Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attribution details.
