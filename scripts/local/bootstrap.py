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
    paths = sorted({*ROOT.glob('app/infra-postgres-*/src/main/resources/db/v3-*/**/*.sql'),
                    *ROOT.glob('scripts/v3/*.sql'),
                    ROOT / 'scripts/operations/setup-v3-database.py', Path(__file__)})
    return hashlib.sha256(''.join(str(p.relative_to(ROOT)) + ':' +
                                 hashlib.sha256(p.read_bytes()).hexdigest() + '\n'
                                 for p in paths).encode()).hexdigest()


def initialize(sql, passwords):
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
    expected = fingerprint()
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
