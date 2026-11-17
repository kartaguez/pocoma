\set ON_ERROR_STOP on
begin isolation level read committed;
set local lock_timeout = :'lock_timeout';
set local statement_timeout = :'statement_timeout';
select activate_event_sequencing(:'operation_id'::uuid, :'runtime_role'::name) as historical_boundaries;
select * from inspect_event_sequencing();
select * from event_sequencing_cutovers where operation_id=:'operation_id'::uuid;
commit;
