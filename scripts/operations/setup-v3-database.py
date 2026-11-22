#!/usr/bin/env python3
"""Pocoma — Idempotent Local PostgreSQL Initialization for V3 Distribution.

Initializes:
1. Databases `pocoma_write` and `pocoma_read` if absent, with pgcrypto extension.
2. Roles: `pocoma_write`, `pocoma_read`, `pocoma_admission`, `pocoma_write_source_read`,
   `pocoma_read_http`, `pocoma_read_status`, `pocoma_react`, `pocoma_injector`.
3. Schemas: `v3_write` in `pocoma_write` and `v3_read` in `pocoma_read`.
4. Consolidated initial baselines:
   - Write: `db/write/migration/V1__initial_schema.sql` on `v3_write` in `pocoma_write`
   - Read: canonical READ V1 to V5 on `v3_read` in `pocoma_read`
5. Routing, lifecycle, discovery, identity logging, and admission SQL scripts.
6. Dedicated SELECT grants for WRITE source, HTTP READ and READ maintenance status.
"""

from pathlib import Path
import argparse
import os
import re
import subprocess

from read_availability_schema import (
    READ_AVAILABILITY_MIGRATION, availability_catalog_complete, availability_catalog_present,
)

REPO_ROOT = Path(__file__).resolve().parents[2]
READ_MAINTENANCE = "scripts/v3/bootstrap-read-maintenance.sql"
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


def psql_script(db, schema, script_content, variables=None, single_transaction=False):
    vars_args = []
    if variables:
        for k, v in variables.items():
            vars_args.extend(["-v", f"{k}={v}"])
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        f"-e", f"PGOPTIONS=-c search_path={schema},public",
        POSTGRES_CONTAINER,
        "psql", "-X", "-U", POSTGRES_USER, "-d", db, "-v", "ON_ERROR_STOP=1",
        *vars_args, *(["--single-transaction"] if single_transaction else []), "-f", "-"
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


def migration_sql(path, db, schema, admin, runtime):
    return ((REPO_ROOT / path).read_text(encoding="utf-8")
            .replace("${expectedDatabase}", db)
            .replace("${expectedSchema}", schema)
            .replace("${expectedAdmin}", admin)
            .replace("${expectedRuntime}", runtime))


def apply_migrations(db, schema, admin, runtime, migration_files):
    for path in migration_files:
        psql_script(db, schema, migration_sql(path, db, schema, admin, runtime),
                    single_transaction=path == READ_AVAILABILITY_MIGRATION)


def install_fresh_schema(db, schema, migrations, scripts):
    statements = [f"CREATE SCHEMA IF NOT EXISTS {schema} AUTHORIZATION pocoma;"]
    statements.extend(migration_sql(path, db, schema, "pocoma", db) for path in migrations)
    for path, variables in scripts:
        statements.extend(f"\\set {key} {value}" for key, value in variables.items())
        content = (REPO_ROOT / path).read_text(encoding="utf-8")
        # Standalone scripts have delimiters. This caller owns the transaction.
        statements.append(re.sub(r"(?im)^[ \t]*(?:BEGIN|COMMIT);[ \t]*$", "", content))
    psql_script(db, schema, "\n".join(statements), single_transaction=True)


def require_routing_bootstrap(db, schema, contracts):
    ready = psql(db, f"""
        SELECT (SELECT count(*) FROM {schema}.consumption_routing_contract) = {contracts}
           AND (SELECT count(*) FROM {schema}.partition_ownership) = {4 * contracts}
           AND has_table_privilege('{db}', '{schema}.consumption_routing_contract', 'SELECT')
           AND has_column_privilege('{db}', '{schema}.consumption_slot', 'consumable_id', 'INSERT');
    """)
    if ready != "t":
        raise RuntimeError(f"Incomplete {schema} routing bootstrap; inspect the installation before restarting.")


def initialize_v3_write():
    reg = psql("pocoma_write", "SELECT to_regclass('v3_write.v3_command')::text;")
    if reg and reg != "":
        require_routing_bootstrap("pocoma_write", "v3_write", 2)
        created_res_col = psql(
            "pocoma_write",
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema = 'v3_write' AND table_name = 'v3_command_result' "
            "AND column_name = 'created_resources';",
        )
        if not created_res_col:
            print("▶ Applying additive WRITE migration V2__server_assigned_pot_identities.sql...")
            apply_migrations(
                "pocoma_write",
                "v3_write",
                "pocoma",
                "pocoma_write",
                ["app/infra-postgres-write/src/main/resources/db/write/migration/V2__server_assigned_pot_identities.sql"],
            )
            print("  ✓ Additive WRITE migration V2 applied.")
        else:
            print("  ✓ Schema v3_write (V2 server-assigned identities) already initialized.")
        return

    print("▶ Initializing schema v3_write in database pocoma_write...")

    write_migrations = [
        "app/infra-postgres-write/src/main/resources/db/write/migration/V1__initial_schema.sql",
        "app/infra-postgres-write/src/main/resources/db/write/migration/V2__server_assigned_pot_identities.sql",
    ]

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

    install_fresh_schema("pocoma_write", "v3_write", write_migrations, scripts)

    print("  ✓ Schema v3_write successfully provisioned and secured.")


def initialize_read_availability():
    schema = "v3_read"
    database = "pocoma_read"
    if psql(database, availability_catalog_present(schema)) == "t":
        if (psql(database, availability_catalog_complete(schema)) != "t"
                or psql(database, f"SELECT {schema}.read_availability_schema_valid();") != "t"):
            raise RuntimeError("Incomplete READ V5 schema; repair by exclusive READ reconstruction.")
        print("  ✓ READ V5 availability schema already installed.")
        return
    # The migration locks the affected tables, verifies the authority is empty and rolls
    # back its DDL and grants together on any error. A populated READ must be rebuilt.
    apply_migrations(database, schema, "pocoma", "pocoma_read", [READ_AVAILABILITY_MIGRATION])


def initialize_v3_read():
    reg = psql("pocoma_read", "SELECT to_regclass('v3_read.read_current_pot')::text;")
    if reg and reg != "":
        require_routing_bootstrap("pocoma_read", "v3_read", 7)
        agg_reg = psql("pocoma_read", "SELECT to_regclass('v3_read.read_pot_balance_aggregate')::text;")
        if not agg_reg:
            print("▶ Applying additive READ migration V2__pot_balance_aggregate.sql...")
            apply_migrations(
                "pocoma_read",
                "v3_read",
                "pocoma",
                "pocoma_read",
                ["app/infra-postgres-read/src/main/resources/db/read/migration/V2__pot_balance_aggregate.sql"],
            )
            print("  ✓ Additive READ migration V2 applied.")
        radix_reg = psql("pocoma_read", "SELECT to_regclass('v3_read.read_pot_version_node')::text;")
        if not radix_reg:
            existing_versions = psql("pocoma_read", "SELECT count(*) FROM v3_read.read_pot_version;")
            if existing_versions and int(existing_versions) > 0:
                raise RuntimeError(
                    "INCOMPATIBLE-V2-READ-SCHEMA: v3_read.read_pot_version is still on V1/V2 JSONB format "
                    f"with {existing_versions} row(s). Run 'python3 scripts/operations/v3-rebuild-read.py "
                    "--confirm-destructive=RESET_READ_DATA' to rebuild READ from authoritative WRITE events."
                )
            print("▶ Applying READ migration V3__pot_version_radix16.sql on empty READ tables...")
            apply_migrations(
                "pocoma_read",
                "v3_read",
                "pocoma",
                "pocoma_read",
                ["app/infra-postgres-read/src/main/resources/db/read/migration/V3__pot_version_radix16.sql"],
            )
            print("  ✓ READ migration V3 (Radix-16) applied.")
        else:
            print("  ✓ Schema v3_read (V3 Radix-16) already initialized.")
        sh_set_reg = psql("pocoma_read", "SELECT to_regclass('v3_read.read_pot_shareholder_set')::text;")
        bal_content_reg = psql("pocoma_read", "SELECT to_regclass('v3_read.read_pot_balance_content')::text;")
        if not bal_content_reg or not sh_set_reg:
            existing_balances = psql("pocoma_read", "SELECT count(*) FROM v3_read.read_pot_balance;")
            existing_aggs = psql("pocoma_read", "SELECT count(*) FROM v3_read.read_pot_balance_aggregate;")
            if (existing_balances and int(existing_balances) > 0) or (existing_aggs and int(existing_aggs) > 0):
                raise RuntimeError(
                    "INCOMPATIBLE-V3-READ-SCHEMA: v3_read.read_pot_balance / read_pot_balance_aggregate "
                    f"is still on V1/V2/V3 JSONB format with {existing_balances} balance row(s) and "
                    f"{existing_aggs} aggregate row(s). Run 'python3 scripts/operations/v3-rebuild-read.py "
                    "--confirm-destructive=RESET_READ_DATA' to rebuild READ from authoritative WRITE events."
                )
            print("▶ Applying READ migration V4__pot_balance_shared_storage.sql on empty READ tables...")
            apply_migrations(
                "pocoma_read",
                "v3_read",
                "pocoma",
                "pocoma_read",
                ["app/infra-postgres-read/src/main/resources/db/read/migration/V4__pot_balance_shared_storage.sql"],
            )
            print("  ✓ READ migration V4 (PotBalance shared storage) applied.")
        else:
            print("  ✓ Schema v3_read (V4 PotBalance shared storage) already initialized.")
        initialize_read_availability()
        created_res_col = psql(
            "pocoma_read",
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema = 'v3_read' AND table_name = 'read_command_result' "
            "AND column_name = 'created_resources';",
        )
        if not created_res_col:
            print("▶ Applying additive READ migration V6__server_assigned_pot_identities.sql...")
            apply_migrations(
                "pocoma_read",
                "v3_read",
                "pocoma",
                "pocoma_read",
                ["app/infra-postgres-read/src/main/resources/db/read/migration/V6__server_assigned_pot_identities.sql"],
            )
            print("  ✓ Additive READ migration V6 applied.")
        else:
            print("  ✓ Schema v3_read (V6 server-assigned identities) already initialized.")
        maint_content = (REPO_ROOT / READ_MAINTENANCE).read_text(encoding="utf-8")
        psql_script("pocoma_read", "public", maint_content, single_transaction=True)
        return

    print("▶ Initializing schema v3_read in database pocoma_read...")

    read_migrations = [
        "app/infra-postgres-read/src/main/resources/db/read/migration/V1__initial_schema.sql",
        "app/infra-postgres-read/src/main/resources/db/read/migration/V2__pot_balance_aggregate.sql",
        "app/infra-postgres-read/src/main/resources/db/read/migration/V3__pot_version_radix16.sql",
        "app/infra-postgres-read/src/main/resources/db/read/migration/V4__pot_balance_shared_storage.sql",
        READ_AVAILABILITY_MIGRATION,
        "app/infra-postgres-read/src/main/resources/db/read/migration/V6__server_assigned_pot_identities.sql",
    ]

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
        (READ_MAINTENANCE, {}),
    ]

    install_fresh_schema("pocoma_read", "v3_read", read_migrations, scripts)

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


def verify_compatible_target():
    for db, schema in (("pocoma_write", "v3_write"), ("pocoma_read", "v3_read")):
        exists = psql("pocoma", f"SELECT 1 FROM pg_database WHERE datname='{db}'")
        if not exists:
            continue
        flyway_schemas = psql(
            db,
            "SELECT string_agg(schemaname, ',') FROM pg_tables "
            "WHERE tablename = 'flyway_schema_history' "
            "AND schemaname NOT IN ('pg_catalog', 'information_schema');"
        )
        if flyway_schemas:
            raise RuntimeError(
                f"INCOMPATIBLE-LEGACY-FLYWAY-HISTORY: database '{db}' contains flyway_schema_history "
                f"in schema(s) [{flyway_schemas}]; refusing implicit baseline migration without modifying data."
            )


def main():
    global POSTGRES_CONTAINER
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--postgres-container", default=os.environ.get("POCOMA_POSTGRES_CONTAINER", POSTGRES_CONTAINER))
    args = parser.parse_args()
    POSTGRES_CONTAINER = args.postgres_container
    verify_compatible_target()
    initialize_databases_and_roles()
    initialize_v3_write()
    initialize_v3_read()
    grant_runtime_readers()
    print("✓ V3 PostgreSQL initialization complete.")


if __name__ == "__main__":
    main()
