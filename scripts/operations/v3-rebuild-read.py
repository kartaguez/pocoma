#!/usr/bin/env python3
"""Pocoma V3 — READ Reconstruction Orchestration Script.

Orchestrates the complete, safe, and observable destruction, migration, and replay
convergence of the READ storage tier (pocoma_read.v3_read) from canonical WRITE events.

Usage:
  python3 scripts/operations/v3-rebuild-read.py --confirm-destructive=RESET_READ_DATA [OPTIONS]
  python3 scripts/operations/v3-rebuild-read.py --status
  python3 scripts/operations/v3-rebuild-read.py --resume
  python3 scripts/operations/v3-rebuild-read.py --reset-failed

Invariants:
- Zero mutations or event deletions on WRITE (pocoma_write).
- Strict isolation: WRITE connections are explicitly read-only and use restricted role.
- Fail-closed: HTTP 503 is maintained while not READY.
- Resilient: public.read_maintenance_state persists across DROP SCHEMA CASCADE.
- Operator exclusivity: compare-and-set atomic acquisition conditioned on READY & IDLE.
- Non-destructive resume: --resume never executes implicit DROP SCHEMA.
- Conservative oracle: strict catalogue matching, no 0L or MAX_VALUE defaults.
"""

import argparse
import getpass
import json
from pathlib import Path
import subprocess
import sys
import time
import uuid

REPO_ROOT = Path(__file__).resolve().parents[2]
POSTGRES_CONTAINER = "pocoma-functional-postgres-1"
POSTGRES_ADMIN_USER = "pocoma"
POSTGRES_ADMIN_PASSWORD = "pocoma-local-only"
POSTGRES_READ_USER = "pocoma_read"
POSTGRES_READ_PASSWORD = "pocoma-local-only"

READ_DB = "pocoma_read"
WRITE_DB = "pocoma_write"
READ_SCHEMA = "v3_read"
WRITE_SCHEMA = "v3_write"
READ_ROLE = "pocoma_read"

COMPOSE_FILES = [
    "-f", str(REPO_ROOT / "docker-compose.functional.yml"),
    "-f", str(REPO_ROOT / "docker/compose/docker-compose.observability.yml"),
]

# Canonical 7 read consumptions derived from V3ConsumptionCatalogConfiguration
CANONICAL_READ_CONSUMPTIONS = {
    "3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a": "POT_EVENT",
    "190a93c6-b8af-5ac7-8001-72e2ec53c2df": "POT_EVENT",
    "b4d04cce-b5da-5d1b-9698-5ff9af9a46fa": "POT_EVENT",
    "924cd9e3-435d-5804-9d1e-c6aecadb9ab4": "INVITATION_EVENT",
    "7b8d2838-bf7a-5117-868a-e5c14b4c0221": "IDENTITY_EVENT",
    "63664303-d33e-5d42-8cce-c4dae8023311": "COMMAND_COMPLETION_EVENT",
    "1d8ff246-358f-5ddd-a7f5-416efcfd7445": "IDENTITY_COMPLETION_EVENT",
}
EXPECTED_WRITE_FAMILIES = {"POT", "IDENTITY", "COMMAND_COMPLETION", "IDENTITY_COMPLETION", "INVITATION"}
EXPECTED_CHECKPOINTS_COUNT = len(CANONICAL_READ_CONSUMPTIONS) * 4  # 28

CANONICAL_MIGRATION_TABLES = [
    "consumption_routing_contract",
    "partition_ownership",
    "discovery_checkpoint",
    "consumption_slot",
    "read_subobject_version",
    "read_pot_version",
    "read_current_pot",
    "read_pot_balance",
    "read_pot_visibility_state",
    "read_user_pot",
    "read_current_binding",
    "read_command_result",
    "read_identity_request_result",
    "read_current_invitation",
]


def psql_admin(db, sql_command, check=True):
    """Execute SQL command as postgres admin in postgres container."""
    schema = "v3_read" if "read" in db else "v3_write"
    pgoptions = f"-c search_path={schema},public"
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_ADMIN_PASSWORD}",
        "-e", f"PGOPTIONS={pgoptions}",
        POSTGRES_CONTAINER,
        "psql", "-X", "-U", POSTGRES_ADMIN_USER, "-d", db, "-v", "ON_ERROR_STOP=1", "-tAc", sql_command
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"psql admin failed on {db}: {res.stderr.strip()} (cmd: {sql_command})")
    return res.stdout.strip()


def psql_write_readonly(sql_command, check=True):
    """Execute SQL command on WRITE_DB using strictly isolated role pocoma_read with read-only transaction."""
    pgoptions = f"-c search_path={WRITE_SCHEMA},public -c default_transaction_read_only=on"
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_READ_PASSWORD}",
        "-e", f"PGOPTIONS={pgoptions}",
        POSTGRES_CONTAINER,
        "psql", "-X", "-U", POSTGRES_READ_USER, "-d", WRITE_DB, "-v", "ON_ERROR_STOP=1", "-tAc", sql_command
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"psql write readonly failed on {WRITE_DB}: {res.stderr.strip()} (cmd: {sql_command})")
    return res.stdout.strip()


def psql_script(db, schema, script_content, variables=None):
    """Execute SQL script content via docker exec as admin."""
    vars_args = []
    if variables:
        for k, v in variables.items():
            vars_args.extend(["-v", f"{k}={v}"])
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_ADMIN_PASSWORD}",
        "-e", f"PGOPTIONS=-c search_path={schema},public",
        POSTGRES_CONTAINER,
        "psql", "-X", "-U", POSTGRES_ADMIN_USER, "-d", db, "-v", "ON_ERROR_STOP=1",
        *vars_args, "-f", "-"
    ]
    res = subprocess.run(cmd, input=script_content, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"psql script failed on {db}.{schema}: {res.stderr.strip()}")
    return res.stdout.strip()


def get_current_maintenance_state():
    """Retrieve current row from public.read_maintenance_state."""
    raw = psql_admin(
        READ_DB,
        "SELECT row_to_json(r) FROM public.read_maintenance_state r WHERE id = 1;",
        check=False
    )
    if not raw:
        return None
    try:
        return json.loads(raw)
    except Exception as e:
        print(f"Warning: Failed to parse maintenance state JSON: {e}", file=sys.stderr)
        return None


def print_status():
    """Display current rebuild and maintenance status."""
    st = get_current_maintenance_state()
    if not st:
        print("Status: public.read_maintenance_state NOT FOUND in pocoma_read")
        return 1

    print("================================================================================")
    print(" Pocoma V3 — READ Maintenance & Reconstruction Status")
    print("================================================================================")
    print(f"  State:         {st.get('state')}")
    print(f"  Phase:         {st.get('phase')}")
    print(f"  Operator:      {st.get('operator_id') or '(none)'}")
    print(f"  Operation ID:  {st.get('operation_id') or '(none)'}")
    print(f"  Started at:    {st.get('started_at') or '(none)'}")
    print(f"  Updated at:    {st.get('updated_at')}")
    print(f"  Target Prefix: {json.dumps(st.get('target_prefix')) if st.get('target_prefix') else '(none)'}")
    if st.get('error_detail'):
        print(f"  Error Detail:  {st.get('error_detail')}")

    try:
        cp_count = psql_admin(READ_DB, "SELECT count(*) FROM v3_read.discovery_checkpoint;", check=False)
        slot_count = psql_admin(READ_DB, "SELECT count(*) FROM v3_read.consumption_slot;", check=False)
        print(f"  Checkpoints:   {cp_count or '0'}/{EXPECTED_CHECKPOINTS_COUNT}")
        print(f"  Current Slots: {slot_count or '0'}")
    except Exception:
        pass
    print("================================================================================")
    return 0


def apply_migrations(db, schema, admin, runtime, migration_files):
    for f in migration_files:
        path = REPO_ROOT / f
        content = path.read_text(encoding="utf-8")
        content = content.replace("${expectedDatabase}", db)
        content = content.replace("${expectedSchema}", schema)
        content = content.replace("${expectedAdmin}", admin)
        content = content.replace("${expectedRuntime}", runtime)
        psql_script(db, schema, content)


def to_write_family(family):
    if family and family.endswith("_EVENT"):
        return family[:-len("_EVENT")]
    return family


def evaluate_readiness_oracle(target_prefix):
    """Evaluates conservative mathematical readiness oracle P_READY(B) on live database.

    Strict invariants:
    - Target prefix B must contain all expected write families.
    - Checkpoints must match exactly the canonical catalogue (7 consumptions x 4 partitions).
    - Unresolved slot positions in WRITE strictly block READY.
    - Slots are resolved via a single batch query on WRITE.
    - Relational integrity must have 0 violations.
    """
    if not target_prefix:
        return False, False, False, False, "Target prefix B is missing or empty"

    # Pre-check: Target prefix B must contain all canonical WRITE families
    missing_families = EXPECTED_WRITE_FAMILIES - set(target_prefix.keys())
    if missing_families:
        return False, False, False, False, f"Target prefix B missing expected families: {missing_families}"

    # 1. Condition C_checkpoints
    cp_rows = psql_admin(
        READ_DB,
        "SELECT dc.consumption_id, crc.family, dc.partition_id, dc.position "
        "FROM v3_read.discovery_checkpoint dc "
        "JOIN v3_read.consumption_routing_contract crc ON dc.consumption_id = crc.consumption_id "
        "WHERE crc.partition_count = 4;"
    )

    satisfied_checkpoints = 0
    seen_keys = set()
    catalog_mismatch = False

    if cp_rows:
        for line in cp_rows.splitlines():
            parts = line.split("|")
            if len(parts) >= 4:
                cid, family, part_id, pos = parts[0], parts[1], int(parts[2]), int(parts[3])
                if cid not in CANONICAL_READ_CONSUMPTIONS or part_id < 0 or part_id >= 4:
                    catalog_mismatch = True
                    continue
                key = (cid, part_id)
                if key in seen_keys:
                    catalog_mismatch = True
                    continue
                seen_keys.add(key)

                write_fam = to_write_family(family)
                target_pos = target_prefix.get(write_fam)
                if target_pos is not None and pos >= target_pos:
                    satisfied_checkpoints += 1

    checkpoints_ok = (not catalog_mismatch) and (len(seen_keys) == EXPECTED_CHECKPOINTS_COUNT) and (satisfied_checkpoints == EXPECTED_CHECKPOINTS_COUNT)

    # 2. Condition C_slots (BATCHED resolution from WRITE)
    slot_rows = psql_admin(
        READ_DB,
        "SELECT cs.consumable_id, crc.family, cs.state "
        "FROM v3_read.consumption_slot cs "
        "JOIN v3_read.consumption_routing_contract crc ON cs.consumption_id = crc.consumption_id;"
    )

    residual_slots = 0
    quarantined_slots = 0

    if slot_rows:
        raw_slots = []
        consumable_ids = []
        for line in slot_rows.splitlines():
            parts = line.split("|")
            if len(parts) >= 3:
                raw_slots.append((parts[0], parts[1], parts[2]))
                consumable_ids.append(parts[0])

        # Batch query WRITE for all consumable positions at once via isolated readonly role
        positions_map = {}
        if consumable_ids:
            quoted_ids = "','".join(consumable_ids)
            pos_results = psql_write_readonly(
                f"SELECT event_id, position FROM v3_write.v3_event_position WHERE event_id IN ('{quoted_ids}');",
                check=False
            )
            if pos_results:
                for line in pos_results.splitlines():
                    pp = line.split("|")
                    if len(pp) >= 2 and pp[1].isdigit():
                        positions_map[pp[0]] = int(pp[1])

        for slot_id, family, state in raw_slots:
            write_fam = to_write_family(family)
            target_pos = target_prefix.get(write_fam)

            if state.upper() == "QUARANTINED":
                quarantined_slots += 1

            if slot_id not in positions_map:
                # UNRESOLVED position in WRITE -> strictly count as residual!
                residual_slots += 1
            else:
                pos = positions_map[slot_id]
                if target_pos is not None and pos <= target_pos:
                    residual_slots += 1

    slots_ok = (residual_slots == 0) and (quarantined_slots == 0)

    # 3. Condition C_integrity
    integrity_errors = []
    # 3.1: CurrentPot -> PotVersion
    dangling_pots = psql_admin(
        READ_DB,
        "SELECT count(*) FROM v3_read.read_current_pot cp "
        "LEFT JOIN v3_read.read_pot_version pv ON cp.pot_id = pv.pot_id AND cp.pot_version = pv.pot_version "
        "WHERE pv.pot_id IS NULL;"
    )
    if dangling_pots and int(dangling_pots) > 0:
        integrity_errors.append(f"{dangling_pots} dangling current_pot references")

    # 3.2: CurrentPot -> PotBalance
    missing_balance = psql_admin(
        READ_DB,
        "SELECT count(*) FROM v3_read.read_current_pot cp "
        "LEFT JOIN v3_read.read_pot_balance pb ON cp.pot_id = pb.pot_id AND cp.pot_version = pb.pot_version "
        "WHERE pb.pot_id IS NULL;"
    )
    if missing_balance and int(missing_balance) > 0:
        integrity_errors.append(f"{missing_balance} missing pot balance rows")

    # 3.3: CurrentPot -> PotVisibility
    missing_visibility = psql_admin(
        READ_DB,
        "SELECT count(*) FROM v3_read.read_current_pot cp "
        "LEFT JOIN v3_read.read_pot_visibility_state vs ON cp.pot_id = vs.pot_id AND cp.pot_version <= vs.materialized_version "
        "WHERE vs.pot_id IS NULL;"
    )
    if missing_visibility and int(missing_visibility) > 0:
        integrity_errors.append(f"{missing_visibility} missing pot visibility rows")

    # 3.4: CurrentBinding sanity
    invalid_bindings = psql_admin(
        READ_DB,
        "SELECT count(*) FROM v3_read.read_current_binding WHERE eui_version < 0;"
    )
    if invalid_bindings and int(invalid_bindings) > 0:
        integrity_errors.append(f"{invalid_bindings} invalid binding versions")

    integrity_ok = len(integrity_errors) == 0

    ready = checkpoints_ok and slots_ok and integrity_ok
    diagnostic = (
        f"CP: {satisfied_checkpoints}/{EXPECTED_CHECKPOINTS_COUNT} | "
        f"Slots<=B: {residual_slots} (Q: {quarantined_slots}) | "
        f"Integrity: {'OK' if integrity_ok else ', '.join(integrity_errors)}"
    )
    return ready, checkpoints_ok, slots_ok, integrity_ok, diagnostic


def transition(from_state, to_state, to_phase, op_id, op_user, extra_sets=""):
    """Atomically executes a conditioned state and phase transition."""
    sql = (
        f"UPDATE public.read_maintenance_state SET "
        f"  state = '{to_state}', phase = '{to_phase}', updated_at = clock_timestamp() {extra_sets} "
        f"WHERE id = 1 AND state = '{from_state}' AND operation_id = '{op_id}'::uuid AND operator_id = '{op_user}' "
        f"RETURNING id;"
    )
    res = psql_admin(READ_DB, sql, check=False)
    if not res:
        raise RuntimeError(f"Transition to {to_state}/{to_phase} REJECTED by CAS! (Operator exclusivity lost or unexpected state)")


def check_tables_exist(expected_tables):
    """Verify that all expected tables exist in schema v3_read."""
    for t in expected_tables:
        reg = psql_admin(READ_DB, f"SELECT to_regclass('{READ_SCHEMA}.{t}')::text;")
        if not reg or reg == "":
            return False
    return True


def main():
    parser = argparse.ArgumentParser(description="Pocoma V3 READ Reconstruction Orchestration")
    parser.add_argument(
        "--confirm-destructive",
        type=str,
        default=None,
        help="Mandatory confirmation token: RESET_READ_DATA"
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help="Resume an interrupted or crashed rebuild without data destruction"
    )
    parser.add_argument(
        "--reset-failed",
        action="store_true",
        help="Explicitly reset authority from FAILED state back to READY/IDLE"
    )
    parser.add_argument(
        "--status",
        action="store_true",
        help="Display current maintenance state and exit"
    )
    parser.add_argument(
        "--operator-id",
        type=str,
        default=getpass.getuser(),
        help="Operator identifier for audit trail"
    )
    parser.add_argument(
        "--watchdog-timeout",
        type=int,
        default=180,
        help="Convergence watchdog timeout in seconds (default: 180)"
    )
    parser.add_argument(
        "--poll-interval",
        type=int,
        default=2,
        help="Replay polling interval in seconds (default: 2)"
    )
    parser.add_argument(
        "--dry-run",
        action="store_true",
        help="Preflight checks only without mutation"
    )

    args = parser.parse_args()

    if args.status:
        return print_status()

    # Preflight 1: Reset failed command
    if args.reset_failed:
        cur = get_current_maintenance_state()
        if not cur or cur.get("state") != "FAILED":
            print("ERROR: --reset-failed can only be executed when state is FAILED.", file=sys.stderr)
            return 1
        psql_admin(
            READ_DB,
            f"UPDATE public.read_maintenance_state SET "
            f"  state = 'READY', phase = 'IDLE', operator_id = NULL, operation_id = NULL, "
            f"  target_prefix = NULL, started_at = NULL, error_detail = NULL, "
            f"  updated_at = clock_timestamp() "
            f"WHERE id = 1 AND state = 'FAILED';"
        )
        print("✓ Maintenance authority reset from FAILED to READY (phase: IDLE).")
        return 0

    # Preflight 2: Destructive confirmation check
    if not args.resume and args.confirm_destructive != "RESET_READ_DATA":
        print("ERROR: Destructive rebuild requires --confirm-destructive=RESET_READ_DATA", file=sys.stderr)
        return 1

    # Preflight 3: Target database validation
    if READ_DB == WRITE_DB or "write" in READ_DB.lower():
        print(f"FATAL: Target database '{READ_DB}' violates WRITE isolation barrier!", file=sys.stderr)
        return 1

    # Preflight 4: PostgreSQL container check
    try:
        running = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Running}}", POSTGRES_CONTAINER],
            capture_output=True, text=True, check=True
        ).stdout.strip()
        if running != "true":
            print(f"ERROR: PostgreSQL container '{POSTGRES_CONTAINER}' is not running.", file=sys.stderr)
            return 1
    except Exception as e:
        print(f"ERROR: Failed to contact container {POSTGRES_CONTAINER}: {e}", file=sys.stderr)
        return 1

    # Preflight 5: Maintenance authority table existence
    maint_exists = psql_admin(READ_DB, "SELECT to_regclass('public.read_maintenance_state')::text;")
    if not maint_exists:
        print("ERROR: public.read_maintenance_state table does not exist in pocoma_read.", file=sys.stderr)
        return 1

    # Preflight 6: Verify WRITE isolation and permissions for pocoma_read
    try:
        # 6a. Ensure pocoma_read can read WRITE event counters
        psql_write_readonly("SELECT count(*) FROM v3_write.v3_event_counter;", check=True)
        # 6b. Ensure pocoma_read CANNOT write to WRITE tables (permission barrier test)
        mut_attempt = subprocess.run(
            [
                "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_READ_PASSWORD}",
                POSTGRES_CONTAINER,
                "psql", "-X", "-U", POSTGRES_READ_USER, "-d", WRITE_DB, "-v", "ON_ERROR_STOP=1", "-c",
                "UPDATE v3_write.v3_event_counter SET position = position WHERE family = 'POT';"
            ],
            capture_output=True, text=True
        )
        if mut_attempt.returncode == 0:
            print("FATAL: Role pocoma_read has mutation rights on WRITE database!", file=sys.stderr)
            return 1
    except Exception as e:
        print(f"FATAL: WRITE isolation verification failed: {e}", file=sys.stderr)
        return 1

    current = get_current_maintenance_state()
    if not current:
        print("ERROR: Could not query maintenance state.", file=sys.stderr)
        return 1

    current_state = current.get("state")
    current_phase = current.get("phase")
    active_op = current.get("operator_id")
    active_op_id = current.get("operation_id")

    if args.dry_run:
        print("✓ Preflight checks passed. Dry run complete; no changes applied.")
        return 0

    # -------------------------------------------------------------------------
    # RESUME PROTOCOL (NON-DESTRUCTIVE)
    # -------------------------------------------------------------------------
    if args.resume:
        if current_state == "READY":
            print("System is in READY state (phase: IDLE). No reconstruction to resume.")
            return 0
        if current_state == "FAILED":
            print(
                f"ERROR: Cannot resume rebuild in FAILED state (error: {current.get('error_detail')}). "
                f"Use --reset-failed first or start fresh with --confirm-destructive=RESET_READ_DATA.",
                file=sys.stderr
            )
            return 1
        if current_state not in ("MAINTENANCE", "REBUILDING"):
            print(f"ERROR: Invalid state for resume: {current_state}", file=sys.stderr)
            return 1

        print("================================================================================")
        print(f" Pocoma V3 — Resuming READ Reconstruction [Operator: {args.operator_id}]")
        print(f" Active Operation: {active_op_id} | State: {current_state} | Phase: {current_phase}")
        print(" Mode: RESUME (Strictly Non-Destructive — NO DROP SCHEMA)")
        print("================================================================================")

        operation_id = active_op_id
        # Adopt active operation id if not previously set
        if not operation_id:
            operation_id = str(uuid.uuid4())
            psql_admin(
                READ_DB,
                f"UPDATE public.read_maintenance_state SET operation_id = '{operation_id}'::uuid WHERE id = 1;"
            )

        # Physical state revalidation
        schema_exists = bool(psql_admin(READ_DB, f"SELECT to_regnamespace('{READ_SCHEMA}')::text;"))
        tables_exist = check_tables_exist(CANONICAL_MIGRATION_TABLES) if schema_exists else False
        routing_count = int(psql_admin(READ_DB, f"SELECT count(*) FROM {READ_SCHEMA}.consumption_routing_contract;", check=False) or 0) if tables_exist else 0
        cp_count = int(psql_admin(READ_DB, f"SELECT count(*) FROM {READ_SCHEMA}.discovery_checkpoint;", check=False) or 0) if tables_exist else 0

        target_b = current.get("target_prefix")

        print("• Physical state inspection:")
        print(f"  Schema {READ_SCHEMA} exists:      {schema_exists}")
        print(f"  Migration tables exist:    {tables_exist} (14/14)")
        print(f"  Routing contracts:         {routing_count}/7")
        print(f"  Discovery checkpoints:     {cp_count}/{EXPECTED_CHECKPOINTS_COUNT}")
        print(f"  Target Prefix B:           {json.dumps(target_b) if target_b else '(not set)'}")

        # If already converged, just restore service
        if current_phase == "CONVERGED":
            print("Operation is already converged. Transitioning to READY...")
            transition("REBUILDING", "READY", "IDLE", operation_id, active_op,
                       extra_sets=", operator_id = NULL, operation_id = NULL, error_detail = NULL")
            print("✓ Restored to READY.")
            return 0

        # If replaying or ready to replay
        if tables_exist and routing_count == 7 and cp_count == EXPECTED_CHECKPOINTS_COUNT:
            if not target_b:
                print("Capturing target prefix B from WRITE event counters (isolated)...")
                raw_counters = psql_write_readonly("SELECT family, position FROM v3_write.v3_event_counter;")
                target_b = {}
                for line in raw_counters.splitlines():
                    p = line.split("|")
                    if len(p) >= 2:
                        target_b[p[0]] = int(p[1])
                transition("MAINTENANCE", "REBUILDING", "REPLAYING", operation_id, active_op,
                           extra_sets=f", target_prefix = '{json.dumps(target_b)}'::jsonb")
            else:
                if current_state != "REBUILDING":
                    transition("MAINTENANCE", "REBUILDING", "REPLAYING", operation_id, active_op)

            print("Starting replay worker...")
            subprocess.run(["docker", "compose", *COMPOSE_FILES, "up", "-d", "runtime-monolith"], check=True, capture_output=True)

            print(f"Monitoring convergence towards target prefix B (timeout: {args.watchdog_timeout}s)...")
            start_time = time.time()
            converged = False
            while time.time() - start_time < args.watchdog_timeout:
                ready, cp_ok, slot_ok, int_ok, diag = evaluate_readiness_oracle(target_b)
                elapsed = int(time.time() - start_time)
                print(f"    [{elapsed:3d}s] {diag}")
                if ready:
                    converged = True
                    break
                time.sleep(args.poll_interval)

            if not converged:
                print(f"FATAL: Watchdog timed out waiting for P_READY!", file=sys.stderr)
                transition("REBUILDING", "FAILED", current_phase, operation_id, active_op,
                           extra_sets=f", error_detail = 'Watchdog timeout ({args.watchdog_timeout}s)'")
                return 1

            transition("REBUILDING", "REBUILDING", "CONVERGED", operation_id, active_op)
            transition("REBUILDING", "READY", "IDLE", operation_id, active_op,
                       extra_sets=", operator_id = NULL, operation_id = NULL, error_detail = NULL")
            print("✓ Reconstruction converged and restored to READY via --resume.")
            return 0
        else:
            print("FATAL: Inconsistent physical state for non-destructive resume.", file=sys.stderr)
            print("To reconstruct safely, restart with --confirm-destructive=RESET_READ_DATA", file=sys.stderr)
            return 1

    # -------------------------------------------------------------------------
    # FRESH DESTRUCTIVE REBUILD
    # -------------------------------------------------------------------------
    operation_id = str(uuid.uuid4())

    print("================================================================================")
    print(f" Pocoma V3 — Starting READ Reconstruction [Operator: {args.operator_id}]")
    print(f" Target: {READ_DB}.{READ_SCHEMA} | Operation ID: {operation_id}")
    print("================================================================================")

    # Step 1: Atomic Compare-and-Set Acquisition
    print("1. Acquiring MAINTENANCE lock via atomic PostgreSQL CAS...")
    acquire_sql = (
        f"UPDATE public.read_maintenance_state SET "
        f"  state = 'MAINTENANCE', phase = 'DRAINING', "
        f"  operator_id = '{args.operator_id}', "
        f"  operation_id = '{operation_id}'::uuid, "
        f"  started_at = clock_timestamp(), updated_at = clock_timestamp(), "
        f"  error_detail = NULL "
        f"WHERE id = 1 AND state = 'READY' AND phase = 'IDLE' "
        f"RETURNING id;"
    )
    acquired = psql_admin(READ_DB, acquire_sql, check=False)
    if not acquired:
        cur = get_current_maintenance_state()
        c_state = cur.get("state") if cur else "UNKNOWN"
        c_op = cur.get("operator_id") if cur else "UNKNOWN"
        c_op_id = cur.get("operation_id") if cur else "UNKNOWN"
        if c_state == "FAILED":
            print(
                f"ERROR: Cannot acquire maintenance: authority is in FAILED state. "
                f"Execute --reset-failed first.",
                file=sys.stderr
            )
        else:
            print(
                f"ERROR: Cannot acquire maintenance lock: another operation is active by operator '{c_op}' "
                f"(state: {c_state}, operation: {c_op_id}). Concurrent acquisition rejected.",
                file=sys.stderr
            )
        return 1

    print("   ✓ Atomic CAS succeeded: lock acquired (state: MAINTENANCE, phase: DRAINING)")

    # Step 2: Draining active HTTP in-flight requests
    print("2. Draining active HTTP in-flight requests (3s delay)...")
    time.sleep(3)
    print("   ✓ Drainage delay completed.")

    # Step 3: Gracefully stopping active READ workers
    print("3. Neutralizing and stopping active READ workers...")
    print("   • Runtime architecture note: In local functional mode, 'runtime-monolith' co-locates READ and WRITE runtimes in one container. Stopping the container temporarily pauses WRITE admission during DDL in this local single-container topology. (In distributed production topology, read-worker containers are stopped independently from write-admission).")
    subprocess.run(["docker", "compose", *COMPOSE_FILES, "stop", "runtime-monolith"], check=True, capture_output=True)
    print("   ✓ Worker processes stopped.")

    # Step 4: Graceful verification of SQL sessions (AUD-L6-I2-05)
    print("4. Verifying termination of residual SQL sessions on pocoma_read...")
    sessions_cleared = False
    for attempt in range(10):
        active_sessions = psql_admin(
            READ_DB,
            f"SELECT count(*) FROM pg_stat_activity WHERE datname = '{READ_DB}' AND usename = '{READ_ROLE}';"
        )
        if int(active_sessions) == 0:
            sessions_cleared = True
            break
        time.sleep(0.5)

    if not sessions_cleared:
        print(f"   • Lingering sessions detected ({active_sessions}). Executing exceptional termination...")
        psql_admin(
            READ_DB,
            f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
            f"WHERE datname = '{READ_DB}' AND usename = '{READ_ROLE}' AND pid <> pg_backend_pid();"
        )
        remaining = psql_admin(
            READ_DB,
            f"SELECT count(*) FROM pg_stat_activity WHERE datname = '{READ_DB}' AND usename = '{READ_ROLE}';"
        )
        if int(remaining) > 0:
            print(f"FATAL: Residual sessions remained ({remaining}) on {READ_DB} after termination!", file=sys.stderr)
            transition("MAINTENANCE", "FAILED", "DRAINING", operation_id, args.operator_id,
                       extra_sets=", error_detail = 'Lingering SQL sessions could not be terminated'")
            return 1

    print("   ✓ All residual runtime SQL sessions cleared (0 active).")

    # Step 5: Revoking permissions on schema v3_read
    print("5. Revoking USAGE permissions on schema v3_read...")
    psql_admin(READ_DB, f"REVOKE ALL ON ALL TABLES IN SCHEMA {READ_SCHEMA} FROM {READ_ROLE};", check=False)
    psql_admin(READ_DB, f"REVOKE USAGE ON SCHEMA {READ_SCHEMA} FROM {READ_ROLE};", check=False)
    # Postcondition: verify USAGE revoked
    has_usage = psql_admin(READ_DB, f"SELECT has_schema_privilege('{READ_ROLE}', '{READ_SCHEMA}', 'USAGE');")
    if has_usage.lower() == "t":
        print(f"FATAL: Failed to revoke USAGE on {READ_SCHEMA} from {READ_ROLE}!", file=sys.stderr)
        return 1
    transition("MAINTENANCE", "MAINTENANCE", "EXCLUDED", operation_id, args.operator_id)
    print("   ✓ USAGE on v3_read revoked (phase: EXCLUDED).")

    # Step 6: Dropping schema v3_read CASCADE (AUD-L6-I2-02)
    print("6. Dropping schema v3_read CASCADE...")
    psql_admin(READ_DB, f"DROP SCHEMA IF EXISTS {READ_SCHEMA} CASCADE;")
    # Postcondition: verify schema is completely gone
    ns = psql_admin(READ_DB, f"SELECT to_regnamespace('{READ_SCHEMA}')::text;")
    if ns and ns != "":
        print(f"FATAL: Schema {READ_SCHEMA} still exists after DROP CASCADE!", file=sys.stderr)
        return 1
    transition("MAINTENANCE", "MAINTENANCE", "SCHEMA_DROPPED", operation_id, args.operator_id)
    print("   ✓ Schema v3_read dropped and verified absent (phase: SCHEMA_DROPPED).")

    # Step 7: Recreating schema v3_read
    print("7. Recreating schema v3_read...")
    psql_admin(READ_DB, f"CREATE SCHEMA {READ_SCHEMA} AUTHORIZATION {POSTGRES_ADMIN_USER};")
    psql_admin(READ_DB, f"GRANT USAGE ON SCHEMA {READ_SCHEMA} TO {READ_ROLE};")
    # Postcondition: verify schema exists and usage granted
    ns_created = psql_admin(READ_DB, f"SELECT to_regnamespace('{READ_SCHEMA}')::text;")
    if not ns_created or ns_created == "":
        print(f"FATAL: Failed to create schema {READ_SCHEMA}!", file=sys.stderr)
        return 1
    transition("MAINTENANCE", "MAINTENANCE", "SCHEMA_CREATED", operation_id, args.operator_id)
    print("   ✓ Clean schema v3_read created and verified (phase: SCHEMA_CREATED).")

    # Step 8: Apply Canonical Versioned Migrations (AUD-L6-I2-07)
    print("8. Applying canonical versioned DDL migration scripts (V1..V7, V10)...")
    read_migrations = [
        "app/infra-persistence-read-jdbc/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql",
        "app/infra-persistence-read-jdbc/src/main/resources/db/v3-read-business/migration/V2__subobject_version.sql",
        "app/infra-persistence-read-jdbc/src/main/resources/db/v3-read-business/migration/V3__pot_version.sql",
        "app/infra-persistence-read-jdbc/src/main/resources/db/v3-read-business/migration/V4__pot_balance.sql",
        "app/infra-persistence-read-jdbc/src/main/resources/db/v3-read-business/migration/V5__pot_visibility.sql",
        "app/infra-persistence-read-jdbc/src/main/resources/db/v3-read-business/migration/V6__current_binding.sql",
        "app/infra-persistence-read-jdbc/src/main/resources/db/v3-read-business/migration/V7__terminal_results.sql",
        "app/infra-persistence-read-jdbc/src/main/resources/db/v3-read-invitation/migration/V10__current_invitation.sql",
    ]
    apply_migrations(READ_DB, READ_SCHEMA, POSTGRES_ADMIN_USER, READ_ROLE, read_migrations)
    # Postcondition: verify all canonical tables exist
    if not check_tables_exist(CANONICAL_MIGRATION_TABLES):
        print("FATAL: Postcondition failed: Not all canonical migration tables were created!", file=sys.stderr)
        return 1
    transition("MAINTENANCE", "MAINTENANCE", "MIGRATED", operation_id, args.operator_id)
    print("   ✓ Migrations V1..V7, V10 applied and 14 tables verified (phase: MIGRATED).")

    # Step 9-10: Bootstrap Routing and Initialize Checkpoints
    print("9. Bootstrapping routing contracts and permissions...")
    scripts = [
        ("scripts/v3/bootstrap-l2-routing.sql", {
            "expected_database": READ_DB,
            "expected_schema": READ_SCHEMA,
            "expected_admin": POSTGRES_ADMIN_USER,
            "authority": "READ",
        }),
        ("scripts/v3/grant-l2-lifecycle.sql", {
            "expected_database": READ_DB,
            "expected_schema": READ_SCHEMA,
            "expected_admin": POSTGRES_ADMIN_USER,
            "runtime_role": READ_ROLE,
            "reactivation_role": "pocoma_react",
            "identity_authority": "real",
            "identity_read_role": READ_ROLE,
        }),
        ("scripts/v3/grant-l2-discovery.sql", {
            "expected_database": READ_DB,
            "expected_schema": READ_SCHEMA,
            "expected_admin": POSTGRES_ADMIN_USER,
            "runtime_role": READ_ROLE,
            "reactivation_role": "pocoma_react",
            "identity_authority": "real",
            "identity_read_role": READ_ROLE,
        }),
    ]
    for script_file, variables in scripts:
        content = (REPO_ROOT / script_file).read_text(encoding="utf-8")
        psql_script(READ_DB, READ_SCHEMA, content, variables=variables)

    print("10. Initializing 28 discovery checkpoints to position 0...")
    psql_admin(
        READ_DB,
        f"INSERT INTO {READ_SCHEMA}.discovery_checkpoint (consumption_id, partition_id, position) "
        f"SELECT consumption_id, p, 0 "
        f"FROM {READ_SCHEMA}.consumption_routing_contract "
        f"CROSS JOIN generate_series(0, 3) p "
        f"ON CONFLICT (consumption_id, partition_id) DO NOTHING;"
    )
    # Postcondition: verify 7 contracts and 28 checkpoints
    r_count = int(psql_admin(READ_DB, f"SELECT count(*) FROM {READ_SCHEMA}.consumption_routing_contract;"))
    c_count = int(psql_admin(READ_DB, f"SELECT count(*) FROM {READ_SCHEMA}.discovery_checkpoint;"))
    if r_count != 7 or c_count != EXPECTED_CHECKPOINTS_COUNT:
        print(f"FATAL: Routing bootstrap incomplete! (contracts: {r_count}/7, checkpoints: {c_count}/28)", file=sys.stderr)
        return 1
    transition("MAINTENANCE", "MAINTENANCE", "ROUTED", operation_id, args.operator_id)
    print("    ✓ Routing contracts (7/7) and checkpoints (28/28) initialized (phase: ROUTED).")

    # Step 11: Capture Target Prefix B from WRITE (Isolated via pocoma_read role)
    print("11. Capturing reference target prefix B from WRITE event counters (role: pocoma_read, read-only)...")
    raw_counters = psql_write_readonly(f"SELECT family, position FROM {WRITE_SCHEMA}.v3_event_counter;")
    target_b = {}
    if raw_counters:
        for line in raw_counters.splitlines():
            parts = line.split("|")
            if len(parts) >= 2:
                target_b[parts[0]] = int(parts[1])

    # Ensure all expected families are present
    missing_b = EXPECTED_WRITE_FAMILIES - set(target_b.keys())
    if missing_b:
        print(f"FATAL: WRITE database missing expected event counter families: {missing_b}", file=sys.stderr)
        return 1

    print(f"    ✓ Target prefix B captured: {json.dumps(target_b)}")
    transition("MAINTENANCE", "REBUILDING", "REPLAYING", operation_id, args.operator_id,
               extra_sets=f", target_prefix = '{json.dumps(target_b)}'::jsonb")
    print("    ✓ State transitioned to REBUILDING (phase: REPLAYING).")

    # Step 12: Start Replay Worker
    print("12. Starting authorized replay worker...")
    subprocess.run(["docker", "compose", *COMPOSE_FILES, "up", "-d", "runtime-monolith"], check=True, capture_output=True)
    print("    ✓ Replay worker active.")

    # Step 13: Watchdog & Convergence Monitoring via Readiness Oracle
    print(f"13. Monitoring convergence towards target prefix B (timeout: {args.watchdog_timeout}s)...")
    start_time = time.time()
    converged = False

    while time.time() - start_time < args.watchdog_timeout:
        ready, cp_ok, slot_ok, int_ok, diag = evaluate_readiness_oracle(target_b)
        elapsed = int(time.time() - start_time)
        print(f"    [{elapsed:3d}s] {diag}")

        if ready:
            converged = True
            break
        time.sleep(args.poll_interval)

    if not converged:
        print(f"FATAL: Rebuild convergence watchdog timed out after {args.watchdog_timeout}s!", file=sys.stderr)
        transition("REBUILDING", "FAILED", "REPLAYING", operation_id, args.operator_id,
                   extra_sets=f", error_detail = 'Watchdog timeout ({args.watchdog_timeout}s) waiting for P_READY convergence'")
        return 1

    print("    ✓ Readiness Oracle evaluated to TRUE: P_READY(B) fully satisfied!")
    transition("REBUILDING", "REBUILDING", "CONVERGED", operation_id, args.operator_id)
    print("    ✓ Authority transitioned to CONVERGED.")

    # Step 14: Transition to READY
    print("14. Restoring production service (state = READY, phase = IDLE)...")
    transition("REBUILDING", "READY", "IDLE", operation_id, args.operator_id,
               extra_sets=", operator_id = NULL, operation_id = NULL, error_detail = NULL")
    print("    ✓ Authority transitioned to READY.")

    # Step 15: Discriminant Smoke Tests (AUD-L6-I2-12)
    print("15. Executing post-reconstruction discriminant HTTP smoke tests...")
    time.sleep(1)  # Allow filter cache invalidation
    curl_res = subprocess.run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "http://127.0.0.1:8080/api/v3/me/binding"],
        capture_output=True, text=True
    )
    http_code = curl_res.stdout.strip()
    if http_code == "000":
        print("FATAL: HTTP server unreachable (code 000)!", file=sys.stderr)
        return 1
    if http_code == "503":
        print("FATAL: HTTP service still returning 503 after READY transition!", file=sys.stderr)
        return 1
    if http_code not in ("200", "401"):
        print(f"WARNING: Unexpected HTTP response {http_code} after restoration", file=sys.stderr)
        return 1

    print(f"    ✓ HTTP smoke test successful: received HTTP {http_code} (normal service active, security enforced)")

    print("================================================================================")
    print(" Pocoma V3 — READ Reconstruction Completed Successfully.")
    print("================================================================================")
    return 0


if __name__ == "__main__":
    sys.exit(main())
