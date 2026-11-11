#!/usr/bin/env python3
"""Pocoma V3 — Idempotent Local PostgreSQL Initialization for V3 Observability Demonstration.

Initializes:
1. Database `pocoma_read` if absent, with pgcrypto extension.
2. Roles: `pocoma_write`, `pocoma_read`, `pocoma_admission`, `pocoma_react`, `pocoma_injector`.
3. Schemas: `v3_write` in `pocoma` and `v3_read` in `pocoma_read`.
4. Flyway migrations with required placeholders:
   - Write: V1..V10 on v3_write in pocoma
   - Read: V1..V7, V10 on v3_read in pocoma_read
5. Routing, lifecycle, discovery, identity logging, and admission SQL scripts.
6. Dedicated SELECT grants for WRITE source, HTTP READ and READ maintenance status.
"""

from pathlib import Path
import argparse
import os
import subprocess

REPO_ROOT = Path(__file__).resolve().parents[2]
POSTGRES_CONTAINER = "pocoma-v3-postgres"
POSTGRES_USER = "pocoma"
POSTGRES_PASSWORD = "pocoma-local-only"


def psql(db, sql_command, check=True):
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        POSTGRES_CONTAINER,
        "psql", "-X", "-U", POSTGRES_USER, "-d", db, "-v", "ON_ERROR_STOP=1", "-tAc", sql_command
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if check and res.returncode != 0:
        raise RuntimeError(f"psql failed on {db}: {res.stderr.strip()} (cmd: {sql_command})")
    return res.stdout.strip()


def psql_script(db, schema, script_content, variables=None):
    vars_args = []
    if variables:
        for k, v in variables.items():
            vars_args.extend(["-v", f"{k}={v}"])
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        f"-e", f"PGOPTIONS=-c search_path={schema},public",
        POSTGRES_CONTAINER,
        "psql", "-X", "-U", POSTGRES_USER, "-d", db, "-v", "ON_ERROR_STOP=1",
        *vars_args, "-f", "-"
    ]
    res = subprocess.run(cmd, input=script_content, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"psql script failed on {db}.{schema}: {res.stderr.strip()}")
    return res.stdout.strip()


def initialize_databases_and_roles():
    print("▶ Initializing PostgreSQL databases and roles for V3...")
    # Ensure server log_line_prefix matches V3 requirement
    psql("pocoma", "ALTER SYSTEM SET log_line_prefix = '%m [%p] xid=%x state=%e ';")
    psql("pocoma", "SELECT pg_reload_conf();")
    # 1. Databases pocoma_write and pocoma_read
    for db in ["pocoma_write", "pocoma_read"]:
        exists = psql("pocoma", f"SELECT 1 FROM pg_database WHERE datname='{db}'")
        if not exists:
            print(f"  • Creating database {db}...")
            psql("pocoma", f"CREATE DATABASE {db};")
        psql(db, "CREATE EXTENSION IF NOT EXISTS pgcrypto;")
        psql(db, f"REVOKE TEMPORARY ON DATABASE {db} FROM public;")

    # 2. Roles
    roles_sql = """
    DO $$
    BEGIN
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pocoma_write') THEN
        CREATE ROLE pocoma_write LOGIN PASSWORD 'pocoma-local-only';
      END IF;
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pocoma_read') THEN
        CREATE ROLE pocoma_read LOGIN PASSWORD 'pocoma-local-only';
      END IF;
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pocoma_admission') THEN
        CREATE ROLE pocoma_admission LOGIN PASSWORD 'pocoma-local-only';
      END IF;
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pocoma_write_source_read') THEN
        CREATE ROLE pocoma_write_source_read LOGIN PASSWORD 'pocoma-source-local-only';
      END IF;
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pocoma_read_http') THEN
        CREATE ROLE pocoma_read_http LOGIN PASSWORD 'pocoma-read-http-local-only';
      END IF;
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pocoma_read_status') THEN
        CREATE ROLE pocoma_read_status LOGIN PASSWORD 'pocoma-read-status-local-only';
      END IF;
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pocoma_react') THEN
        CREATE ROLE pocoma_react LOGIN PASSWORD 'pocoma-local-only';
      END IF;
      IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'pocoma_injector') THEN
        CREATE ROLE pocoma_injector LOGIN PASSWORD 'pocoma-local-only';
      END IF;
    END $$;
    """
    psql("pocoma", roles_sql)
    psql("pocoma_write", "ALTER ROLE pocoma_write IN DATABASE pocoma_write SET search_path = v3_write, public;")
    psql("pocoma_write", "ALTER ROLE pocoma_admission IN DATABASE pocoma_write SET search_path = v3_write, public;")
    psql("pocoma_write", "ALTER ROLE pocoma_react IN DATABASE pocoma_write SET search_path = v3_write, public;")
    psql("pocoma_read", "ALTER ROLE pocoma_read IN DATABASE pocoma_read SET search_path = v3_read, public;")
    psql("pocoma_read", "ALTER ROLE pocoma_react IN DATABASE pocoma_read SET search_path = v3_read, public;")
    print("  ✓ Databases, roles, and search paths verified.")


def apply_migrations(db, schema, admin, runtime, migration_files):
    for f in migration_files:
        path = REPO_ROOT / f
        content = path.read_text(encoding="utf-8")
        # Replace Flyway placeholders
        content = content.replace("${expectedDatabase}", db)
        content = content.replace("${expectedSchema}", schema)
        content = content.replace("${expectedAdmin}", admin)
        content = content.replace("${expectedRuntime}", runtime)
        psql_script(db, schema, content)


def initialize_v3_write():
    reg = psql("pocoma_write", "SELECT to_regclass('v3_write.v3_command')::text;")
    if reg and reg != "":
        print("  ✓ Schema v3_write already initialized.")
        return

    print("▶ Initializing schema v3_write in database pocoma_write...")
    psql("pocoma_write", "CREATE SCHEMA IF NOT EXISTS v3_write AUTHORIZATION pocoma;")

    write_migrations = [
        "app/infra-postgres-write/src/main/resources/db/v3-pot/migration/V1__pot_components.sql",
        "app/infra-postgres-write/src/main/resources/db/v3-pot-execution/migration/V2__command_execution.sql",
        "app/infra-postgres-write/src/main/resources/db/v3-pot/migration/V3__point_history_indexes.sql",
        "app/infra-postgres-write/src/main/resources/db/v3-pot/migration/V4__write_effect_indexes.sql",
        "app/infra-postgres-write/src/main/resources/db/v3-consumption-write/migration/V5__consumption_foundation.sql",
        "app/infra-postgres-write/src/main/resources/db/v3-identity/migration/V6__identity_authority_and_requests.sql",
        "app/infra-postgres-write/src/main/resources/db/v3-admission/migration/V7__public_admission.sql",
        "app/infra-postgres-write/src/main/resources/db/v3-admission/migration/V8__admission_column_reads.sql",
        "app/infra-postgres-write/src/main/resources/db/v3-admission/migration/V9__link_proposal.sql",
        "app/infra-postgres-write/src/main/resources/db/v3-invitation/migration/V10__invitation_history.sql",
        "app/infra-postgres-write/src/main/resources/db/v3-identity/migration/V11__sequential_request_publication.sql",
    ]
    apply_migrations("pocoma_write", "v3_write", "pocoma", "pocoma_write", write_migrations)

    # Apply SQL scripts
    scripts = [
        ("scripts/v3/configure-l3-identity-logging.sql", {
            "expected_database": "pocoma_write",
            "expected_schema": "v3_write",
            "expected_admin": "pocoma",
            "runtime_role": "pocoma_write",
        }),
        ("scripts/v3/bootstrap-l2-routing.sql", {
            "expected_database": "pocoma_write",
            "expected_schema": "v3_write",
            "expected_admin": "pocoma",
            "authority": "WRITE",
        }),
        ("scripts/v3/grant-l2-lifecycle.sql", {
            "expected_database": "pocoma_write",
            "expected_schema": "v3_write",
            "expected_admin": "pocoma",
            "runtime_role": "pocoma_write",
            "reactivation_role": "pocoma_react",
            "identity_authority": "real",
            "identity_read_role": "pocoma_read",
        }),
        ("scripts/v3/grant-l2-discovery.sql", {
            "expected_database": "pocoma_write",
            "expected_schema": "v3_write",
            "expected_admin": "pocoma",
            "runtime_role": "pocoma_write",
            "reactivation_role": "pocoma_react",
            "identity_authority": "real",
            "identity_read_role": "pocoma_read",
        }),
        ("scripts/v3/grant-l5-admission.sql", {
            "expected_database": "pocoma_write",
            "expected_schema": "v3_write",
            "expected_admin": "pocoma",
            "admission_role": "pocoma_admission",
        }),
    ]

    for script_file, variables in scripts:
        content = (REPO_ROOT / script_file).read_text(encoding="utf-8")
        psql_script("pocoma_write", "v3_write", content, variables=variables)

    print("  ✓ Schema v3_write successfully provisioned and secured.")


def initialize_v3_read():
    # Bootstrap persistent maintenance authority in public schema (idempotent)
    maint_content = (REPO_ROOT / "scripts/v3/bootstrap-read-maintenance.sql").read_text(encoding="utf-8")
    psql_script("pocoma_read", "public", maint_content)

    reg = psql("pocoma_read", "SELECT to_regclass('v3_read.read_current_pot')::text;")
    if reg and reg != "":
        print("  ✓ Schema v3_read and public maintenance authority already initialized.")
        return

    print("▶ Initializing schema v3_read in database pocoma_read...")
    psql("pocoma_read", "CREATE SCHEMA IF NOT EXISTS v3_read AUTHORIZATION pocoma;")

    read_migrations = [
        "app/infra-postgres-read/src/main/resources/db/v3-consumption-read/migration/V1__consumption_foundation.sql",
        "app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V2__subobject_version.sql",
        "app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V3__pot_version.sql",
        "app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V4__pot_balance.sql",
        "app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V5__pot_visibility.sql",
        "app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V6__current_binding.sql",
        "app/infra-postgres-read/src/main/resources/db/v3-read-business/migration/V7__terminal_results.sql",
        "app/infra-postgres-read/src/main/resources/db/v3-read-invitation/migration/V10__current_invitation.sql",
    ]
    apply_migrations("pocoma_read", "v3_read", "pocoma", "pocoma_read", read_migrations)

    scripts = [
        ("scripts/v3/bootstrap-l2-routing.sql", {
            "expected_database": "pocoma_read",
            "expected_schema": "v3_read",
            "expected_admin": "pocoma",
            "authority": "READ",
        }),
        ("scripts/v3/grant-l2-lifecycle.sql", {
            "expected_database": "pocoma_read",
            "expected_schema": "v3_read",
            "expected_admin": "pocoma",
            "runtime_role": "pocoma_read",
            "reactivation_role": "pocoma_react",
            "identity_authority": "real",
            "identity_read_role": "pocoma_read",
        }),
        ("scripts/v3/grant-l2-discovery.sql", {
            "expected_database": "pocoma_read",
            "expected_schema": "v3_read",
            "expected_admin": "pocoma",
            "runtime_role": "pocoma_read",
            "reactivation_role": "pocoma_react",
            "identity_authority": "real",
            "identity_read_role": "pocoma_read",
        }),
    ]

    for script_file, variables in scripts:
        content = (REPO_ROOT / script_file).read_text(encoding="utf-8")
        psql_script("pocoma_read", "v3_read", content, variables=variables)

    print("  ✓ Schema v3_read and public maintenance authority successfully provisioned and secured.")


def grant_runtime_readers():
    parameters = {
        "grant-l7-write-source-read.sql": ("pocoma_write", "v3_write", {
            "write_database": "pocoma_write", "write_schema": "v3_write",
            "source_role": "pocoma_write_source_read",
        }),
        "grant-l7-http-read.sql": ("pocoma_read", "v3_read", {
            "read_database": "pocoma_read", "read_schema": "v3_read",
            "query_role": "pocoma_read_http", "status_role": "pocoma_read_status",
        }),
    }
    for script, (database, schema, variables) in parameters.items():
        content = (REPO_ROOT / "scripts/v3" / script).read_text(encoding="utf-8")
        psql_script(database, schema, content, variables)


def main():
    global POSTGRES_CONTAINER
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-container", default=os.environ.get("POCOMA_POSTGRES_CONTAINER", POSTGRES_CONTAINER))
    args = parser.parse_args()
    POSTGRES_CONTAINER = args.postgres_container
    initialize_databases_and_roles()
    initialize_v3_write()
    initialize_v3_read()
    grant_runtime_readers()
    print("✓ V3 PostgreSQL initialization complete.")


if __name__ == "__main__":
    main()
