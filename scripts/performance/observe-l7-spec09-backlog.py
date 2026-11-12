#!/usr/bin/env python3
"""Capture or reconcile the fixed F01-G population using read-only SQL services."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import uuid


POPULATION = 10_000
POT_CONSUMPTIONS = (
    "3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a",
    "190a93c6-b8af-5ac7-8001-72e2ec53c2df",
    "b4d04cce-b5da-5d1b-9698-5ff9af9a46fa",
)
RESULT_CONSUMPTION = "63664303-d33e-5d42-8cce-c4dae8023311"


def population(path):
    request_ids = json.loads(path.read_text())
    if not isinstance(request_ids, list) or len(request_ids) != POPULATION:
        raise ValueError(f"Supply exactly {POPULATION} admitted request UUIDs")
    identifiers = sorted(str(uuid.UUID(value)) for value in request_ids)
    if len(set(identifiers)) != POPULATION:
        raise ValueError("The initial population contains duplicate request UUIDs")
    return identifiers


def json_literal(value):
    return "'" + json.dumps(value, separators=(",", ":")).replace("'", "''") + "'::jsonb"


def snapshot(service, schema, statement):
    argv = ["psql", "-X", "-q", "-t", "-A", "-w", "-v", "ON_ERROR_STOP=1"]
    env = {**os.environ, "PGSERVICE": service, "PGCONNECT_TIMEOUT": "5"}
    sql = ("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;\n"
           f"SET LOCAL search_path = {schema}, public;\n"
           "SET LOCAL statement_timeout = '5s';\n"
           "SET LOCAL standard_conforming_strings = on;\n"
           f"{statement}\nCOMMIT;\n")
    result = subprocess.run(argv, env=env, input=sql, text=True, capture_output=True, timeout=12)
    if result.returncode:
        raise RuntimeError(f"The {schema} read-only snapshot failed with psql exit {result.returncode}")
    return json.loads(result.stdout)


def write_statement(request_ids):
    return f"""
with ids as (
    select value::uuid as request_id from jsonb_array_elements_text({json_literal(request_ids)})
), facts as (
    select i.request_id, c.pot_id, c.status, c.operation, c.observed_version,
           admitted.position as request_position,
           r.outcome, r.resulting_pot_version as version, p.event_id as pot_event,
           p.subject_version as event_version, pp.position as pot_position,
           e.event_id as result_event, ep.position as result_position,
           case when r.request_id is not null then encode(sha256(convert_to(jsonb_build_array(
               c.operation,c.requester_issuer,c.requester_subject,c.pot_id,
               r.outcome,r.resulting_pot_version,r.rejection)::text,'UTF8')),'hex') end as result_hash,
           (select jsonb_agg(jsonb_build_object(
                'kind', component.kind, 'id', component.object_id, 'v', component.local_version))
              from v3_pot_event_component component where component.event_id=p.event_id) as components,
           exists(select 1 from consumption_slot slot join consumption_routing_contract route
             using (consumption_id)
             where route.family='COMMAND' and slot.consumable_id=c.request_id) as slot_present
      from ids i
      left join v3_command c using (request_id)
      left join v3_request_position admitted on admitted.request_id=i.request_id and admitted.family='COMMAND'
      left join v3_command_result r on r.request_id=i.request_id
      left join v3_pot_event p on p.request_id=i.request_id
      left join v3_event_position pp on pp.event_id=p.event_id and pp.family='POT'
      left join v3_command_completion_event e on e.subject_id=i.request_id
      left join v3_event_position ep on ep.event_id=e.event_id and ep.family='COMMAND_COMPLETION'
)
select jsonb_agg(to_jsonb(facts) order by request_id) from facts;
"""


def read_statement(changed):
    target = [{k: row[k] for k in ("request_id", "pot_id", "version", "result_hash",
                                   "pot_event", "pot_position", "result_event", "result_position",
                                   "components")} for row in changed]
    routes = ",".join(f"('{route}'::uuid, 'POT_EVENT')" for route in POT_CONSUMPTIONS)
    routes += f",('{RESULT_CONSUMPTION}'::uuid, 'COMMAND_COMPLETION_EVENT')"
    return f"""
with targets as (
    select * from jsonb_to_recordset({json_literal(target)}) as t(
        request_id uuid, pot_id uuid, version bigint, result_hash text,
        pot_event uuid, pot_position bigint, result_event uuid, result_position bigint,
        components jsonb)
), expected_routes(consumption_id,family) as (values {routes}), expected_slots as (
    select t.request_id, route.consumption_id, route.family,
           case when route.family='POT_EVENT' then t.pot_event else t.result_event end as event_id,
           case when route.family='POT_EVENT' then t.pot_position else t.result_position end as pos
      from targets t cross join expected_routes route
), coverage as (
    select expected.request_id, count(*) filter (where actual.family=expected.family
           and checkpoint.position>=expected.pos and slot.consumable_id is null) as covered
      from expected_slots expected
      left join consumption_routing_contract actual using (consumption_id)
      left join discovery_checkpoint checkpoint on checkpoint.consumption_id=expected.consumption_id
        and checkpoint.partition_id=((consumption_hash_v1(expected.family,expected.event_id)::bigint%4+4)%4)
      left join consumption_slot slot on slot.consumption_id=expected.consumption_id
        and slot.consumable_id=expected.event_id
     group by expected.request_id
), observations as (
    select t.request_id, coverage.covered=4 as complete_slots,
           coalesce(encode(sha256(convert_to(jsonb_build_array(
               r.operation,r.requester_issuer,r.requester_subject,r.pot_id,
               r.write_outcome,r.resulting_pot_version,r.rejection)::text,'UTF8')),'hex')
               =t.result_hash,false) as result_matches,
           coalesce(version.payload->'deleted'='false'::jsonb
                and current_pot.pot_version>=t.version,false) as pot_ready,
           balance.pot_id is not null as balance_ready,
           coalesce(visibility.materialized_version>=t.version,false) as visibility_ready,
           not exists (select 1 from jsonb_to_recordset(t.components) as part(kind text,id uuid,v bigint)
              where not exists (select 1 from read_subobject_version sv
                where (sv.pot_id,sv.kind,sv.object_id,sv.local_version)=(t.pot_id,part.kind,part.id,part.v)))
             as subobjects_ready
      from targets t join coverage using (request_id)
      left join read_command_result r using (request_id)
      left join read_pot_version version on (version.pot_id,version.pot_version)=(t.pot_id,t.version)
      left join read_current_pot current_pot on current_pot.pot_id=t.pot_id
      left join read_pot_balance balance on (balance.pot_id,balance.pot_version)=(t.pot_id,t.version)
      left join read_pot_visibility_state visibility on visibility.pot_id=t.pot_id
)
select coalesce(jsonb_agg(to_jsonb(observations) order by request_id),'[]'::jsonb) from observations;
"""


def applied(row):
    return (row["status"] == "TERMINAL" and row["operation"] == "CreateExpense"
            and row["outcome"] == "CHANGED" and row["request_position"] is not None
            and row["version"] == row["event_version"] == row["observed_version"] + 1
            and not row["slot_present"]
            and all(row[key] for key in ("pot_position", "result_position", "result_hash", "components")))


def observe(args, request_ids):
    fingerprint = hashlib.sha256("\n".join(request_ids).encode()).hexdigest()
    if args.mode == "reconcile":
        before = json.loads(args.before.read_text())
        if before.get("status") != "INITIAL_STOCK_VERIFIED" or before.get("requestSha256") != fingerprint:
            raise ValueError("Initial stock receipt does not match this population")
    rows = snapshot(args.write_service, "v3_write", write_statement(request_ids))
    operations = {row["operation"] for row in rows}
    pots = {row["pot_id"] for row in rows}
    common = {"requestSha256": fingerprint, "expected": POPULATION, "rows": len(rows),
              "distinctPots": len(pots), "operations": sorted(x for x in operations if x)}
    if args.mode == "capture":
        pending = sum(row["status"] == "PENDING" and row["request_position"] is not None
                      and row["outcome"] is None
                      and row["pot_event"] is None and row["result_event"] is None
                      and not row["slot_present"] for row in rows)
        verified = (pending == POPULATION and len(pots) == POPULATION and None not in pots
                    and operations == {"CreateExpense"})
        return {**common, "status": "INITIAL_STOCK_VERIFIED" if verified else "INCOMPLETE_STOCK",
                "pendingWithoutDerivedWork": pending}, verified

    changed = [row for row in rows if applied(row)]
    observations = snapshot(args.read_service, "v3_read", read_statement(changed))
    incomplete = [
        {"request_id": row["request_id"], "missing": sorted(key for key, value in row.items() if value is False)}
        for row in observations if False in row.values()
    ]
    verified = len(changed) == len(observations) == POPULATION and not incomplete
    return {**common, "status": "CONVERGED" if verified else "INCOMPLETE",
            "writeApplied": len(changed), "readObserved": len(observations),
            "incompleteRead": incomplete,
            "nonApplied": [row["request_id"] for row in rows if not applied(row)]}, verified


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("capture", "reconcile"))
    parser.add_argument("--write-service", required=True, help="libpq service for WRITE observer")
    parser.add_argument("--read-service", help="libpq service for READ observer")
    parser.add_argument("--requests", type=Path, required=True, help="JSON array of initial request UUIDs")
    parser.add_argument("--before", type=Path, help="Successful capture receipt")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.mode == "reconcile" and (not args.before or not args.read_service
                                      or args.read_service == args.write_service):
        parser.error("reconcile requires an initial receipt and distinct WRITE and READ services")
    if args.output.exists():
        parser.error("Use a new output path for each snapshot")
    started = time.monotonic()
    observation, verified = observe(args, population(args.requests))
    observation.update(observedAtUtc=datetime.now(timezone.utc).isoformat(),
                       observationSeconds=time.monotonic()-started)
    with args.output.open("x") as stream:
        json.dump(observation, stream, indent=2)
        stream.write("\n")
    return 0 if verified else 1


if __name__ == "__main__":
    raise SystemExit(main())
