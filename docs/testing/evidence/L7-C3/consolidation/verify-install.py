"""Exercise the local setup paths against an isolated container supplied by the caller."""
import importlib
import os
import sys

sys.path[:0] = ["scripts/operations", "scripts/local"]
setup = importlib.import_module("setup-v3-database")
historical = importlib.import_module("setup-v4-audit")
container, scenario = sys.argv[1:]
setup.POSTGRES_CONTAINER = historical.POSTGRES_CONTAINER = container

def read(sql):
    return setup.psql("pocoma_read", sql)

def assert_read_install():
    for sql, expected in [
        ("show server_version", "17."),
        ("select v3_read.read_availability_schema_valid() and v3_read.read_availability_valid()", "t"),
        ("select count(*) from v3_read.read_availability_counter", "8"),
        ("select count(*) from v3_read.consumption_routing_contract", "7"),
        ("select count(*) from v3_read.partition_ownership", "28"),
        ("""select state || '/' || phase from public.read_maintenance_state where id = 1""", "READY/IDLE"),
        ("""select has_column_privilege('pocoma_read','v3_read.consumption_slot','consumable_id','insert')
             and has_table_privilege('pocoma_read','v3_read.read_availability_event','insert')
             and not has_table_privilege('pocoma_read','v3_read.read_availability_event','delete')
             and not exists (
               select from (values ('read_availability_counter'), ('read_availability_event'),
                 ('read_current_availability'), ('read_availability_effects')) metadata(relation)
               where has_table_privilege('pocoma_read_http', 'v3_read.' || relation, 'select'))
             and not has_function_privilege('pocoma_read_http',
               'v3_read.read_current_availability_page(text,bigint,integer)', 'execute')""", "t"),
    ]:
        observed = read(sql)
        assert observed.startswith(expected) if expected == "17." else observed == expected, (sql, observed, expected)
        print(observed, " ".join(sql.split()))

def initialization_with_injected_error(db, initialize, last_statement):
    original = setup.psql_script

    def inject(database, schema, sql, **options):
        if database == db and last_statement in sql.lower():
            sql += "\nselect 1/0;\n"
        return original(database, schema, sql, **options)

    setup.psql_script = inject
    try:
        initialize()
    except RuntimeError as error:
        assert "division by zero" in str(error), str(error)
        print(f"{db}: {error}")
    else:
        raise AssertionError("injected failure was not reached")
    finally:
        setup.psql_script = original

if scenario == "fresh":
    setup.initialize_databases_and_roles()
    initialization_with_injected_error("pocoma_write", setup.initialize_v3_write, "insert into consumption_routing_contract")
    assert setup.psql("pocoma_write", "select to_regclass('v3_write.v3_command') is null") == "t"
    setup.initialize_v3_write()
    initialization_with_injected_error("pocoma_read", setup.initialize_v3_read, "insert into consumption_routing_contract")
    assert read("select to_regclass('v3_read.read_current_pot') is null") == "t"
    assert read("select to_regclass('v3_read.read_availability_event') is null") == "t"
    setup.initialize_v3_read()
    setup.grant_runtime_readers()
    assert_read_install()
    setup.initialize_v3_write()
    setup.initialize_v3_read()
    setup.grant_runtime_readers()
    assert_read_install()
    read("truncate v3_read.partition_ownership cascade")
    try:
        setup.initialize_v3_read()
    except RuntimeError as error:
        assert "Incomplete v3_read routing bootstrap" in str(error), str(error)
        print(error)
    else:
        raise AssertionError("incomplete routing was accepted")
elif scenario in ("empty-v4", "populated-v4"):
    historical.initialize_databases_and_roles()
    historical.initialize_v3_write()
    historical.initialize_v3_read()
    historical.grant_runtime_readers()
    assert read("select to_regclass('v3_read.read_availability_event') is null") == "t"
    if scenario == "empty-v4":
        setup.initialize_v3_read()
        setup.grant_runtime_readers()
        assert_read_install()
    else:
        read("insert into v3_read.read_pot_visibility_state values ('00000000-0000-0000-0000-000000000010',1)")
        try:
            setup.initialize_v3_read()
        except RuntimeError as error:
            assert "reconstruct" in str(error).lower() or "empty" in str(error).lower(), str(error)
            print(error)
        else:
            raise AssertionError("populated V4 was accepted")
        assert read("select count(*) from v3_read.read_pot_visibility_state") == "1"
        assert read("select to_regclass('v3_read.read_availability_event') is null") == "t"
else:
    raise ValueError(scenario)
