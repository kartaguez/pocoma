-- DEV fixtures and internal injector only. Existing lifecycle/discovery grants stay unchanged.
begin;
select set_config('pocoma.expected_database', :'expected_database', true);
select set_config('pocoma.expected_schema', :'expected_schema', true);
select set_config('pocoma.expected_admin', :'expected_admin', true);
select set_config('pocoma.dev_environment', :'environment', true);
select set_config('pocoma.dev_runtime', :'runtime_role', true);
select set_config('pocoma.dev_injector', :'injector_role', true);
do $$
declare r text:=current_setting('pocoma.dev_runtime');i text:=current_setting('pocoma.dev_injector');
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
 if to_regclass('v3_command') is not null then
    execute format('grant usage on schema %I to %I',current_schema(),i);
    -- The deferred admission constraint reads the result even for PENDING.
    execute format('grant select on v3_command_result to %I',i);
    execute format('grant select,insert(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload) on v3_command to %I',i);
 end if;
end $$;
commit;
