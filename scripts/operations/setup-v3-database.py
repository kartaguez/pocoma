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
6. Generates application-v3.properties with resolved PostgreSQL inet_server_addr.
"""

from pathlib import Path
import re
import subprocess
import sys

REPO_ROOT = Path(__file__).resolve().parents[2]
POSTGRES_CONTAINER = "pocoma-functional-postgres-1"
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
        "app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot/migration/V1__pot_components.sql",
        "app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot-execution/migration/V2__command_execution.sql",
        "app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot/migration/V3__point_history_indexes.sql",
        "app/infra-persistence-primary-jpa/src/main/resources/db/v3-pot/migration/V4__write_effect_indexes.sql",
        "app/infra-persistence-primary-jpa/src/main/resources/db/v3-consumption-write/migration/V5__consumption_foundation.sql",
        "app/infra-persistence-primary-jpa/src/main/resources/db/v3-identity/migration/V6__identity_authority_and_requests.sql",
        "app/infra-persistence-primary-jpa/src/main/resources/db/v3-admission/migration/V7__public_admission.sql",
        "app/infra-persistence-primary-jpa/src/main/resources/db/v3-admission/migration/V8__admission_column_reads.sql",
        "app/infra-persistence-primary-jpa/src/main/resources/db/v3-admission/migration/V9__link_proposal.sql",
        "app/infra-persistence-primary-jpa/src/main/resources/db/v3-invitation/migration/V10__invitation_history.sql",
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
    reg = psql("pocoma_read", "SELECT to_regclass('v3_read.read_current_pot')::text;")
    if reg and reg != "":
        print("  ✓ Schema v3_read already initialized.")
        return

    print("▶ Initializing schema v3_read in database pocoma_read...")
    psql("pocoma_read", "CREATE SCHEMA IF NOT EXISTS v3_read AUTHORIZATION pocoma;")

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

    print("  ✓ Schema v3_read successfully provisioned and secured.")


def generate_properties_file():
    print("▶ Generating docker/monolith/application-v3.properties...")
    cmd = [
        "docker", "exec", "-i", "-e", f"PGPASSWORD={POSTGRES_PASSWORD}",
        POSTGRES_CONTAINER,
        "psql", "-X", "-h", "postgres", "-U", POSTGRES_USER, "-d", "pocoma_write", "-v", "ON_ERROR_STOP=1", "-tAc",
        "SELECT inet_server_addr()::text, inet_server_port();"
    ]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode == 0 and res.stdout.strip():
        parts = res.stdout.strip().split("|")
        server_addr, server_port = parts[0], parts[1]
    else:
        server_addr = "127.0.0.1/32"
        server_port = "5432"

    config_dir = REPO_ROOT / "docker/monolith"
    config_dir.mkdir(parents=True, exist_ok=True)
    prop_file = config_dir / "application-v3.properties"

    dev_targets = (
        f"postgres,5432,pocoma_write,v3_write,pocoma_write,{server_addr},{server_port};"
        f"postgres,5432,pocoma_read,v3_read,pocoma_read,{server_addr},{server_port}"
    )

    properties_content = f"""# Auto-generated Pocoma V3 Monolith Runtime Properties
spring.flyway.enabled=false
spring.jpa.open-in-view=false
management.health.db.enabled=false
management.endpoints.web.exposure.include=health,prometheus
management.endpoint.health.probes.enabled=true
management.prometheus.metrics.export.enabled=true
server.port=8080
server.address=0.0.0.0
management.server.port=18081
management.server.address=0.0.0.0

# HTTP Security / Keycloak OIDC
pocoma.v3.http.issuer=http://localhost:8081/realms/pocoma
pocoma.v3.http.audience=pocoma-api
pocoma.v3.http.jwk-set-uri=http://keycloak:8080/realms/pocoma/protocol/openid-connect/certs

# HTTP Read Facade Pool
pocoma.v3.http.read.url=jdbc:postgresql://postgres:5432/pocoma_read?currentSchema=v3_read
pocoma.v3.http.read.username=pocoma_read
pocoma.v3.http.read.password=pocoma-local-only

# HTTP Admission
pocoma.v3.http.admission.url=jdbc:postgresql://postgres:5432/pocoma_write?currentSchema=v3_write
pocoma.v3.http.admission.username=pocoma_admission
pocoma.v3.http.admission.password=pocoma-local-only
pocoma.v3.http.admission.database=pocoma_write
pocoma.v3.http.admission.schema=v3_write
pocoma.v3.http.admission.lookup-key-id=stable
pocoma.v3.http.admission.lookup-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
pocoma.v3.http.admission.comparison-current=k1
pocoma.v3.http.admission.comparison-key-ids=k1
pocoma.v3.http.admission.comparison-keys.k1=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=

# Read Limits
pocoma.v3.worker.read-limits.expense-batch=50
pocoma.v3.worker.read-limits.navigation-page=100
pocoma.v3.worker.read-limits.response-budget=16000000

# Worker Settings
pocoma.v3.worker.enabled=true
pocoma.v3.worker.environment=DEV
pocoma.v3.worker.fixture-identity=false
pocoma.v3.worker.identity-authority=real
pocoma.v3.worker.identity-request=true
pocoma.v3.worker.command-enabled=true
pocoma.v3.worker.technical-probes=false
pocoma.v3.worker.worker-name=monolith-worker
pocoma.v3.worker.worker-limit=4
pocoma.v3.worker.read-participants=MaterializePotVersion,MaterializePotBalance,MaterializePotVisibility,MaterializeCurrentBinding,MaterializeCommandResult,MaterializeIdentityRequestResult
pocoma.v3.worker.consumption-limits=MaterializePotVersion=1,MaterializePotBalance=1,MaterializePotVisibility=1,MaterializeCurrentBinding=1,MaterializeCommandResult=1,MaterializeIdentityRequestResult=1,Command=1,IdentityRequest=1
pocoma.v3.worker.partitions=MaterializePotVersion=0|1|2|3;MaterializePotBalance=0|1|2|3;MaterializePotVisibility=0|1|2|3;MaterializeCurrentBinding=0|1|2|3;MaterializeCommandResult=0|1|2|3;MaterializeIdentityRequestResult=0|1|2|3;Command=0|1|2|3;IdentityRequest=0|1|2|3
pocoma.v3.worker.lifecycle.lease-duration-ms=240000

# Targets & Pilot
pocoma.v3.worker.dev-targets={dev_targets}
pocoma.v3.pilot.write.database=pocoma_write
pocoma.v3.pilot.write.schema=v3_write
pocoma.v3.pilot.write.username=pocoma_write
pocoma.v3.pilot.write.password=pocoma-local-only
pocoma.v3.pilot.write.url=jdbc:postgresql://postgres:5432/pocoma_write?currentSchema=v3_write
pocoma.v3.pilot.read.database=pocoma_read
pocoma.v3.pilot.read.schema=v3_read
pocoma.v3.pilot.read.username=pocoma_read
pocoma.v3.pilot.read.password=pocoma-local-only
pocoma.v3.pilot.read.url=jdbc:postgresql://postgres:5432/pocoma_read?currentSchema=v3_read

# Worker Pools
pocoma.v3.worker.write-business.url=jdbc:postgresql://postgres:5432/pocoma_write?currentSchema=v3_write
pocoma.v3.worker.write-business.username=pocoma_write
pocoma.v3.worker.write-business.password=pocoma-local-only
pocoma.v3.worker.write-control.url=jdbc:postgresql://postgres:5432/pocoma_write?currentSchema=v3_write
pocoma.v3.worker.write-control.username=pocoma_write
pocoma.v3.worker.write-control.password=pocoma-local-only
pocoma.v3.worker.read-business.url=jdbc:postgresql://postgres:5432/pocoma_read?currentSchema=v3_read
pocoma.v3.worker.read-business.username=pocoma_read
pocoma.v3.worker.read-business.password=pocoma-local-only
pocoma.v3.worker.read-control.url=jdbc:postgresql://postgres:5432/pocoma_read?currentSchema=v3_read
pocoma.v3.worker.read-control.username=pocoma_read
pocoma.v3.worker.read-control.password=pocoma-local-only
"""
    prop_file.write_text(properties_content, encoding="utf-8")
    print(f"  ✓ {prop_file} written.")


def main():
    initialize_databases_and_roles()
    initialize_v3_write()
    initialize_v3_read()
    generate_properties_file()
    print("✓ V3 PostgreSQL initialization complete.")


if __name__ == "__main__":
    main()
