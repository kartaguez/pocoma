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
       ├── reset --confirm RESET:<name> ──▶ reinitialize DBs/data with the current schema
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

## 4. Recovering After an Ungraceful Stop (Docker crash / kill) or Core NATS Outage

- **Core NATS outage or terminal client closure (automatic fallback & recovery, no restart, no data loss)**: Core NATS carries zero durable state (`jetstream: false`). Stopping, restarting, or terminating the NATS connection never modifies PostgreSQL state, never aborts a commit, and never fails HTTP `202 Accepted` admissions. Workers switch automatically from `CONNECTED` (`reconcile-poll=2000 ms`) to `DEGRADED` (`fallback-max-poll=20 ms`) within `≤ 4 s`, continue processing via PostgreSQL polling, automatically reconstruct the connection even after an involuntary terminal `CLOSED` event, and trigger an immediate full reconciliation (`onExternalReconnect`) upon broker recovery once subscriptions are confirmed via `flush()`.
- **Voluntary NATS deactivation (`NOOP`) vs durable instance reconfiguration (controlled restart, staged & recoverable)**:
  - Switching an existing `pocoma-local` instance to `NOOP` (`./pocoma-local start --instance <name> --wakeup-mode noop`) or back to `NATS` (`./pocoma-local start --instance <name> --wakeup-mode nats`) is **non-destructive** and **requires a controlled restart of the 4 wakeup runtimes** (`http-write`, `command-worker`, `critical-read-worker`, `pot-read-worker`) — it is not a hot no-restart toggle.
  - The transition follows an explicit staged protocol (`PREPARING → APPLYING → COMMITTED`): `manifest.json` retains the previously committed `wakeupMode` and records `wakeupTransition` until all checks and the canary succeed. `./pocoma-local status` surfaces `wakeup.transitionInProgress` if an interruption occurred.
  - **Recovering an interrupted mode switch**: run `./pocoma-local start --instance <name>` to deterministically resume and complete the pending transition, or `./pocoma-local start --instance <name> --wakeup-mode <previous>` to revert cleanly to the previous mode. Neither `reset` nor secret regeneration is ever required; PostgreSQL data, Keycloak, `http-read`, Prometheus, Grafana, ports, and `secrets.json` are strictly preserved and never recreated.
  - Once committed, the new mode is persisted durably in `manifest.json`: subsequent `./pocoma-local start --instance <name>` calls without `--wakeup-mode` stay in the configured mode.
- **Ungraceful worker/PostgreSQL stop**:
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

- **Reset databases with the current schema** (all local business data is wiped):

  ```bash
  ./pocoma-local reset --instance <name> --confirm RESET:<name> --dry-run
  ./pocoma-local reset --instance <name> --confirm RESET:<name>
  ```

- **Completely destroy the instance** (containers, volumes, and `.pocoma-local/instances/<name>/` directory):

  ```bash
  ./pocoma-local destroy --instance <name> --confirm DESTROY:<name> --dry-run
  ./pocoma-local destroy --instance <name> --confirm DESTROY:<name>
  ```

## READ reconstruction and availability

A fresh database installs the canonical READ V5 availability schema after V4. A populated V4 READ authority requires the exclusive L6/I2 reconstruction procedure for first activation. The procedure preserves authoritative WRITE data, drains the READ materializers, recreates READ and replays the retained WRITE sources. READ remains in maintenance until checkpoints, outstanding slots and projection integrity allow reopening. The availability oracle checks the catalogue, both directions of the effect and publication mapping, and the family positions.

For a fresh installation, rerun the setup command after a failed schema transaction. It verifies routing and discovery grants before accepting an existing authority. An incomplete schema left by an older installer requires inspection before the runtimes are restarted. Use the reconstruction procedure for populated READ data.

Use the [L6/I2 reconstruction procedure](../v3/L6_I2_Acceptance_and_Closure_Decision.md) for the qualified five-runtime T1A topology (`pocoma-critical-read-worker`, `pocoma-pot-read-worker`). The [reconstruction script](../../scripts/operations/v3-rebuild-read.py) includes status and resume paths and is **completely independent of Core NATS** (it replays authoritative PostgreSQL WRITE sources and relies on reconciliation polling, never on prior volatile notifications). A resume after a replay interruption keeps committed availability UUIDs and positions. A full READ rebuild regenerates them. Any downstream reader must resynchronize its cursors with the new READ incarnation. See the [availability reference](../reference/read-availability.md#concurrency-privileges-and-retention).
