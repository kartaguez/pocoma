#!/usr/bin/env python3
"""Pocoma V3 — Populated Database Generator for Sequential Burnable Discovery Qualification.

Generates realistic and reproducible PostgreSQL populations (H1..H5) with all
triggers and constraints strictly enabled (zero trigger/constraint bypass).

Profiles:
  - H1: Empty baseline (0 historical commands, 0 historical identity requests, 0 quarantines, 0 extra events)
  - H2: Completed history (10,000 TERMINAL commands, 2,000 TERMINAL identity requests, 100,000 published events, 0 quarantines)
  - H3: R11-like quarantine pressure (10,000 TERMINAL commands, 300 QUARANTINED commands across 4 partitions, 50 QUARANTINED identity requests across 4 partitions)
  - H4: Heavy populated database (50,000 TERMINAL commands, 800 QUARANTINED commands, 200 QUARANTINED identity requests, 500,000 published events)
  - H5: Volumetric stress (100,000 TERMINAL commands, 2,000 QUARANTINED commands, 500 QUARANTINED identity requests, 1,000,000 published events)
"""

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Any, Dict

POSTGRES_CONTAINER = os.environ.get("POSTGRES_CONTAINER", "pocoma-v3-postgres")
POSTGRES_USER = os.environ.get("POSTGRES_USER", "pocoma")
POSTGRES_PASSWORD = os.environ.get("POSTGRES_PASSWORD", "pocoma-local-only")
WRITE_DB = os.environ.get("WRITE_DB", "pocoma_write")
WRITE_SCHEMA = os.environ.get("WRITE_SCHEMA", "v3_write")
READ_DB = os.environ.get("READ_DB", "pocoma_read")
READ_SCHEMA = os.environ.get("READ_SCHEMA", "v3_read")

RUNTIME_PREFIX = os.environ.get("POCOMA_RUNTIME_PREFIX", "")
MANAGEMENT_PORT_OFFSET = int(os.environ.get("POCOMA_MANAGEMENT_PORT_OFFSET", "0"))
WORKER_PORTS = {
    "pocoma-command-worker": 18083,
    "pocoma-results-worker": 18084,
    "pocoma-business-read-worker": 18085,
}


COMMAND_CONSUMPTION_ID = "98f7268d-d340-581c-b83d-010926223e14"
IDENTITY_CONSUMPTION_ID = "2999e75c-272d-5afe-a966-c9364ef021fe"

PROFILES: Dict[str, Dict[str, int]] = {
    "H1": {
        "terminal_commands": 0,
        "quarantined_commands": 0,
        "terminal_identity": 0,
        "quarantined_identity": 0,
        "total_events": 0,
    },
    "H2": {
        "terminal_commands": 49_000,
        "quarantined_commands": 0,
        "terminal_identity": 2_006,
        "quarantined_identity": 0,
        "total_events": 120_000,
    },
    "H3": {
        "terminal_commands": 49_000,
        "quarantined_commands": 300,
        "terminal_identity": 2_006,
        "quarantined_identity": 50,
        "total_events": 120_000,
    },
    "H4": {
        "terminal_commands": 60_000,
        "quarantined_commands": 800,
        "terminal_identity": 2_006,
        "quarantined_identity": 200,
        "total_events": 500_000,
    },
    "H5": {
        "terminal_commands": 100_000,
        "quarantined_commands": 2_000,
        "terminal_identity": 5_006,
        "quarantined_identity": 500,
        "total_events": 1_000_000,
    },
}


def run_psql(sql: str, db: str = WRITE_DB, schema: str = WRITE_SCHEMA, timeout: int = 300) -> str:
    cmd = [
        "docker", "exec", "-i",
        "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        "-e", f"PGOPTIONS=-c search_path={schema},public",
        POSTGRES_CONTAINER,
        "psql", "-X", "-v", "ON_ERROR_STOP=1",
        "-U", POSTGRES_USER, "-d", db, "-tAc", sql,
    ]
    res = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if res.returncode != 0:
        raise RuntimeError(f"psql failed on {db}.{schema}:\n{res.stderr}\nSQL:\n{sql[:600]}")
    return res.stdout.strip()


def current_counts() -> Dict[str, Any]:
    sql = """
    SELECT json_build_object(
        'commands_total', (SELECT count(*) FROM v3_command),
        'commands_terminal', (SELECT count(*) FROM v3_command WHERE status='TERMINAL'),
        'commands_pending', (SELECT count(*) FROM v3_command WHERE status='PENDING'),
        'commands_quarantined_slots', (SELECT count(*) FROM consumption_slot WHERE consumption_id='98f7268d-d340-581c-b83d-010926223e14' AND state='QUARANTINED'),
        'commands_quarantined_partitions', (SELECT count(DISTINCT partition_id) FROM consumption_slot WHERE consumption_id='98f7268d-d340-581c-b83d-010926223e14' AND state='QUARANTINED'),
        'identity_total', (SELECT count(*) FROM v3_identity_request),
        'identity_terminal', (SELECT count(*) FROM v3_identity_request WHERE status='TERMINAL'),
        'identity_pending', (SELECT count(*) FROM v3_identity_request WHERE status='PENDING'),
        'identity_quarantined_slots', (SELECT count(*) FROM consumption_slot WHERE consumption_id='2999e75c-272d-5afe-a966-c9364ef021fe' AND state='QUARANTINED'),
        'identity_quarantined_partitions', (SELECT count(DISTINCT partition_id) FROM consumption_slot WHERE consumption_id='2999e75c-272d-5afe-a966-c9364ef021fe' AND state='QUARANTINED'),
        'command_counter', (SELECT position FROM v3_request_counter WHERE family='COMMAND'),
        'command_positions', (SELECT count(*) FROM v3_request_position WHERE family='COMMAND'),
        'identity_counter', (SELECT position FROM v3_request_counter WHERE family='IDENTITY_REQUEST'),
        'identity_positions', (SELECT count(*) FROM v3_request_position WHERE family='IDENTITY_REQUEST'),
        'event_positions_total', (SELECT count(*) FROM v3_event_position),
        'pot_event_counter', (SELECT position FROM v3_event_counter WHERE family='POT'),
        'cmd_completion_counter', (SELECT position FROM v3_event_counter WHERE family='COMMAND_COMPLETION'),
        'id_completion_counter', (SELECT position FROM v3_event_counter WHERE family='IDENTITY_COMPLETION')
    );
    """
    return json.loads(run_psql(sql))


def seed_terminal_commands(target_delta: int, batch_size: int = 2500) -> None:
    if target_delta <= 0:
        return
    remaining = target_delta
    batch_idx = 0
    while remaining > 0:
        n = min(batch_size, remaining)
        batch_idx += 1
        sql = f"""
        DO $$
        DECLARE
            i int;
            req_id uuid;
            ev_id uuid;
            pot_id uuid := '00000000-0000-4000-8000-000000000001'::uuid;
            r_pos bigint;
            e_pos bigint;
        BEGIN
            FOR i IN 1..{n} LOOP
                req_id := gen_random_uuid();
                ev_id := gen_random_uuid();

                UPDATE v3_request_counter
                   SET position = position + 1
                 WHERE family = 'COMMAND'
                 RETURNING position INTO r_pos;

                INSERT INTO v3_command(
                    request_id, requester_issuer, requester_subject,
                    binding_token, pot_id, observed_version, operation, payload, status
                ) VALUES (
                    req_id, 'hist-seed', 'hist-actor',
                    'hist-binding-token', pot_id, 0, 'CreatePot',
                    '{{"label":"hist-terminal"}}'::jsonb, 'PENDING'
                );

                INSERT INTO v3_request_position(family, position, request_id)
                VALUES ('COMMAND', r_pos, req_id);

                UPDATE v3_command SET status = 'TERMINAL' WHERE request_id = req_id;

                INSERT INTO v3_command_result(request_id, outcome, resulting_pot_version, rejection)
                VALUES (req_id, 'REJECTED', NULL, 'STALE_VERSION');

                INSERT INTO v3_command_completion_event(event_id, subject_type, subject_id, recorded_at)
                VALUES (ev_id, 'COMMAND', req_id, clock_timestamp());

                UPDATE v3_event_counter
                   SET position = position + 1
                 WHERE family = 'COMMAND_COMPLETION'
                 RETURNING position INTO e_pos;

                INSERT INTO v3_event_position(family, position, event_id)
                VALUES ('COMMAND_COMPLETION', e_pos, ev_id);
            END LOOP;
        END $$;
        """
        run_psql(sql)
        remaining -= n


def seed_terminal_identity_requests(target_delta: int, batch_size: int = 2000) -> None:
    if target_delta <= 0:
        return
    remaining = target_delta
    while remaining > 0:
        n = min(batch_size, remaining)
        sql = f"""
        DO $$
        DECLARE
            i int;
            req_id uuid;
            ev_id uuid;
            r_pos bigint;
            e_pos bigint;
        BEGIN
            FOR i IN 1..{n} LOOP
                req_id := gen_random_uuid();
                ev_id := gen_random_uuid();

                UPDATE v3_request_counter
                   SET position = position + 1
                 WHERE family = 'IDENTITY_REQUEST'
                 RETURNING position INTO r_pos;

                INSERT INTO v3_identity_request(
                    request_id, requester_issuer, requester_subject,
                    operation, binding_token, target_issuer, target_subject,
                    admission_context, created_at, status
                ) VALUES (
                    req_id, 'hist-seed', 'hist-actor-' || r_pos,
                    'UNLINK', 'hist-binding-token', 'hist-seed', 'hist-target-' || r_pos,
                    jsonb_build_object(
                        'validator', 'hist-seed-validator',
                        'contractVersion', 'v1',
                        'initiator', jsonb_build_object('issuer', 'hist-seed', 'subject', 'hist-actor-' || r_pos),
                        'target', jsonb_build_object('issuer', 'hist-seed', 'subject', 'hist-target-' || r_pos),
                        'intentionFingerprint', req_id::text,
                        'grantOrCorrelationReference', req_id::text,
                        'validatedAt', '2026-11-09T00:00:00Z',
                        'attestations', '{{}}'::jsonb,
                        'origin', 'CONTROLLED_INTERNAL'
                    ),
                    clock_timestamp(), 'PENDING'
                );

                INSERT INTO v3_request_position(family, position, request_id)
                VALUES ('IDENTITY_REQUEST', r_pos, req_id);

                UPDATE v3_identity_request SET status = 'TERMINAL' WHERE request_id = req_id;

                INSERT INTO v3_identity_request_result(
                    request_id, outcome, target_eui_id, target_version, rejection
                ) VALUES (
                    req_id, 'REJECTED', NULL, NULL, 'INVALID_BINDING'
                );

                INSERT INTO v3_identity_completion_event(
                    event_id, subject_type, subject_id, recorded_at
                ) VALUES (
                    ev_id, 'UNLINK', req_id, clock_timestamp()
                );

                UPDATE v3_event_counter
                   SET position = position + 1
                 WHERE family = 'IDENTITY_COMPLETION'
                 RETURNING position INTO e_pos;

                INSERT INTO v3_event_position(family, position, event_id)
                VALUES ('IDENTITY_COMPLETION', e_pos, ev_id);
            END LOOP;
        END $$;
        """
        run_psql(sql)
        remaining -= n


def seed_quarantined_commands(target_delta: int) -> None:
    if target_delta <= 0:
        return
    sql = f"""
    DO $$
    DECLARE
        i int;
        req_id uuid;
        r_pos bigint;
        h integer;
        p integer;
        pot_id uuid := '00000000-0000-4000-8000-000000000001'::uuid;
    BEGIN
        FOR i IN 1..{target_delta} LOOP
            req_id := gen_random_uuid();

            UPDATE v3_request_counter
               SET position = position + 1
             WHERE family = 'COMMAND'
             RETURNING position INTO r_pos;

            INSERT INTO v3_command(
                request_id, requester_issuer, requester_subject,
                binding_token, pot_id, observed_version, operation, payload, status
            ) VALUES (
                req_id, 'hist-quarantine', 'actor-' || i,
                'quarantine-token', pot_id, 0, 'CreatePot',
                '{{"label":"quarantined-cmd"}}'::jsonb, 'PENDING'
            );

            INSERT INTO v3_request_position(family, position, request_id)
            VALUES ('COMMAND', r_pos, req_id);

            h := consumption_hash_v1('COMMAND', req_id);
            p := ((h::bigint % 4 + 4) % 4)::int;

            INSERT INTO consumption_slot(
                consumption_id, consumable_id, partition_hash, partition_id, state, attempt_count
            ) VALUES (
                '{COMMAND_CONSUMPTION_ID}'::uuid, req_id, h, p, 'QUARANTINED', 3
            );
        END LOOP;
    END $$;
    """
    run_psql(sql)


def seed_quarantined_identity_requests(target_delta: int) -> None:
    if target_delta <= 0:
        return
    sql = f"""
    DO $$
    DECLARE
        i int;
        req_id uuid;
        r_pos bigint;
        h integer;
        p integer;
    BEGIN
        FOR i IN 1..{target_delta} LOOP
            req_id := gen_random_uuid();

            UPDATE v3_request_counter
               SET position = position + 1
             WHERE family = 'IDENTITY_REQUEST'
             RETURNING position INTO r_pos;

            INSERT INTO v3_identity_request(
                request_id, requester_issuer, requester_subject,
                operation, binding_token, target_issuer, target_subject,
                admission_context, created_at, status
            ) VALUES (
                req_id, 'hist-quarantine', 'id-actor-' || r_pos,
                'REGISTRATION', NULL, 'hist-quarantine', 'id-actor-' || r_pos,
                jsonb_build_object(
                    'validator', 'hist-quar-validator',
                    'contractVersion', 'v1',
                    'initiator', jsonb_build_object('issuer', 'hist-quarantine', 'subject', 'id-actor-' || r_pos),
                    'target', jsonb_build_object('issuer', 'hist-quarantine', 'subject', 'id-actor-' || r_pos),
                    'intentionFingerprint', req_id::text,
                    'grantOrCorrelationReference', req_id::text,
                    'validatedAt', '2026-11-09T00:00:00Z',
                    'attestations', '{{}}'::jsonb,
                    'origin', 'CONTROLLED_INTERNAL'
                ),
                clock_timestamp(), 'PENDING'
            );

            INSERT INTO v3_request_position(family, position, request_id)
            VALUES ('IDENTITY_REQUEST', r_pos, req_id);

            h := consumption_hash_v1('IDENTITY_REQUEST', req_id);
            p := ((h::bigint % 4 + 4) % 4)::int;

            INSERT INTO consumption_slot(
                consumption_id, consumable_id, partition_hash, partition_id, state, attempt_count
            ) VALUES (
                '{IDENTITY_CONSUMPTION_ID}'::uuid, req_id, h, p, 'QUARANTINED', 3
            );
        END LOOP;
    END $$;
    """
    run_psql(sql)


def seed_extra_published_events(target_delta: int, batch_size: int = 25000) -> None:
    """Seeds additional published event positions on POT family to reach target event volume."""
    if target_delta <= 0:
        return
    remaining = target_delta
    while remaining > 0:
        n = min(batch_size, remaining)
        sql = f"""
        WITH base AS (
            SELECT position AS start_pos FROM v3_event_counter WHERE family = 'POT' FOR UPDATE
        ),
        ins AS (
            INSERT INTO v3_event_position(family, position, event_id)
            SELECT 'POT', b.start_pos + g.i, gen_random_uuid()
            FROM base b, generate_series(1, {n}) AS g(i)
            RETURNING position
        )
        UPDATE v3_event_counter
           SET position = (SELECT max(position) FROM ins)
         WHERE family = 'POT';
        """
        run_psql(sql)
        remaining -= n


def synchronize_checkpoints_and_analyze() -> None:
    """Advances WRITE and READ discovery_checkpoint to current counters and runs ANALYZE."""
    sync_write_sql = f"""
    DELETE FROM consumption_slot WHERE state = 'AVAILABLE';

    INSERT INTO discovery_checkpoint(consumption_id, partition_id, position)
    SELECT '{COMMAND_CONSUMPTION_ID}'::uuid, p, (SELECT position FROM v3_request_counter WHERE family='COMMAND')
    FROM generate_series(0, 3) AS p
    ON CONFLICT (consumption_id, partition_id)
    DO UPDATE SET position = EXCLUDED.position;

    INSERT INTO discovery_checkpoint(consumption_id, partition_id, position)
    SELECT '{IDENTITY_CONSUMPTION_ID}'::uuid, p, (SELECT position FROM v3_request_counter WHERE family='IDENTITY_REQUEST')
    FROM generate_series(0, 3) AS p
    ON CONFLICT (consumption_id, partition_id)
    DO UPDATE SET position = EXCLUDED.position;

    UPDATE partition_ownership
       SET owner_token = NULL, owned_until = NULL;

    ANALYZE;
    """
    run_psql(sync_write_sql, db=WRITE_DB, schema=WRITE_SCHEMA)

    # Also advance READ checkpoints so background historical events are already marked discovered
    counters_raw = run_psql(
        "SELECT json_object_agg(family, position) FROM v3_event_counter;",
        db=WRITE_DB,
        schema=WRITE_SCHEMA,
    )
    counters = json.loads(counters_raw)
    pot_pos = counters.get("POT", 0)
    cmd_comp_pos = counters.get("COMMAND_COMPLETION", 0)
    id_pos = counters.get("IDENTITY", 0)
    id_comp_pos = counters.get("IDENTITY_COMPLETION", 0)
    inv_pos = counters.get("INVITATION", 0)

    sync_read_sql = f"""
    DELETE FROM consumption_slot WHERE state = 'AVAILABLE';
    DELETE FROM discovery_checkpoint;

    INSERT INTO discovery_checkpoint(consumption_id, partition_id, position)
    SELECT c.consumption_id, p.partition_id,
           CASE c.family
               WHEN 'POT_EVENT' THEN {pot_pos}
               WHEN 'COMMAND_COMPLETION_EVENT' THEN {cmd_comp_pos}
               WHEN 'IDENTITY_EVENT' THEN {id_pos}
               WHEN 'INVITATION_EVENT' THEN {inv_pos}
               WHEN 'IDENTITY_COMPLETION_EVENT' THEN {id_comp_pos}
               ELSE 0
           END
    FROM consumption_routing_contract c
    CROSS JOIN generate_series(0, c.partition_count - 1) AS p(partition_id);

    UPDATE partition_ownership
       SET owner_token = NULL, owned_until = NULL;

    ANALYZE;
    """
    run_psql(sync_read_sql, db=READ_DB, schema=READ_SCHEMA)


def verify_sql_integrity() -> Dict[str, Any]:
    audit_sql = """
    SELECT json_build_object(
        'command_counter_matches_positions', (
            (SELECT position FROM v3_request_counter WHERE family='COMMAND') =
            (SELECT count(*) FROM v3_request_position WHERE family='COMMAND')
        ),
        'command_dense_prefix', (
            (SELECT coalesce(max(position), 0) FROM v3_request_position WHERE family='COMMAND') =
            (SELECT count(*) FROM v3_request_position WHERE family='COMMAND')
        ),
        'unpositioned_commands', (
            SELECT count(*) FROM v3_command c
            WHERE NOT EXISTS (
                SELECT 1 FROM v3_request_position p WHERE p.family='COMMAND' AND p.request_id=c.request_id
            )
        ),
        'identity_counter_matches_positions', (
            (SELECT position FROM v3_request_counter WHERE family='IDENTITY_REQUEST') =
            (SELECT count(*) FROM v3_request_position WHERE family='IDENTITY_REQUEST')
        ),
        'identity_dense_prefix', (
            (SELECT coalesce(max(position), 0) FROM v3_request_position WHERE family='IDENTITY_REQUEST') =
            (SELECT count(*) FROM v3_request_position WHERE family='IDENTITY_REQUEST')
        ),
        'unpositioned_identity_requests', (
            SELECT count(*) FROM v3_identity_request r
            WHERE NOT EXISTS (
                SELECT 1 FROM v3_request_position p WHERE p.family='IDENTITY_REQUEST' AND p.request_id=r.request_id
            )
        ),
        'command_checkpoints_aligned', (
            SELECT count(*) = 4 FROM discovery_checkpoint
            WHERE consumption_id='98f7268d-d340-581c-b83d-010926223e14'
              AND position = (SELECT position FROM v3_request_counter WHERE family='COMMAND')
        ),
        'identity_checkpoints_aligned', (
            SELECT count(*) = 4 FROM discovery_checkpoint
            WHERE consumption_id='2999e75c-272d-5afe-a966-c9364ef021fe'
              AND position = (SELECT position FROM v3_request_counter WHERE family='IDENTITY_REQUEST')
        )
    );
    """
    audit = json.loads(run_psql(audit_sql))
    if not (
        audit["command_counter_matches_positions"]
        and audit["command_dense_prefix"]
        and audit["unpositioned_commands"] == 0
        and audit["identity_counter_matches_positions"]
        and audit["identity_dense_prefix"]
        and audit["unpositioned_identity_requests"] == 0
        and audit["command_checkpoints_aligned"]
        and audit["identity_checkpoints_aligned"]
    ):
        raise RuntimeError(f"SQL integrity audit failed: {json.dumps(audit, indent=2)}")
    return audit


def retire_all_quarantines() -> None:
    """Retires any existing PENDING commands/identity requests and deletes QUARANTINED slots."""
    sql = """
    DO $$
    DECLARE
        rec record;
        ev_id uuid;
        e_pos bigint;
    BEGIN
        DELETE FROM consumption_slot WHERE state = 'QUARANTINED';

        FOR rec IN SELECT request_id FROM v3_command WHERE status = 'PENDING' LOOP
            ev_id := gen_random_uuid();
            DELETE FROM consumption_slot
             WHERE consumption_id = '98f7268d-d340-581c-b83d-010926223e14'::uuid
               AND consumable_id = rec.request_id;
            UPDATE v3_command SET status = 'TERMINAL' WHERE request_id = rec.request_id;
            INSERT INTO v3_command_result(request_id, outcome, resulting_pot_version, rejection)
            VALUES (rec.request_id, 'REJECTED', NULL, 'STALE_VERSION');
            INSERT INTO v3_command_completion_event(event_id, subject_type, subject_id, recorded_at)
            VALUES (ev_id, 'COMMAND', rec.request_id, clock_timestamp());
            UPDATE v3_event_counter
               SET position = position + 1
             WHERE family = 'COMMAND_COMPLETION'
             RETURNING position INTO e_pos;
            INSERT INTO v3_event_position(family, position, event_id)
            VALUES ('COMMAND_COMPLETION', e_pos, ev_id);
        END LOOP;

        FOR rec IN SELECT request_id, operation FROM v3_identity_request WHERE status = 'PENDING' LOOP
            ev_id := gen_random_uuid();
            DELETE FROM consumption_slot
             WHERE consumption_id = '2999e75c-272d-5afe-a966-c9364ef021fe'::uuid
               AND consumable_id = rec.request_id;
            UPDATE v3_identity_request SET status = 'TERMINAL' WHERE request_id = rec.request_id;
            INSERT INTO v3_identity_request_result(
                request_id, outcome, target_eui_id, target_version, rejection
            ) VALUES (
                rec.request_id, 'REJECTED', NULL, NULL, 'INVALID_BINDING'
            );
            INSERT INTO v3_identity_completion_event(
                event_id, subject_type, subject_id, recorded_at
            ) VALUES (
                ev_id, rec.operation, rec.request_id, clock_timestamp()
            );
            UPDATE v3_event_counter
               SET position = position + 1
             WHERE family = 'IDENTITY_COMPLETION'
             RETURNING position INTO e_pos;
            INSERT INTO v3_event_position(family, position, event_id)
            VALUES ('IDENTITY_COMPLETION', e_pos, ev_id);
        END LOOP;
    END $$;
    """
    run_psql(sql)


def wait_workers_healthy(timeout_sec: float = 45.0) -> None:
    import urllib.request
    endpoints = {
        RUNTIME_PREFIX + name: f"http://127.0.0.1:{port + MANAGEMENT_PORT_OFFSET}/actuator/health"
        for name, port in WORKER_PORTS.items()
    }
    t0 = time.time()
    ready = set()
    while time.time() - t0 < timeout_sec and len(ready) < len(endpoints):
        for name, url in endpoints.items():
            if name in ready:
                continue
            try:
                with urllib.request.urlopen(url, timeout=2) as resp:
                    if resp.status == 200:
                        ready.add(name)
            except Exception:
                pass
        if len(ready) < len(endpoints):
            time.sleep(0.5)
    if len(ready) < len(endpoints):
        raise RuntimeError(f"Workers failed to become healthy: {set(endpoints.keys()) - ready}")


def apply_profile(profile_name: str) -> Dict[str, Any]:
    if profile_name not in PROFILES:
        raise ValueError(f"Unknown profile {profile_name}; valid: {list(PROFILES.keys())}")
    target = PROFILES[profile_name]
    t0 = time.time()
    workers = [RUNTIME_PREFIX + name for name in WORKER_PORTS]
    print(f"[{profile_name}] Stopping worker containers for clean atomic population...")
    subprocess.run(["docker", "stop", *workers], capture_output=True, text=True, check=True)
    try:
        initial = current_counts()
        if (
            (target["quarantined_commands"] == 0 and target["quarantined_identity"] == 0)
            or initial["commands_quarantined_slots"] > target["quarantined_commands"]
            or initial["identity_quarantined_slots"] > target["quarantined_identity"]
        ):
            retire_all_quarantines()
        before = current_counts()

        # Calculate deltas needed to reach at least target volumes monotonically
        delta_term_cmd = max(0, target["terminal_commands"] - before["commands_terminal"])
        delta_quar_cmd = max(0, target["quarantined_commands"] - before["commands_quarantined_slots"])
        delta_term_id = max(0, target["terminal_identity"] - before["identity_terminal"])
        delta_quar_id = max(0, target["quarantined_identity"] - before["identity_quarantined_slots"])

        print(
            f"[{profile_name}] Seeding deltas: "
            f"+{delta_term_cmd} terminal commands, "
            f"+{delta_quar_cmd} quarantined commands, "
            f"+{delta_term_id} terminal identity requests, "
            f"+{delta_quar_id} quarantined identity requests..."
        )

        seed_terminal_commands(delta_term_cmd)
        seed_quarantined_commands(delta_quar_cmd)
        seed_terminal_identity_requests(delta_term_id)
        seed_quarantined_identity_requests(delta_quar_id)

        mid = current_counts()
        delta_events = max(0, target["total_events"] - mid["event_positions_total"])
        if delta_events > 0:
            print(f"[{profile_name}] Seeding +{delta_events} published event positions...")
            seed_extra_published_events(delta_events)

        synchronize_checkpoints_and_analyze()
        after = current_counts()
        audit = verify_sql_integrity()
    finally:
        print(f"[{profile_name}] Starting worker containers and awaiting healthchecks...")
        subprocess.run(["docker", "start", *workers], capture_output=True, text=True, check=True)
        wait_workers_healthy()
    elapsed = round(time.time() - t0, 2)

    result = {
        "profile": profile_name,
        "elapsedSeconds": elapsed,
        "before": before,
        "after": after,
        "integrityAudit": audit,
    }
    print(f"[{profile_name}] Completed in {elapsed}s | Integrity audit PASSED.")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="Pocoma V3 Populated DB Generator (H1..H5)")
    parser.add_argument("--profile", required=True, choices=list(PROFILES.keys()), help="Target profile H1..H5")
    parser.add_argument("--output", help="Optional JSON output file path")
    args = parser.parse_args()

    res = apply_profile(args.profile)
    if args.output:
        out_path = Path(args.output)
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_text(json.dumps(res, indent=2), encoding="utf-8")
    else:
        print(json.dumps(res, indent=2))


if __name__ == "__main__":
    main()
