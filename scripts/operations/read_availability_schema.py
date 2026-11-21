"""Canonical C3 migration and catalog probes shared by setup and READ reconstruction."""

READ_AVAILABILITY_MIGRATION = (
    "app/infra-postgres-read/src/main/resources/db/read/availability/migration/"
    "V5__projection_availability.sql"
)

READ_AVAILABILITY_RELATIONS = (
    "read_availability_counter", "read_availability_event", "read_current_availability",
    "read_availability_effects",
)

READ_AVAILABILITY_FUNCTIONS = (
    "read_availability_schema_valid()", "read_availability_valid()",
    "read_current_availability_page(text,bigint,integer)",
)


def _availability_catalog_checks(schema):
    return [
        *(f"to_regclass('{schema}.{name}') is not null" for name in READ_AVAILABILITY_RELATIONS),
        *(f"to_regprocedure('{schema}.{name}') is not null" for name in READ_AVAILABILITY_FUNCTIONS),
    ]


def availability_catalog_present(schema):
    return "SELECT " + " OR ".join(_availability_catalog_checks(schema))


def availability_catalog_complete(schema):
    return "SELECT " + " AND ".join(_availability_catalog_checks(schema))
