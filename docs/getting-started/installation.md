# Installation & Prerequisites

This guide explains how to prepare your workstation, install and start a local Pocoma instance with [`./pocoma-local`](../reference/cli.md), verify its `READY` state, and stop or destroy it cleanly.

## 1. System Prerequisites

Required versions are defined in [`scripts/local/toolchain.json`](../../scripts/local/toolchain.json) and automatically checked by `./pocoma-local` before any container operation:

| Component | Requirement | Role |
| :--- | :--- | :--- |
| **Docker** | Docker Engine or Docker Desktop **running** | Runs the 10 local containers (PostgreSQL, Keycloak, Core NATS, Prometheus, Grafana, 5 T1A JVMs) |
| **Python** | **≥ 3.11** | Runs `./pocoma-local` and initialization scripts |
| **Java (JDK)** | Major version **21** | Compiles via the Maven Wrapper (`app/mvnw`) |
| **Node.js & npm** | Node **≥ 22**, `npm` **≥ 7** | Runs Bruno CLI (`4.3.0`) |
| **Bruno CLI** | Version **`4.3.0`** (installed in `scripts/bruno-v3`) | Runs automated collections and E2E journeys |

> [!NOTE]
> No global Maven installation is required: `./pocoma-local` uses the repository's `app/mvnw` wrapper with your JDK 21.

### Recommended Docker Resources

A local instance starts **10 containers** by default (5 infrastructure services including Core NATS `nats:2.15.3-alpine`, and 5 `eclipse-temurin:21.0.8_9-jre` JVM runtimes in T1A topology; or 9 containers with `--wakeup-mode noop`), with combined memory limits around **5,888 MiB**. Allocate at least **8 GB of RAM** to Docker Desktop to avoid OOM kills during simultaneous build or startup.

### Shell Environment & Maven / Java Restrictions

To guarantee reproducible builds:

- **Forbidden files under `app/.mvn/`**: `maven.config`, `jvm.config`, `extensions.xml` (`./pocoma-local` refuses with `PRE-UNREVIEWED-MAVEN-CONFIG`).
- **Automatic isolation of JVM/Maven shell variables**: if `MAVEN_ARGS`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, or `_JAVA_OPTIONS` are set in your shell, `./pocoma-local` automatically strips them from its build and probe subprocesses without modifying your shell.

### Quick Workstation Check

```bash
./pocoma-local doctor
```

`doctor` performs a read-only check of Docker (daemon + allocated RAM), host free disk space (`PRE-DISK`), Python, JDK 21, Node ≥ 22, npm/Bruno CLI, and whether the Maven build or Docker images are already cached — without creating any instance state.

> [!NOTE]
> **Platform scope**: `./pocoma-local` has been verified on **macOS (Apple Silicon / ARM64)** with Docker Desktop. On a fresh machine without local caches, the first `./pocoma-local start` also downloads Maven dependencies into `~/.m2`, pulls the 5 Docker base images (~1.5–2 GB), and `./pocoma-local bruno` installs Bruno CLI `4.3.0` via `npm` inside `scripts/bruno-v3/`. Local `./pocoma-local` execution on Linux and Windows (WSL2) has not been independently qualified yet.

## 2. Installation & First Startup

From the repository root, create and start the default instance (`demo`) or a named instance (`[a-z][a-z0-9-]{0,30}`):

```bash
./pocoma-local start --instance demo
```

When creating a fresh instance, `./pocoma-local start` deterministically executes:

1. Prerequisite checks and generation of isolated local secrets under `.pocoma-local/instances/demo/`.
2. Compilation of Java artifacts (`app/mvnw`) and preparation of Docker images if needed.
3. Startup of **PostgreSQL 17.6** and initialization of `pocoma_write` (`v3_write` schema) and `pocoma_read` (`v3_read` schema) from the active V1 SQL baselines, with no manual migrations to replay.
4. Initialization of the **36 routing partitions** and the `public.pocoma_local_bootstrap` marker.
5. Startup of **Keycloak 26.7.0**, the **5 JVM runtimes**, **Prometheus**, and **Grafana**.
6. Availability verification (`READY`) and execution of the end-to-end functional canary.

## 3. Verify `READY` State & Access Services

Inspect the consolidated instance status and exposed local URLs:

```bash
./pocoma-local status --instance demo
```

| Exit Code | State | Meaning |
| :---: | :--- | :--- |
| `0` | `READY` | All 10 containers are healthy (or 9 in `--wakeup-mode noop`), all 5 T1A runtimes are operational, and the canary succeeded. |
| `4` | `DEGRADED` | Business services and canary are healthy, but Prometheus is unavailable. |
| `1` | `NOT_READY` / `STOPPED` / `STALE` | Instance is stopped, unhealthy, or canary proof expired (`> 300 s`). |
| `2` / `3` | `PRE-*` / lock | Missing prerequisite, input drift detected, or concurrent operation lock. |

The output of `status` (and `start`) prints the instance's local endpoints:

- **HTTP WRITE API (`WRITE_URL`)**: command admission `/api/v3` (`http-write`, internal port `8080`).
- **HTTP READ API (`READ_URL`)**: read queries `/api/v3` (`http-read`, internal port `8082`).
- **Keycloak (`ISSUER`)**: admin console `admin` / `pocoma`; local test accounts `third`, `guest`, `bruno` (password `pocoma`).
- **Grafana**: login `admin` / `pocoma`, with two read-only provisioned dashboards (*Pocoma runtime*, *Pocoma V3 - Architecture & Observability Overview*).
- **PostgreSQL**: intentionally isolated on the internal Docker network, with no host port published.

## 4. Stop, Reset, and Destroy an Instance

- **Stop while preserving data** (PostgreSQL databases, Keycloak state, secrets, and volumes remain intact):

  ```bash
  ./pocoma-local stop --instance demo
  ```

- **Restart** an existing instance:

  ```bash
  ./pocoma-local start --instance demo
  ```

- **Reset data** (recreate fresh WRITE/READ databases):

  ```bash
  ./pocoma-local reset --instance demo --confirm RESET:demo --dry-run
  ./pocoma-local reset --instance demo --confirm RESET:demo
  ```

- **Completely destroy the instance** (containers, volumes, state, and secrets):

  ```bash
  ./pocoma-local destroy --instance demo --confirm DESTROY:demo --dry-run
  ./pocoma-local destroy --instance demo --confirm DESTROY:demo
  ```

## 5. Initial Troubleshooting

- **`PRE-*` on startup**: verify Docker Desktop is running and no unreviewed `app/.mvn/{maven.config,jvm.config,extensions.xml}` files exist.
- **`PRE-INPUT-DRIFT` or `DB-INPUT-DRIFT` on an existing instance**: source code or SQL schemas changed since the instance was created; use another `--instance <name>` or run `reset`.
- **`qualificationState=STALE` (`exit code 1`)**: the canary proof is older than `300 s` while services are healthy (`operationalState=OPERATIONAL`); rerun `./pocoma-local start --instance demo` to refresh the canary.
- For the full error catalog and procedures: [Troubleshooting & Diagnostics](../operations/troubleshooting.md) and [Crash Recovery & Lifecycle](../operations/recovery.md).

Next step: [First Steps](first-steps.md).
