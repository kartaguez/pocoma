#!/usr/bin/env python3
"""Pocoma V3 — READ Reconstruction Orchestration Script.

Orchestrates the complete, safe, and observable destruction, migration, and replay
convergence of the READ storage tier (pocoma_read.v3_read) from canonical WRITE events.

Usage:
  python3 scripts/operations/v3-rebuild-read.py --confirm-destructive=RESET_READ_DATA [OPTIONS]
  python3 scripts/operations/v3-rebuild-read.py --status
  python3 scripts/operations/v3-rebuild-read.py --resume

Invariants:
- Zero mutations or event deletions on WRITE (pocoma_write).
- Strict isolation: WRITE connections are explicitly read-only.
- Fail-closed: HTTP 503 is maintained while not READY.
- Resilient: public.read_maintenance_state persists across DROP SCHEMA CASCADE.
"""

import argparse
import getpass
import json
from pathlib import Path
import subprocess
import sys
import time

REPO_ROOT = Path(__file__).resolve().parents[2]
POSTGRES_CONTAINER = "pocoma-functional-postgres-1"
POSTGRES_USER = "pocoma"
POSTGRES_PASSWORD = "pocoma-local-only"
READ_DB = "pocoma_read"
WRITE_DB = "pocoma_write"
READ_SCHEMA = "v3_read"
WRITE_SCHEMA = "v3_write"
READ_ROLE = "pocoma_read"
COMPOSE_FILES = [
    "-f", str(REPO_ROOT / "docker-compose.functional.yml"),
    "-f", str(REPO_ROOT / "docker/compose/docker-compose.observability.yml"),
]

EXPECTED_CHECKPOINTS_COUNT = 28  # 7 consumptions x 4 partitions


def psql(db, sql_command, check=True, read_only=False):
    """Execute SQL command via docker exec in postgres container."""
    schema = "v3_read" if "read" in db else "v3_write"
    pgoptions = f"-c search_path={schema},public"
    if read_only:
        pgoptions += " -c default_transaction_read_only=on"
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        "-e", f"PGOPTIONS={pgoptions}",
        POSTGRES_CONTAINER,
        "psql", "-X", "-U", POSTGRES_USER, "-d", db, "-v", "ON_ERROR_STOP=1", "-tAc", sql_command
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"psql failed on {db}: {res.stderr.strip()} (cmd: {sql_command})")
    return res.stdout.strip()


def psql_script(db, schema, script_content, variables=None):
    """Execute SQL script content via docker exec in postgres container."""
    vars_args = []
    if variables:
        for k, v in variables.items():
            vars_args.extend(["-v", f"{k}={v}"])
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        "-e", f"PGOPTIONS=-c search_path={schema},public",
        POSTGRES_CONTAINER,
        "psql", "-X", "-U", POSTGRES_USER, "-d", db, "-v", "ON_ERROR_STOP=1",
        *vars_args, "-f", "-"
    ]
    res = subprocess.run(cmd, input=script_content, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"psql script failed on {db}.{schema}: {res.stderr.strip()}")
    return res.stdout.strip()


def get_current_maintenance_state():
    """Retrieve current row from public.read_maintenance_state."""
    raw = psql(
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
    print(f"  Started at:    {st.get('started_at') or '(none)'}")
    print(f"  Updated at:    {st.get('updated_at')}")
    print(f"  Target Prefix: {json.dumps(st.get('target_prefix')) if st.get('target_prefix') else '(none)'}")
    if st.get('error_detail'):
        print(f"  Error Detail:  {st.get('error_detail')}")

    # Checkpoint and slot summary
    try:
        cp_count = psql(READ_DB, "SELECT count(*) FROM v3_read.discovery_checkpoint;", check=False)
        slot_count = psql(READ_DB, "SELECT count(*) FROM v3_read.consumption_slot;", check=False)
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


def evaluate_readiness_oracle(target_prefix):
    """Evaluates mathematical readiness oracle P_READY(B) on live database."""
    # 1. Condition C_checkpoints
    cp_rows = psql(
        READ_DB,
        "SELECT crc.family, dc.partition_id, dc.position "
        "FROM v3_read.discovery_checkpoint dc "
        "JOIN v3_read.consumption_routing_contract crc ON dc.consumption_id = crc.consumption_id "
        "WHERE crc.partition_count = 4;"
    )
    satisfied_checkpoints = 0
    total_checkpoints = 0
    if cp_rows:
        for line in cp_rows.splitlines():
            parts = line.split("|")
            if len(parts) >= 3:
                family, part_id, pos_str = parts[0], int(parts[1]), int(parts[2])
                total_checkpoints += 1
                write_fam = family.replace("_EVENT", "") if family.endswith("_EVENT") else family
                target_pos = target_prefix.get(write_fam, target_prefix.get(family, 0))
                if pos_str >= target_pos:
                    satisfied_checkpoints += 1

    checkpoints_ok = (satisfied_checkpoints == EXPECTED_CHECKPOINTS_COUNT) and (total_checkpoints >= EXPECTED_CHECKPOINTS_COUNT)

    # 2. Condition C_slots
    slot_rows = psql(
        READ_DB,
        "SELECT cs.consumable_id, crc.family, cs.state "
        "FROM v3_read.consumption_slot cs "
        "JOIN v3_read.consumption_routing_contract crc ON cs.consumption_id = crc.consumption_id;"
    )
    residual_slots = 0
    quarantined_slots = 0
    if slot_rows:
        for line in slot_rows.splitlines():
            parts = line.split("|")
            if len(parts) >= 3:
                slot_id, family, state = parts[0], parts[1], parts[2]
                write_fam = family.replace("_EVENT", "") if family.endswith("_EVENT") else family
                target_pos = target_prefix.get(write_fam, target_prefix.get(family, 0))

                pos_query = psql(
                    WRITE_DB,
                    f"SELECT position FROM v3_write.v3_event_position WHERE event_id = '{slot_id}' AND family = '{write_fam}';",
                    check=False,
                    read_only=True
                )
                pos = int(pos_query) if pos_query and pos_query.isdigit() else 999999999
                if pos <= target_pos:
                    residual_slots += 1
                    if state.upper() == "QUARANTINED":
                        quarantined_slots += 1

    slots_ok = (residual_slots == 0)

    # 3. Condition C_integrity
    integrity_errors = []
    # 3.1: CurrentPot -> PotVersion
    dangling_pots = psql(
        READ_DB,
        "SELECT count(*) FROM v3_read.read_current_pot cp "
        "LEFT JOIN v3_read.read_pot_version pv ON cp.pot_id = pv.pot_id AND cp.pot_version = pv.pot_version "
        "WHERE pv.pot_id IS NULL;"
    )
    if dangling_pots and int(dangling_pots) > 0:
        integrity_errors.append(f"{dangling_pots} dangling current_pot references")

    # 3.2: CurrentPot -> PotBalance
    missing_balance = psql(
        READ_DB,
        "SELECT count(*) FROM v3_read.read_current_pot cp "
        "LEFT JOIN v3_read.read_pot_balance pb ON cp.pot_id = pb.pot_id AND cp.pot_version = pb.pot_version "
        "WHERE pb.pot_id IS NULL;"
    )
    if missing_balance and int(missing_balance) > 0:
        integrity_errors.append(f"{missing_balance} missing pot balance rows")

    # 3.3: CurrentPot -> PotVisibility
    missing_visibility = psql(
        READ_DB,
        "SELECT count(*) FROM v3_read.read_current_pot cp "
        "LEFT JOIN v3_read.read_pot_visibility_state vs ON cp.pot_id = vs.pot_id AND cp.pot_version <= vs.materialized_version "
        "WHERE vs.pot_id IS NULL;"
    )
    if missing_visibility and int(missing_visibility) > 0:
        integrity_errors.append(f"{missing_visibility} missing pot visibility rows")

    integrity_ok = len(integrity_errors) == 0

    ready = checkpoints_ok and slots_ok and integrity_ok
    diagnostic = (
        f"CP: {satisfied_checkpoints}/{EXPECTED_CHECKPOINTS_COUNT} | "
        f"Slots<=B: {residual_slots} (Q: {quarantined_slots}) | "
        f"Integrity: {'OK' if integrity_ok else ', '.join(integrity_errors)}"
    )
    return ready, checkpoints_ok, slots_ok, integrity_ok, diagnostic


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
        help="Resume an interrupted or crashed rebuild"
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

    # Preflight 1: Destructive confirmation check
    if not args.resume and args.confirm_destructive != "RESET_READ_DATA":
        print("ERROR: Destructive rebuild requires --confirm-destructive=RESET_READ_DATA", file=sys.stderr)
        return 1

    # Preflight 2: Target database validation
    if READ_DB == WRITE_DB or "write" in READ_DB.lower():
        print(f"FATAL: Target database '{READ_DB}' violates WRITE isolation barrier!", file=sys.stderr)
        return 1

    # Preflight 3: PostgreSQL container check
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

    # Preflight 4: Maintenance authority table existence
    maint_exists = psql(READ_DB, "SELECT to_regclass('public.read_maintenance_state')::text;")
    if not maint_exists:
        print("ERROR: public.read_maintenance_state table does not exist in pocoma_read.", file=sys.stderr)
        return 1

    # Preflight 5: Concurrency check
    current = get_current_maintenance_state()
    if not current:
        print("ERROR: Could not query maintenance state.", file=sys.stderr)
        return 1

    current_state = current.get("state")
    current_phase = current.get("phase")
    active_op = current.get("operator_id")

    if not args.resume and current_state not in ("READY", "FAILED"):
        print(
            f"ERROR: Another rebuild operation is active by operator '{active_op}' "
            f"(state: {current_state}, phase: {current_phase}). "
            f"Use --resume if recovering, or wait for completion.",
            file=sys.stderr
        )
        return 1

    if args.dry_run:
        print("✓ Preflight checks passed. Dry run complete; no changes applied.")
        return 0

    print("================================================================================")
    print(f" Pocoma V3 — Starting READ Reconstruction [Operator: {args.operator_id}]")
    print(f" Target: {READ_DB}.{READ_SCHEMA} | Mode: {'RESUME' if args.resume else 'FRESH'}")
    print("================================================================================")

    # RESUME DISPATCH LOGIC
    is_resuming = args.resume and current_state in ("MAINTENANCE", "REBUILDING")
    target_b = current.get("target_prefix") if is_resuming else None
    phase_order = [
        "IDLE", "DRAINING", "EXCLUDED", "SCHEMA_DROPPED",
        "SCHEMA_CREATED", "MIGRATED", "ROUTED", "REPLAYING", "CONVERGED"
    ]
    cur_phase_idx = phase_order.index(current_phase) if current_phase in phase_order else 0

    if is_resuming:
        print(f"Resuming active rebuild in phase: {current_phase} (state: {current_state})")

    # Step 1-7: Acquire Maintenance, Drain, Neutralize, Drop and Recreate Schema
    if not is_resuming or cur_phase_idx < phase_order.index("SCHEMA_CREATED") or current_state == "FAILED":
        print("1. Acquiring MAINTENANCE lock in public.read_maintenance_state...")
        psql(
            READ_DB,
            f"UPDATE public.read_maintenance_state SET "
            f"  state = 'MAINTENANCE', phase = 'DRAINING', "
            f"  operator_id = '{args.operator_id}', "
            f"  started_at = clock_timestamp(), updated_at = clock_timestamp(), "
            f"  error_detail = NULL "
            f"WHERE id = 1;"
        )
        print("   ✓ State set to MAINTENANCE (phase: DRAINING)")

        print("2. Draining active HTTP in-flight requests (3s)...")
        time.sleep(3)
        print("   ✓ Drainage delay completed.")

        print("3. Neutralizing and stopping active READ workers...")
        subprocess.run(
            ["docker", "compose", *COMPOSE_FILES, "stop", "runtime-monolith"],
            check=True, capture_output=True
        )
        print("   ✓ Workers stopped.")

        print("4. Terminating residual SQL sessions on pocoma_read...")
        psql(
            READ_DB,
            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
            f"WHERE datname = '{READ_DB}' AND usename = '{READ_ROLE}' AND pid <> pg_backend_pid();"
        )
        remaining = psql(
            READ_DB,
            f"SELECT count(*) FROM pg_stat_activity WHERE datname = '{READ_DB}' AND usename = '{READ_ROLE}';"
        )
        if int(remaining) > 0:
            print(f"FATAL: Residual sessions remained ({remaining}) on {READ_DB}", file=sys.stderr)
            return 1
        print("   ✓ All residual runtime SQL sessions eliminated (pg_stat_activity == 0).")

        print("5. Revoking USAGE permissions on schema v3_read...")
        psql(READ_DB, f"UPDATE public.read_maintenance_state SET phase = 'EXCLUDED', updated_at = clock_timestamp();")
        psql(READ_DB, f"REVOKE ALL ON ALL TABLES IN SCHEMA {READ_SCHEMA} FROM {READ_ROLE};", check=False)
        psql(READ_DB, f"REVOKE USAGE ON SCHEMA {READ_SCHEMA} FROM {READ_ROLE};", check=False)
        print("   ✓ USAGE on v3_read revoked from pocoma_read.")

        print("6. Dropping schema v3_read CASCADE...")
        psql(READ_DB, f"UPDATE public.read_maintenance_state SET phase = 'SCHEMA_DROPPED', updated_at = clock_timestamp();")
        psql(READ_DB, f"DROP SCHEMA IF EXISTS {READ_SCHEMA} CASCADE;")
        print("   ✓ Schema v3_read dropped.")

        print("7. Recreating schema v3_read...")
        psql(READ_DB, f"CREATE SCHEMA {READ_SCHEMA} AUTHORIZATION {POSTGRES_USER};")
        psql(READ_DB, f"GRANT USAGE ON SCHEMA {READ_SCHEMA} TO {READ_ROLE};")
        psql(READ_DB, f"UPDATE public.read_maintenance_state SET phase = 'SCHEMA_CREATED', updated_at = clock_timestamp();")
        print("   ✓ Clean schema v3_read created.")

    # Step 8: Apply Flyway Migrations
    if not is_resuming or cur_phase_idx < phase_order.index("MIGRATED") or current_state == "FAILED":
        print("8. Applying canonical Flyway migrations to v3_read...")
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
        apply_migrations(READ_DB, READ_SCHEMA, POSTGRES_USER, READ_ROLE, read_migrations)
        psql(READ_DB, f"UPDATE public.read_maintenance_state SET phase = 'MIGRATED', updated_at = clock_timestamp();")
        print("   ✓ Migrations V1..V7, V10 applied successfully.")

    # Step 9-10: Bootstrap Routing and Initialize Checkpoints
    if not is_resuming or cur_phase_idx < phase_order.index("ROUTED") or current_state == "FAILED":
        print("9. Bootstrapping routing contracts and permissions...")
        scripts = [
            ("scripts/v3/bootstrap-l2-routing.sql", {
                "expected_database": READ_DB,
                "expected_schema": READ_SCHEMA,
                "expected_admin": POSTGRES_USER,
                "authority": "READ",
            }),
            ("scripts/v3/grant-l2-lifecycle.sql", {
                "expected_database": READ_DB,
                "expected_schema": READ_SCHEMA,
                "expected_admin": POSTGRES_USER,
                "runtime_role": READ_ROLE,
                "reactivation_role": "pocoma_react",
                "identity_authority": "real",
                "identity_read_role": READ_ROLE,
            }),
            ("scripts/v3/grant-l2-discovery.sql", {
                "expected_database": READ_DB,
                "expected_schema": READ_SCHEMA,
                "expected_admin": POSTGRES_USER,
                "runtime_role": READ_ROLE,
                "reactivation_role": "pocoma_react",
                "identity_authority": "real",
                "identity_read_role": READ_ROLE,
            }),
        ]
        routing_count = psql(READ_DB, f"SELECT count(*) FROM {READ_SCHEMA}.consumption_routing_contract;", check=False)
        if not routing_count or not routing_count.isdigit() or int(routing_count) != 7:
            for script_file, variables in scripts:
                content = (REPO_ROOT / script_file).read_text(encoding="utf-8")
                psql_script(READ_DB, READ_SCHEMA, content, variables=variables)
        else:
            print("    • Routing contracts already present (7/7). Applying lifecycle and discovery grants...")
            psql(READ_DB, f"REVOKE ALL ON {READ_SCHEMA}.consumption_routing_contract, {READ_SCHEMA}.partition_ownership, {READ_SCHEMA}.discovery_checkpoint, {READ_SCHEMA}.consumption_slot FROM {READ_ROLE}, pocoma_react; GRANT SELECT ON {READ_SCHEMA}.consumption_routing_contract, {READ_SCHEMA}.partition_ownership, {READ_SCHEMA}.discovery_checkpoint, {READ_SCHEMA}.consumption_slot TO {READ_ROLE};", check=False)
            for script_file, variables in scripts[1:]:
                content = (REPO_ROOT / script_file).read_text(encoding="utf-8")
                psql_script(READ_DB, READ_SCHEMA, content, variables=variables)

        print("10. Initializing 28 discovery checkpoints to position 0...")
        psql(
            READ_DB,
            f"INSERT INTO {READ_SCHEMA}.discovery_checkpoint (consumption_id, partition_id, position) "
            f"SELECT consumption_id, p, 0 "
            f"FROM {READ_SCHEMA}.consumption_routing_contract "
            f"CROSS JOIN generate_series(0, 3) p "
            f"ON CONFLICT (consumption_id, partition_id) DO NOTHING;"
        )
        psql(READ_DB, f"UPDATE public.read_maintenance_state SET phase = 'ROUTED', updated_at = clock_timestamp();")
        print("    ✓ Routing contracts and 28 checkpoints initialized.")

    # Step 11: Capture Target Prefix B from WRITE (Read-Only)
    if not target_b:
        print("11. Capturing reference target prefix B from WRITE event counters (read-only)...")
        raw_counters = psql(
            WRITE_DB,
            f"SELECT family, position FROM {WRITE_SCHEMA}.v3_event_counter;",
            read_only=True
        )
        target_b = {}
        if raw_counters:
            for line in raw_counters.splitlines():
                parts = line.split("|")
                if len(parts) >= 2:
                    target_b[parts[0]] = int(parts[1])

        print(f"    ✓ Target prefix B captured: {json.dumps(target_b)}")
        psql(
            READ_DB,
            f"UPDATE public.read_maintenance_state SET "
            f"  state = 'REBUILDING', phase = 'REPLAYING', "
            f"  target_prefix = '{json.dumps(target_b)}'::jsonb, "
            f"  updated_at = clock_timestamp();"
        )
    else:
        print(f"11. Using preserved target prefix B: {json.dumps(target_b)}")

    # Step 12: Start Replay Worker
    print("12. Starting authorized replay worker...")
    subprocess.run(
        ["docker", "compose", *COMPOSE_FILES, "up", "-d", "runtime-monolith"],
        check=True, capture_output=True
    )
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
        psql(
            READ_DB,
            f"UPDATE public.read_maintenance_state SET "
            f"  state = 'FAILED', "
            f"  error_detail = 'Watchdog timeout ({args.watchdog_timeout}s) waiting for P_READY convergence', "
            f"  updated_at = clock_timestamp();"
        )
        return 1

    print("    ✓ Readiness Oracle evaluated to TRUE: P_READY(B) fully satisfied!")
    psql(READ_DB, "UPDATE public.read_maintenance_state SET phase = 'CONVERGED', updated_at = clock_timestamp();")

    # Step 14: Transition to READY
    print("14. Restoring production service (state = READY, phase = IDLE)...")
    psql(
        READ_DB,
        "UPDATE public.read_maintenance_state SET "
        "  state = 'READY', phase = 'IDLE', "
        "  operator_id = NULL, error_detail = NULL, "
        "  updated_at = clock_timestamp();"
    )
    print("    ✓ Authority transitioned to READY.")

    # Step 15: Smoke Test HTTP
    print("15. Executing post-reconstruction HTTP smoke test...")
    time.sleep(1)  # Allow filter cache invalidation
    curl_res = subprocess.run(
        ["curl", "-s", "-o", "/dev/null", "-w", "%{http_code}", "http://127.0.0.1:8080/api/v3/me/binding"],
        capture_output=True, text=True
    )
    http_code = curl_res.stdout.strip()
    if http_code in ("200", "401"):
        print(f"    ✓ HTTP smoke test successful: received HTTP {http_code} (non-503 normal service response)")
    else:
        print(f"WARNING: Unexpected HTTP response {http_code} after restoration", file=sys.stderr)

    print("================================================================================")
    print(" Pocoma V3 — READ Reconstruction Completed Successfully.")
    print("================================================================================")
    return 0


if __name__ == "__main__":
    sys.exit(main())
