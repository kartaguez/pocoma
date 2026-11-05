\set ON_ERROR_STOP on
-- Apply as trusted schema administrator AFTER V8. Dedicated LOGIN, no memberships.
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
 -- Remove former direct column grants as well, so reapplying the script converges.
 for item in select format('%I.%I',n.nspname,c.relname) from pg_class c
     join pg_namespace n on n.oid=c.relnamespace where n.nspname=current_schema() and c.relkind in ('r','p') loop
   execute (select format('revoke select(%s),insert(%s),update(%s),references(%s) on %s from %I',
       cols,cols,cols,cols,item,r) from
       (select string_agg(quote_ident(attname),',') cols from pg_attribute
        where attrelid=item::regclass and attnum>0 and not attisdropped) a);
 end loop;
 execute format('grant usage on schema %I to %I',current_schema(),r);
 -- Application reads only the association; request reads belong to invoker constraints.
 execute format('grant select(request_id,category,operation,canonical_version,lookup_key_id,comparison_key_id,scope_key_fingerprint,intent_fingerprint,created_at),insert on v3_admission_association to %I',r);
 execute format('grant select(request_id,status,pot_id),insert(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload) on v3_command to %I',r);
 execute format('grant select(request_id,status,operation,target_issuer,target_subject),insert(request_id,requester_issuer,requester_subject,operation,binding_token,target_issuer,target_subject,admission_context,created_at) on v3_identity_request to %I',r);
 -- Exact prefix reads for deferred PENDING checks; no business-authority reads by the service.
 execute format('grant select(request_id,outcome,resulting_pot_version) on v3_command_result to %I',r);
 execute format('grant select(request_id,outcome,target_eui_id,target_version) on v3_identity_request_result to %I',r);
 execute format('grant select(request_id) on v3_identity_event to %I',r);
 execute format('grant select(event_id,subject_type,subject_id) on v3_identity_completion_event to %I',r);
 execute format('grant select(event_id) on v3_event_position to %I',r);
 execute format('grant select(consumption_id,consumable_id) on consumption_slot to %I',r);
 execute format('grant select(created_request_id) on v3_pocoma_user to %I',r);
 execute format('grant select(request_id) on v3_eui_version to %I',r);
end $$;
commit;
