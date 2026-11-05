\set ON_ERROR_STOP on
-- Apply as trusted schema administrator AFTER V7. Dedicated LOGIN, no memberships.
-- Required variables: expected_database, expected_schema, expected_admin, admission_role.
begin;
select set_config('pocoma.admission_database', :'expected_database', true);
select set_config('pocoma.admission_schema', :'expected_schema', true);
select set_config('pocoma.admission_admin', :'expected_admin', true);
select set_config('pocoma.admission_role', :'admission_role', true);
do $$
declare r text:=current_setting('pocoma.admission_role'); item text;
begin
 if current_database()<>current_setting('pocoma.admission_database')
    or current_schema()<>current_setting('pocoma.admission_schema') or current_schema()='public'
    or current_user<>current_setting('pocoma.admission_admin') or current_user=r
    or not exists(select 1 from pg_roles where rolname=r and rolcanlogin and not(rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
    or exists(select 1 from pg_roles where rolname<>r and pg_has_role(r,oid,'MEMBER'))
    or has_schema_privilege(r,current_schema(),'CREATE')
    or to_regclass('v3_admission_association') is null
 then raise exception 'unsafe admission authority' using errcode='42501';end if;
 -- Superuser-only logging defaults are provisioned administratively, never set by the runtime.
 foreach item in array array['log_statement=none','log_duration=off','log_min_duration_statement=-1',
   'log_min_duration_sample=-1','log_transaction_sample_rate=0','log_parameter_max_length=0',
   'log_parameter_max_length_on_error=0','log_min_error_statement=panic','log_error_verbosity=terse','log_min_messages=warning'] loop
   execute format('alter role %I in database %I set %I to %L',r,current_database(),split_part(item,'=',1),split_part(item,'=',2));
 end loop;
 execute format('revoke all on all tables in schema %I from %I',current_schema(),r);
 execute format('revoke all on all sequences in schema %I from %I',current_schema(),r);
 execute format('grant usage on schema %I to %I',current_schema(),r);
 execute format('grant select,insert on v3_admission_association to %I',r);
 execute format('grant select,insert(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload) on v3_command to %I',r);
 execute format('grant select,insert(request_id,requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,admission_context,created_at) on v3_identity_request to %I',r);
 -- SECURITY INVOKER deferred PENDING checks, never business-authority reads by the service.
 execute format('grant select on v3_command_result,v3_identity_request_result,v3_identity_event,v3_identity_completion_event,v3_event_position,consumption_slot to %I',r);
 execute format('grant select(created_request_id) on v3_pocoma_user to %I',r);
 execute format('grant select(request_id) on v3_eui_version to %I',r);
end $$;
commit;
