"""CURRENT V3 bootstrap using the existing SQL catalogue, atomic per database.

No source SQL is modified. The historical helper's transport is replaced so no
credential enters argv or diagnostics. A committed input marker closes each DB.
"""
import hashlib
import importlib.util
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ROLES = ('pocoma_write', 'pocoma_read', 'pocoma_admission', 'pocoma_write_source_read',
         'pocoma_read_http', 'pocoma_read_status', 'pocoma_react', 'pocoma_injector')


def fingerprint():
    paths = sorted({*ROOT.glob('app/infra-postgres-write/src/main/resources/db/write/migration/*.sql'),
                    *ROOT.glob('app/infra-postgres-read/src/main/resources/db/read/migration/*.sql'),
                    *ROOT.glob('scripts/v3/*.sql'),
                    ROOT / 'scripts/operations/setup-v3-database.py', Path(__file__)})
    return hashlib.sha256(''.join(str(p.relative_to(ROOT)) + ':' +
                                 hashlib.sha256(p.read_bytes()).hexdigest() + '\n'
                                 for p in paths).encode()).hexdigest()


def verify_compatible_state(sql, expected):
    """Refuse legacy Flyway or incompatible pre-existing schemas before any mutation."""
    for db, schema in (('pocoma_write', 'v3_write'), ('pocoma_read', 'v3_read')):
        exists = sql('pocoma', f"SELECT 1 FROM pg_database WHERE datname='{db}';")
        if exists.strip() != '1':
            continue
        flyway = sql(db, "SELECT string_agg(schemaname, ',') FROM pg_tables "
                         "WHERE tablename = 'flyway_schema_history' "
                         "AND schemaname NOT IN ('pg_catalog', 'information_schema');")
        if flyway.strip():
            raise RuntimeError(
                f"INCOMPATIBLE-LEGACY-FLYWAY-HISTORY: database '{db}' contains flyway_schema_history "
                f"in schema(s) [{flyway.strip()}]; refusing implicit baseline migration without modifying data."
            )
        marker = sql(db, "SELECT to_regclass('public.pocoma_local_bootstrap') IS NOT NULL;")
        if marker.strip() == 't':
            if sql(db, 'SELECT fingerprint FROM public.pocoma_local_bootstrap;').strip() != expected:
                raise RuntimeError('DB-INPUT-DRIFT')
        else:
            existing = sql(db, f"SELECT string_agg(tablename, ',') FROM pg_tables WHERE schemaname = '{schema}';")
            if existing.strip():
                raise RuntimeError(
                    f"INCOMPATIBLE-EXISTING-SCHEMA: database '{db}' schema '{schema}' already contains "
                    f"tables without matching baseline marker; refusing implicit migration without modifying data."
                )


def initialize(sql, passwords):
    expected = fingerprint()
    verify_compatible_state(sql, expected)
    spec = importlib.util.spec_from_file_location('existing_bootstrap', ROOT / 'scripts/operations/setup-v3-database.py')
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    pending = {'pocoma_write': [], 'pocoma_read': []}
    collecting = False

    def command(db, text, check=True):
        if collecting:
            if text.startswith('SELECT to_regclass'):
                return ''  # Only collected when the committed marker is absent.
            pending[db].append(text + '\n;\n')
            return ''
        return sql(db, text)

    def script(db, schema, text, variables=None):
        # Existing scripts own transaction delimiters. Compose them into a single
        # transaction with the marker; no early commit may survive an interruption.
        text = re.sub(r'(?im)^[ \t]*(?:BEGIN|COMMIT);[ \t]*$', '', text)
        prelude = f'SET search_path={schema},public;\n'
        for key, value in (variables or {}).items():
            prelude += f'\\set {key} {value}\n'
        pending[db].append(prelude + text + '\n')
        return ''

    helper.psql = command
    helper.psql_script = script
    # Create roles without known fixture credentials. Intercept only the role DDL.
    original = helper.psql
    def roles_command(db, text, check=True):
        text = re.sub(r"CREATE ROLE (\w+) LOGIN PASSWORD '[^']+';",
                      lambda m: f"CREATE ROLE {m[1]} LOGIN PASSWORD '{passwords[m[1]]}';", text)
        return original(db, text, check)
    helper.psql = roles_command
    helper.initialize_databases_and_roles()
    collecting = True
    helper.initialize_v3_write()
    helper.initialize_v3_read()
    helper.grant_runtime_readers()
    for db, statements in pending.items():
        marker = sql(db, "SELECT to_regclass('public.pocoma_local_bootstrap') IS NOT NULL;")
        if marker.strip() == 't':
            if sql(db, 'SELECT fingerprint FROM public.pocoma_local_bootstrap;').strip() != expected:
                raise RuntimeError('DB-INPUT-DRIFT')
            continue
        sql(db, 'BEGIN;\n' + '\n'.join(statements) +
            '\nCREATE TABLE public.pocoma_local_bootstrap(fingerprint text PRIMARY KEY);\n' +
            f"INSERT INTO public.pocoma_local_bootstrap VALUES ('{expected}');\nCOMMIT;\n")
    return expected
