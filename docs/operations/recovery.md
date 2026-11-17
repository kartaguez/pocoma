# Crash Recovery & Lifecycle

This guide describes the full lifecycle of a [`./pocoma-local`](../reference/cli.md) local instance and recovery procedures after an ungraceful stop or PostgreSQL outage.

## 1. Instance Lifecycle

```text
    (absent) ──[ doctor (read-only prerequisites & cache check) ]
       │
       ▼  ./pocoma-local start --instance <name>
   [ READY ] ◀──────────────────────────────┐
       │                                    │ start (same fingerprint)
       ├── status / login / gui / bruno ────┤ (read-only inspection, auth, E2E tests)
       │                                    │
       ├── reload [--runtime <r>] ──────────┤ (rebuild & recreate JVM runtime(s); DB & Keycloak preserved)
       │                                    │
       ├── upgrade-grafana ─────────────────┘ (update Grafana configuration only; DB & Keycloak preserved)
       │
       ├── stop ──▶ [ STOPPED (data preserved) ] ──( start )──▶ [ READY ]
       │
       ├── reset --confirm RESET:<name> ──▶ reinitialize DBs/data from V1
       │
       └── destroy --confirm DESTROY:<name> ──▶ full deletion (containers, volumes, state)
```

- **Mandatory confirmation**: `reset` and `destroy` refuse to run without `--confirm RESET:<instance>` or `--confirm DESTROY:<instance>`. Use `--dry-run` to simulate without destructive effects (`--dry-run` is rejected with `exit code 2` on all other subcommands).
- **Never edit an instance by hand**: avoid manually deleting individual containers, volumes, or PostgreSQL tables; always use `stop`, `reset`, or `destroy`.

## 2. Restarting After a Graceful Stop (`stop` → `start`)

`./pocoma-local stop --instance <name>` cleanly stops all 9 containers while preserving `pocoma_write` / `pocoma_read` databases, Keycloak state, Prometheus metrics, and local secrets under `.pocoma-local/instances/<name>/`.

To restart:

```bash
./pocoma-local start --instance <name>
```

## 3. Recovering From a PostgreSQL Outage

During a prolonged PostgreSQL outage, the three workers (`command-worker`, `results-worker`, `business-read-worker`) switch to **fail-closed** (`draining`) mode:
- `/actuator/health` switches to **`503 DOWN`** (`pocoma_v3_worker_state{state="draining"} = 1.0`), while `/actuator/health/readiness` may remain `200 UP`.
- An `http-write` admission left `UNRESOLVED` during the outage may also hold a permit (`pocoma_v3_admission_capacity_available < 4.0`).

### Recovery Procedure

1. Ensure the PostgreSQL service is reachable again.
2. Simply run:

   ```bash
   ./pocoma-local start --instance <name>
   ```

3. `./pocoma-local start` first checks PostgreSQL and Keycloak availability (if PostgreSQL is still down, `start` fails cleanly with `DB-INIT-TIMEOUT` without needlessly restarting runtimes).
4. Once PostgreSQL is healthy, the orchestrator automatically detects workers in `draining` state and any residual `http-write` saturation, performs a **single** bounded restart (logged in `runtime-recovery.json`), waits for re-acquisition of all **36 leases** with new `owner_token` values (fencing), and runs the canary.
5. Verify return to nominal state:

   ```bash
   ./pocoma-local status --instance <name>
   ```

   Expected: `exit=0`, `state=READY`, `operationalState=OPERATIONAL`, `qualificationState=QUALIFIED`.

## 4. Recovering After an Ungraceful Stop (Docker crash / kill)

After an ungraceful stop:

- The nominal SQL TTL for partition leases is **`30 s`** (`lease-duration-ms=30000`), with batched renewal (`renewBatch`) every **`10 s`**.
- Full recovery combines lease expiration (`30 s`), worker polling cycles (`min-poll=2 ms`, `max-poll=20 ms`), any `claim-duration-ms=60000` if a slot was claimed, and projection convergence.
- `./pocoma-local` enforces a **bounded recovery observation budget of `270 s`**.

If a command hits a client-side timeout, inspect the final outcome using the printed run ID:

```bash
./pocoma-local status --instance <name> --run <run-id>
```

## 5. Updating, Resetting, or Destroying

- **Reload Java runtime(s) only** (when Java code changed without SQL migration or infrastructure changes):

  ```bash
  ./pocoma-local reload --instance <name>
  ./pocoma-local reload --instance <name> --runtime http-write
  ```

- **Update Grafana only** (read-only configuration and dashboards):

  ```bash
  ./pocoma-local upgrade-grafana --instance <name>
  ```

- **Reset databases from V1 baselines** (all local business data is wiped):

  ```bash
  ./pocoma-local reset --instance <name> --dry-run
  ./pocoma-local reset --instance <name> --confirm RESET:<name>
  ```

- **Completely destroy the instance** (containers, volumes, and `.pocoma-local/instances/<name>/` directory):

  ```bash
  ./pocoma-local destroy --instance <name> --dry-run
  ./pocoma-local destroy --instance <name> --confirm DESTROY:<name>
  ```
