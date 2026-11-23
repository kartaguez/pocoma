# Pocoma Documentation

Welcome to the **Pocoma** product documentation. It covers how to install, understand, use, and operate Pocoma locally without prior knowledge of its build history.

> [!TIP]
> **New to Pocoma?** Follow the short onboarding path: [Architecture Overview](architecture/overview.md) → [Installation](getting-started/installation.md) → [First Steps](getting-started/first-steps.md) → [Maven Modules](architecture/modules.md) → [JVM Runtimes](architecture/runtimes.md) → [PostgreSQL Persistence](architecture/persistence.md) → [Local Development](guides/local-development.md). Historical build reports and L7 certification evidence live in [Internal Documentation](internal/README.md) and are **not** required to get started.

## Getting Started

- [Getting Started Overview](getting-started/README.md)
- [Installation & Prerequisites](getting-started/installation.md) — toolchain, Docker resources, initial startup, `READY` check, stopping and destroying instances.
- [First Steps](getting-started/first-steps.md) — self-contained walkthrough: start, authenticate, submit a command, poll its result, and query the projection.

## Practical Guides

- [Guides Index](guides/README.md)
- [Authentication & Binding](guides/authentication.md) — OIDC PKCE S256 via Keycloak, `Registration`, `Unlink`, and `bindingToken`.
- [Commands & Asynchronous Results](guides/commands.md) — `202 Accepted` admission, `Idempotency-Key`, result polling, and business payloads.
- [READ Queries & Projections](guides/queries.md) — `minVersion` reads, exact history, balances, pagination, and opaque privacy (`404`).
- [Local Development & Bruno](guides/local-development.md) — `--instance` management, Bruno GUI/CLI, Grafana/Prometheus, Java inner loop, and Maven slice workflow.
- [Adding a READ Projection](guides/adding-read-projections.md) — family identity, transactional publication, tests and reconstruction.

## Architecture

- [Architecture Index](architecture/README.md)
- [Overview](architecture/overview.md) — domain concepts, WRITE/READ separation, async flow, and 9-container topology.
- [Maven Modules (43 modules)](architecture/modules.md) — layer taxonomy, dependency rules, and where to place new code.
- [JVM Runtimes & Concurrency](architecture/runtimes.md) — the 5 runtimes, 36 partitions, SQL leases (`30 s`), `renewBatch`, and fencing.
- [PostgreSQL Persistence](architecture/persistence.md) — `pocoma_write` / `pocoma_read` databases, V1 baselines, and coordination tables.
- [READ Materialization Availability](architecture/read-availability.md) — eight families, atomic publication and recovery.
- [Security](architecture/security.md) — OIDC, pre-semaphore validation, HMAC idempotency, local secrets, and log redaction.

## Reference

- [Reference Index](reference/README.md)
- [`./pocoma-local` CLI](reference/cli.md) — full syntax, 11 subcommands, options, and exit codes.
- [Runtime Configuration](reference/configuration.md) — Spring profiles, Hikari pools, leases, polling (ms), containers, and limits.
- [Public API `/api/v3`](reference/api.md) — WRITE/READ routes, headers, payloads, HTTP statuses, and error format.
- [READ Availability Reference](reference/read-availability.md) — internal PostgreSQL records, family cursors and retention.

## Operations

- [Operations Index](operations/README.md)
- [Troubleshooting & Diagnostics](operations/troubleshooting.md) — `operationalState` / `qualificationState`, `PRE-*` / `DB-*` / `KEYCLOAK_*` / `GRAFANA-*` codes, and known limits.
- [Crash Recovery & Lifecycle](operations/recovery.md) — ungraceful stop recovery, PostgreSQL outage handling, `reset`, `destroy`, and `upgrade-grafana`.

---

## Internal Engineering & Build History

Internal engineering policies, active specifications, historical build reports, and qualification evidence are grouped in the secondary internal space:

- [Internal Documentation & Build History](internal/README.md)
- [Reactor Verification Policy](testing/Reactor_Verification_Policy.md) *(normative for changes under `app/`)*
- [Continuous Integration (CI)](development/ci.md)
