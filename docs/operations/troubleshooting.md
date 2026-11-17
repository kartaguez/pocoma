# Troubleshooting & Diagnostics

This guide lists first-level checks, operational and qualification states, error codes, and known limits of the [`./pocoma-local`](../reference/cli.md) local distribution.

## 1. Diagnostic Command & Exit Codes

Always start by checking the instance status:

```bash
./pocoma-local status --instance demo
```

If an earlier command timed out and printed a `--run` identifier (`[0-9]{10}-[0-9a-f]{8}`):

```bash
./pocoma-local status --instance demo --run <run-id>
```

| Code | Meaning | Immediate Action |
| :---: | :--- | :--- |
| `0` | `READY` / command succeeded | Instance is operational and canary is qualified |
| `1` | `NOT_READY` / `STOPPED` / `STALE` / runtime error | Inspect the returned message, `operationalState` / `qualificationState`, and logs under `.pocoma-local/instances/<instance>/` |
| `2` | Usage error, prerequisite refusal (`PRE-*`), drift, or incompatible schema (`DB-*` / `INCOMPATIBLE-*`) | Fix workstation setup or choose `reset` / `destroy` / new `--instance` |
| `3` | Lock conflict (`PRE-INSTANCE-LOCKED`, `PRE-LIFECYCLE-LOCKED`, `PRE-BUILD-LOCKED`) | Wait for the concurrent operation to finish |
| `4` | `DEGRADED` | Business API and canary OK, but Prometheus unavailable; inspect `prometheus` / `grafana` |
| `130` | Keyboard interrupt (`Ctrl+C`) | Re-run `status` to inspect the resulting state |

## 2. Operational States vs. Qualification States

`./pocoma-local status` explicitly separates current service health (`operationalState`) from canary proof freshness (`qualificationState`):

| Field | Values | Meaning |
| :--- | :--- | :--- |
| `operationalState` | `OPERATIONAL` | All 9 containers, PostgreSQL, Keycloak, all 5 JVMs (no `draining` or exhausted capacity), and Prometheus are healthy |
| `operationalState` | `DEGRADED_OBSERVABILITY` | Core application healthy, but Prometheus unavailable (`exit code 4`) |
| `operationalState` | `UNAVAILABLE` | At least one critical service (PostgreSQL, Keycloak, web runtime, or worker in `draining`) is unavailable |
| `qualificationState` | `QUALIFIED` | Canary proof is fresh (`≤ 300 s`) and container generation is consistent |
| `qualificationState` | `STALE` | Services operational, but canary proof expired (`> 300 s`, `exit code 1`); re-run `./pocoma-local start` to refresh |
| `qualificationState` | `GENERATION_MISMATCH` | Partial restart detected across container generations; re-run `./pocoma-local start` |
| `qualificationState` | `UNQUALIFIED` | No valid canary proof available |

## 3. Prerequisite Refusals (`PRE-*`)

| Symptom / Code | Verified Cause | Resolution |
| :--- | :--- | :--- |
| Toolchain prerequisite failure | Docker stopped or version outside contract ([`scripts/local/toolchain.json`](../../scripts/local/toolchain.json): Python ≥ `3.11`, JDK `21`, Node `24`, npm ≥ `7`, Bruno `4.3.0`) | Start Docker; align JDK/Node/Python in the current shell |
| Global variable detected (`PRE-*`) | Presence of `MAVEN_ARGS`, `MAVEN_OPTS`, `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS`, or `_JAVA_OPTIONS` | `unset MAVEN_ARGS MAVEN_OPTS JAVA_TOOL_OPTIONS JDK_JAVA_OPTIONS _JAVA_OPTIONS` |
| Forbidden `.mvn` file (`PRE-*`) | Presence of `maven.config`, `jvm.config`, or `extensions.xml` under `app/.mvn/` | Remove the unauthorized local file from `app/.mvn/` |
| `PRE-*-LOCKED` | Concurrent operation or active lock (`exit code 3`) | Wait until the lock is released before retrying |
| `PRE-SECRETS-MISSING` | Existing instance directory missing its expected private secrets | Reset (`reset --confirm RESET:<instance>`) or destroy (`destroy`) the instance |
| `PRE-INPUT-DRIFT` | Source code, SQL schemas, or config modified since instance creation | Use another `--instance <name>` or reset with `reset` |

## 4. Database & Drift Incidents

| Code | Cause | Recommended Resolution |
| :--- | :--- | :--- |
| `INCOMPATIBLE-LEGACY-FLYWAY-HISTORY` | Instance contains legacy Flyway history incompatible with active V1 baselines | Do not run old migrations; recreate via `reset --confirm RESET:<instance>` or create a new instance |
| `INCOMPATIBLE-EXISTING-SCHEMA` | Existing PostgreSQL schema missing the expected `public.pocoma_local_bootstrap` marker | Use `reset` or `destroy` (with `--dry-run` first) or another `--instance` |
| `DB-INPUT-DRIFT` | SQL scripts or init parameters changed compared to the recorded bootstrap in DB | Explicitly reset with `./pocoma-local reset --instance <name> --confirm RESET:<name>` |
| `DB-INIT-TIMEOUT` | PostgreSQL remains unreachable at startup | Check Docker and the `postgres` container health before re-running `start` |

## 5. Keycloak, Bruno, and Observability Incidents

- **Authentication / Keycloak (`KEYCLOAK_*`)**: check the `keycloak` container state in `status` and renew the local session with `./pocoma-local login --instance demo`. On macOS, ensure no stray `.DS_Store` files interfere with local bind mounts inside the instance directory. For Bruno authentication, see [Local Development & Bruno](../guides/local-development.md).
- **Grafana / Prometheus**:
  - `GRAFANA-HTTP`: Grafana does not respond on its expected HTTP or health endpoint.
  - `GRAFANA-DATASOURCE`: the Prometheus datasource is unreachable or misprovisioned.
  - `GRAFANA-DASHBOARD`: one of the two mandatory dashboards (*Pocoma runtime*, *Pocoma V3 - Architecture & Observability Overview*) is missing or invalid.
  - To re-apply Grafana configuration without losing data: `./pocoma-local upgrade-grafana --instance demo`.

## 6. Known Limits of the Local Distribution

- **Nominal load envelope (`25 cmd/s`)**: with `pocoma.v3.http.admission.capacity=4`, WRITE admission sustains `50 cmd/s` without `503` rejections or data loss, but `business-read-worker` queuing degrades visible READ latency at continuous `50 cmd/s` (`p95 ~ 25.2 s`). **`25 cmd/s`** is the sustainable nominal envelope on the local distribution.
- **Single-worker topology & shared PostgreSQL**: each role has a single worker container sharing the local PostgreSQL instance; multi-node high-availability failover is outside the scope of the local distribution.
- **Logs**: secrets and tokens are redacted in logs stored under `.pocoma-local/instances/<instance>/`.
