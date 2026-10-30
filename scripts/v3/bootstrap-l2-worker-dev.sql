\if :{?identity_authority}
\else
\set identity_authority fixture
\endif
-- Administrative DEV fixture only. Never called by a Worker or on restart.
-- Required psql variables: expected_database/schema/admin, authority, environment,
-- fixture_identity, technical_probes. Canonical bootstrap must already be installed.
begin;
-- L3 real is explicit, requires V6, and never installs the fixture.
select set_config('pocoma.identity_authority', :'identity_authority', true);
do $$
begin
 if current_setting('pocoma.identity_authority') not in ('fixture','real') then raise exception 'invalid Identity authority';end if;
 if current_setting('pocoma.identity_authority')='real' and to_regclass('v3_command') is not null then
  if to_regclass('v3_identity_request') is null or to_regprocedure('v3_identity_check_unit(uuid,boolean)') is null
    or to_regclass('i5_fixture_identity') is not null then raise exception 'Identity real requires V6 without fixture';end if;
 end if;
end $$;

select set_config('pocoma.expected_database', :'expected_database', true);
select set_config('pocoma.expected_schema', :'expected_schema', true);
select set_config('pocoma.expected_admin', :'expected_admin', true);
select set_config('pocoma.dev_authority', :'authority', true);
select set_config('pocoma.dev_environment', :'environment', true);
select set_config('pocoma.dev_fixture', :'fixture_identity', true);
select set_config('pocoma.dev_probes', :'technical_probes', true);
lock table consumption_routing_contract,partition_ownership in access exclusive mode;
do $$
declare a text:=current_setting('pocoma.dev_authority');
begin
 if current_database()<>current_setting('pocoma.expected_database')
    or current_schema()<>current_setting('pocoma.expected_schema') or current_schema()='public'
    or current_user<>current_setting('pocoma.expected_admin')
    or current_setting('pocoma.dev_environment')<>'DEV' or a not in ('WRITE','READ')
    or (current_setting('pocoma.identity_authority')='real' and current_setting('pocoma.dev_fixture')<>'false')
    or current_setting('pocoma.dev_fixture') not in ('true','false')
    or current_setting('pocoma.dev_probes') not in ('true','false')
    or ((a='WRITE')<>(to_regclass('v3_command') is not null))
    or exists(select 1 from consumption_slot) or exists(select 1 from discovery_checkpoint)
    or exists(select 1 from partition_ownership where owner_token is not null)
    or to_regclass('i5_fixture_identity') is not null or to_regclass('i5_read_probe_effect') is not null
    or (select count(*) from consumption_routing_contract)<>(case a when 'WRITE' then 2 else 7 end)
    or (select count(*) from partition_ownership)<>(case a when 'WRITE' then 8 else 28 end)
 then raise exception 'unsafe or nonfresh I5 DEV fixture target' using errcode='23514';end if;
 if a='WRITE' and current_setting('pocoma.dev_fixture')='true' then
   create table i5_fixture_identity(issuer text not null,subject text not null,revision bigint not null check(revision>=0),user_id uuid,binding_token text not null,primary key(issuer,subject));
   insert into i5_fixture_identity values('i5-dev','actor',1,'a5000000-0000-4000-8000-000000000010','i5-development-fixture');
 end if;
 if a='READ' and current_setting('pocoma.dev_probes')='true' then
   insert into consumption_routing_contract values
    ('a5000000-0000-4000-8000-000000000001','POT_EVENT','sha256-v1',4),
    ('a5000000-0000-4000-8000-000000000002','COMMAND_COMPLETION_EVENT','sha256-v1',4);
   insert into partition_ownership(consumption_id,partition_id)
    select consumption_id,p from consumption_routing_contract cross join generate_series(0,3) p
     where consumption_id in ('a5000000-0000-4000-8000-000000000001','a5000000-0000-4000-8000-000000000002');
   create table i5_read_probe_effect(consumption_id uuid not null,consumable_id uuid not null,value text not null,primary key(consumption_id,consumable_id),
    check(consumption_id in ('a5000000-0000-4000-8000-000000000001','a5000000-0000-4000-8000-000000000002')),check(value='technical'));
 end if;
end $$;
-- Dedicated fresh DEV databases only: runtime has no temporary-schema DDL path.
revoke temporary on database :"expected_database" from public;
commit;
