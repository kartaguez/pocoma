# CLI Reference (`./pocoma-local`)

The `./pocoma-local` command-line interface (implemented by [`scripts/local/entrypoint.py`](../../scripts/local/entrypoint.py) and [`scripts/local/runtime.py`](../../scripts/local/runtime.py)) is the single entry point to create, verify, operate, and clean up a local instance.

## General Syntax & Options

```text
usage: pocoma-local [-h] [--instance INSTANCE] [--confirm CONFIRM] [--dry-run]
                    [--interactive] [--run RUN]
                    {start,reload,upgrade-grafana,status,stop,reset,destroy,bruno,login,gui}
```

| Option | Default / Format | Scope & Behavior |
| :--- | :--- | :--- |
| `-h`, `--help` | — | Show CLI help and exit |
| `--instance INSTANCE` | `demo` (`[a-z][a-z0-9-]{0,30}`) | Target the named local instance |
| `--confirm CONFIRM` | `RESET:<instance>` or `DESTROY:<instance>` | Required to execute `reset` or `destroy` without `--dry-run` |
| `--dry-run` | boolean | Simulate `reset` or `destroy` without modifying or deleting data; explicitly rejected (`exit code 2`) on all other subcommands |
| `--interactive` | boolean | Interactive execution option for `bruno` |
| `--run RUN` | `[0-9]{10}-[0-9a-f]{8}` | Reserved for `status`: read the late result of a timed-out execution |

## Subcommands

| Subcommand | Actual Effect | Examples |
| :--- | :--- | :--- |
| `start` | Checks prerequisites and locks, builds if needed, initializes PostgreSQL (`pocoma_write`/`pocoma_read`), Keycloak, the 5 JVMs, Prometheus, and Grafana, then runs the `READY` check and canary. On an existing instance, verifies PostgreSQL health, performs a bounded restart of runtimes left fail-closed (`draining` or `http-write` admission capacity depleted), and re-qualifies the canary; refuses cleanly (`exit code 2`) on input drift or incompatible schema. | `./pocoma-local start --instance demo` |
| `reload` | Java inner-loop reload: rebuilds the 5 `runtime-*` JARs and recreates only the 5 JVM runtime containers while preserving PostgreSQL, Keycloak, Prometheus, Grafana, and data volumes. Refuses (`RELOAD_RESET_REQUIRED`) if SQL migrations or infrastructure/config inputs changed, and verifies `DB-CATALOGUE-DRIFT` before re-qualifying the canary. | `./pocoma-local reload --instance dev-feature` |
| `status` | Inspects container availability, runtime/worker state (including `draining`), exposed URLs, and the canary result (or a given `--run`). Explicitly distinguishes `operationalState` / `operational` (services, workers, dependencies) from `qualificationState` / `qualification` (`QUALIFIED`, `STALE`, `GENERATION_MISMATCH`, `UNQUALIFIED`, `MAX_AGE=300 s`). | `./pocoma-local status --instance demo`<br>`./pocoma-local status --instance demo --run <run-id>` |
| `stop` | Cleanly stops the instance's containers while preserving PostgreSQL, Keycloak, Prometheus volumes, and local secrets. | `./pocoma-local stop --instance demo` |
| `upgrade-grafana` | Updates Grafana configuration and dashboards for the instance without touching business data or resetting databases. | `./pocoma-local upgrade-grafana --instance demo` |
| `reset` | Resets PostgreSQL databases and data for the instance from active V1 baselines. Requires `--confirm RESET:<instance>` (or `--dry-run`). | `./pocoma-local reset --instance demo --dry-run`<br>`./pocoma-local reset --instance demo --confirm RESET:demo` |
| `destroy` | Completely deletes containers, volumes, and the local instance directory. Requires `--confirm DESTROY:<instance>` (or `--dry-run`). | `./pocoma-local destroy --instance demo --dry-run`<br>`./pocoma-local destroy --instance demo --confirm DESTROY:demo` |
| `login` | Runs the local OIDC authentication flow (Code + PKCE S256 via Keycloak) for the instance. | `./pocoma-local login --instance demo` |
| `gui` | Opens Bruno GUI preconfigured for the instance with OIDC authentication and no manual JWT copying. | `./pocoma-local gui --instance demo` |
| `bruno` | Runs the automated Bruno collection (`J01`, `J02`, `J03` — 118 requests, 236 named tests) via Bruno CLI `4.3.0`. Supports `--interactive`. | `./pocoma-local bruno --instance demo` |

## CLI Exit Codes

| Code | Meaning |
| :---: | :--- |
| `0` | Success (`READY`, operation completed without error) |
| `1` | `NOT_READY`, `STOPPED`, `STALE`, or runtime execution failure |
| `2` | Argument error, missing confirmation, or `PRE-*` / `DB-*` / `INCOMPATIBLE-*` refusal |
| `3` | Active lock conflict (`PRE-INSTANCE-LOCKED`, `PRE-LIFECYCLE-LOCKED`, `PRE-BUILD-LOCKED`) |
| `4` | `DEGRADED` state (core app and canary OK, Prometheus observability degraded) |
| `130` | Interrupted by user signal (`SIGINT`) |

## Related Commands (Bruno & Maven Verification)

For developers modifying Java code under `app/`, verification does not run `./mvnw test` across the full reactor by default; instead it uses **targeted Maven slices** (`WEB`, `COMMAND`, `PROJECTION`, `FOUNDATION`, `Monolith`, `architecture-tests`).

Always consult the normative policy before compiling or running Maven tests:

- [Reactor Verification Policy](../testing/Reactor_Verification_Policy.md)
- [Local Development & Bruno Guide](../guides/local-development.md)
