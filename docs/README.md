# Pocoma Documentation

Welcome to the **Pocoma** product documentation. It covers how to install, understand, use, and operate Pocoma locally without prior knowledge of its build history.

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

## Architecture

- [Architecture Index](architecture/README.md)
- [Overview](architecture/overview.md) — domain concepts, WRITE/READ separation, async flow, and 9-container topology.
- [Maven Modules (42 modules)](architecture/modules.md) — layer taxonomy, dependency rules, and where to place new code.
- [JVM Runtimes & Concurrency](architecture/runtimes.md) — the 5 runtimes, 36 partitions, SQL leases (`30 s`), `renewBatch`, and fencing.
- [PostgreSQL Persistence](architecture/persistence.md) — `pocoma_write` / `pocoma_read` databases, V1 baselines, and coordination tables.
- [Security](architecture/security.md) — OIDC, pre-semaphore validation, HMAC idempotency, local secrets, and log redaction.

## Reference

- [Reference Index](reference/README.md)
- [`./pocoma-local` CLI](reference/cli.md) — full syntax, 9 subcommands, options, and exit codes.
- [Runtime Configuration](reference/configuration.md) — Spring profiles, Hikari pools, leases, polling (ms), containers, and limits.
- [Public API `/api/v3`](reference/api.md) — WRITE/READ routes, headers, payloads, HTTP statuses, and error format.

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
