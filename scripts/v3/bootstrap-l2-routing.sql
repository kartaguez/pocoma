-- psql -X -v ON_ERROR_STOP=1; expected_* and authority are mandatory.
begin;
lock table consumption_routing_contract in access exclusive mode;
-- Assertions repeat inside the locked transaction; arguments have no defaults.
select set_config('pocoma.expected_database', :'expected_database', true);
select set_config('pocoma.expected_schema', :'expected_schema', true);
select set_config('pocoma.expected_admin', :'expected_admin', true);
select set_config('pocoma.bootstrap_authority', :'authority', true);
do $$
declare a text := current_setting('pocoma.bootstrap_authority');
begin
  if a not in ('WRITE','READ')
     or current_database() <> current_setting('pocoma.expected_database')
     or current_schema() <> current_setting('pocoma.expected_schema')
     or current_schema() = 'public'
     or ((a = 'WRITE') <> (to_regclass(format('%I.v3_command',current_schema())) is not null))
     or current_user <> current_setting('pocoma.expected_admin')
     or exists(select 1 from consumption_routing_contract)
     or exists(select 1 from partition_ownership)
     or exists(select 1 from discovery_checkpoint)
     or exists(select 1 from consumption_slot) then
    raise exception 'wrong or nonempty L2 bootstrap target' using errcode='23514';
  end if;
end $$;
with definitions(id,family,authority) as (values
 ('98f7268d-d340-581c-b83d-010926223e14'::uuid,'COMMAND','WRITE'),
 ('2999e75c-272d-5afe-a966-c9364ef021fe'::uuid,'IDENTITY_REQUEST','WRITE'),
 ('3dec1c8b-6e3e-57e1-82aa-aa0b5f297a4a'::uuid,'POT_EVENT','READ'),
 ('190a93c6-b8af-5ac7-8001-72e2ec53c2df'::uuid,'POT_EVENT','READ'),
 ('b4d04cce-b5da-5d1b-9698-5ff9af9a46fa'::uuid,'POT_EVENT','READ'),
 ('924cd9e3-435d-5804-9d1e-c6aecadb9ab4'::uuid,'INVITATION_EVENT','READ'),
 ('7b8d2838-bf7a-5117-868a-e5c14b4c0221'::uuid,'IDENTITY_EVENT','READ'),
 ('63664303-d33e-5d42-8cce-c4dae8023311'::uuid,'COMMAND_COMPLETION_EVENT','READ'),
 ('1d8ff246-358f-5ddd-a7f5-416efcfd7445'::uuid,'IDENTITY_COMPLETION_EVENT','READ')
)
insert into consumption_routing_contract(consumption_id,family,routing_version,partition_count)
select id,family,'sha256-v1',4 from definitions
where authority=current_setting('pocoma.bootstrap_authority');
insert into partition_ownership(consumption_id,partition_id)
select c.consumption_id,p from consumption_routing_contract c
cross join generate_series(0,3) p;
do $$
declare a text := current_setting('pocoma.bootstrap_authority');
begin
  if (select count(*) from consumption_routing_contract) <>
     (case a when 'WRITE' then 2 else 7 end)
     or (select count(*) from partition_ownership) <>
     (case a when 'WRITE' then 8 else 28 end)
     or exists(select 1 from discovery_checkpoint)
     or exists(select 1 from consumption_slot) then
    raise exception 'incomplete routing bootstrap' using errcode='23514';
  end if;
end $$;
commit;
