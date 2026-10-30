\set ON_ERROR_STOP on
\if :{?identity_authority}
\else
\set identity_authority fixture
\endif
-- DEV fixtures and internal injector only. Existing lifecycle/discovery grants stay unchanged.
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
select set_config('pocoma.dev_environment', :'environment', true);
select set_config('pocoma.dev_runtime', :'runtime_role', true);
select set_config('pocoma.dev_injector', :'injector_role', true);
do $$
declare r text:=current_setting('pocoma.dev_runtime');i text:=current_setting('pocoma.dev_injector');t text;col record;sel boolean;
begin
 if current_database()<>current_setting('pocoma.expected_database') or current_schema()<>current_setting('pocoma.expected_schema')
    or current_user<>current_setting('pocoma.expected_admin') or current_schema()='public'
    or current_setting('pocoma.dev_environment')<>'DEV' or r=i or r=current_user or i=current_user
    or not exists(select 1 from pg_roles where rolname=r and rolcanlogin and not(rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
    or not exists(select 1 from pg_roles where rolname=i and rolcanlogin and not(rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
    or exists(select 1 from pg_roles where rolname<>r and pg_has_role(r,oid,'MEMBER'))
    or exists(select 1 from pg_roles where rolname<>i and pg_has_role(i,oid,'MEMBER'))
    or has_schema_privilege(r,current_schema(),'CREATE') or has_schema_privilege(i,current_schema(),'CREATE')
 then raise exception 'unsafe I5 DEV grant target/roles' using errcode='42501';end if;
 if to_regclass('i5_fixture_identity') is not null then
    execute format('grant select,update(revision) on i5_fixture_identity to %I',r);
 end if;
 if to_regclass('i5_read_probe_effect') is not null then
    execute format('grant select,insert on i5_read_probe_effect to %I',r);
 end if;
 if to_regclass('v3_command') is not null and current_setting('pocoma.identity_authority')='fixture' then
    execute format('grant usage on schema %I to %I',current_schema(),i);
    -- The deferred admission constraint reads the result even for PENDING.
    execute format('grant select on v3_command_result to %I',i);
    execute format('grant select,insert(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload) on v3_command to %I',i);
 end if;
 if current_setting('pocoma.identity_authority')='real' and to_regclass('v3_identity_request') is not null then
    execute format('grant usage on schema %I to %I',current_schema(),i);
    execute format('grant select,insert on v3_identity_request to %I',i);
    -- Invoker PENDING-unit check: no token column or business mutation granted.
    execute format('grant select on v3_identity_request_result,v3_identity_event,v3_identity_completion_event,v3_event_position,consumption_slot to %I',i);
    execute format('grant select(created_request_id) on v3_pocoma_user to %I',i);
    execute format('grant select(request_id) on v3_eui_version to %I',i);
    -- Exhaustive effective allowlist: table-only rights are not column rights.
    foreach t in array array['v3_identity_request','v3_eui_authority','v3_pocoma_user','v3_eui_version',
         'v3_identity_request_result','v3_identity_event','v3_identity_completion_event',
         'v3_event_position','consumption_slot'] loop
     if has_table_privilege(i,t,'DELETE,TRUNCATE,TRIGGER')
        or has_table_privilege(i,t,'INSERT')<>(t='v3_identity_request')
        or has_any_column_privilege(i,t,'UPDATE,REFERENCES') then
      raise exception 'unsafe Identity internal injector table rights on %',t using errcode='42501';end if;
     for col in select column_name from information_schema.columns where table_schema=current_schema() and table_name=t loop
      sel:=t in ('v3_identity_request','v3_identity_request_result','v3_identity_event',
            'v3_identity_completion_event','v3_event_position','consumption_slot')
        or (t='v3_pocoma_user' and col.column_name='created_request_id')
        or (t='v3_eui_version' and col.column_name='request_id');
      if has_column_privilege(i,t,col.column_name,'SELECT')<>sel
         or has_column_privilege(i,t,col.column_name,'INSERT')<>(t='v3_identity_request') then
       raise exception 'unsafe Identity internal injector column rights on %.%',t,col.column_name using errcode='42501';end if;
     end loop;
    end loop;
 end if;
end $$;
commit;
