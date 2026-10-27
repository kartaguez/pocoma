-- Post-bootstrap only. Existing roles, exact target; no credentials, role creation or runtime DDL.
\set ON_ERROR_STOP on
begin;
select set_config('pocoma.expected_database', :'expected_database', true);
select set_config('pocoma.expected_schema', :'expected_schema', true);
select set_config('pocoma.expected_admin', :'expected_admin', true);
select set_config('pocoma.runtime_role', :'runtime_role', true);
select set_config('pocoma.reactivation_role', :'reactivation_role', true);
do $$
declare r text := current_setting('pocoma.runtime_role'); a text := current_setting('pocoma.reactivation_role');
begin
 if current_database() <> current_setting('pocoma.expected_database')
    or current_schema() <> current_setting('pocoma.expected_schema') or current_schema()='public'
    or current_user <> current_setting('pocoma.expected_admin') or r=a or r=current_user or a=current_user
    or not exists(select 1 from pg_roles where rolname=r and not (rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
    or not exists(select 1 from pg_roles where rolname=a and not (rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
    or exists(select 1 from pg_roles where rolname<>r and pg_has_role(r,oid,'MEMBER'))
    or exists(select 1 from pg_roles where rolname<>a and pg_has_role(a,oid,'MEMBER'))
    or not exists(select 1 from consumption_routing_contract)
 then raise exception 'unsafe lifecycle grant target/roles' using errcode='23514'; end if;
end $$;
grant usage on schema :"expected_schema" to :"runtime_role", :"reactivation_role";
grant select on consumption_routing_contract,partition_ownership,consumption_slot,discovery_checkpoint to :"runtime_role";
grant update(owner_token,owned_until) on partition_ownership to :"runtime_role";
grant update(claim_token,claimed_until,retry_after,attempt_count,state),delete on consumption_slot to :"runtime_role";
grant select(consumption_id,family) on consumption_routing_contract to :"reactivation_role";
grant select,update(claim_token,claimed_until,retry_after,attempt_count,state) on consumption_slot to :"reactivation_role";
-- Exact Command writer grants only on WRITE; I3 never grants admission/Discovery writes.
select (to_regclass('v3_command') is not null) as is_write \gset
\if :is_write
grant select on v3_command,v3_command_result,v3_pot_identity,v3_pot_version,v3_shareholder_identity,v3_expense_identity,v3_pot_header,v3_pot_details,v3_shareholder_header,v3_shareholder_details,v3_shareholder_weight,v3_expense_header,v3_expense_details,v3_expense_financials,v3_expense_participant,v3_pot_event,v3_pot_event_component,v3_command_completion_event,v3_event_counter,v3_event_position to :"runtime_role";
grant insert on v3_pot_identity,v3_pot_version,v3_shareholder_identity,v3_expense_identity,v3_pot_header,v3_pot_details,v3_shareholder_header,v3_shareholder_details,v3_shareholder_weight,v3_expense_header,v3_expense_details,v3_expense_financials,v3_expense_participant,v3_command_result,v3_pot_event,v3_pot_event_component,v3_command_completion_event,v3_event_position to :"runtime_role";
grant update(active_to) on v3_pot_header,v3_pot_details,v3_shareholder_header,v3_shareholder_details,v3_shareholder_weight,v3_expense_header,v3_expense_details,v3_expense_financials to :"runtime_role";
grant update(current_version) on v3_pot_identity to :"runtime_role";
grant update(status) on v3_command to :"runtime_role";
grant update(position) on v3_event_counter to :"runtime_role";
\endif
-- Refuse preexisting broader effective grants; reexecution never silently normalizes an unsafe role.
do $$
declare r text := current_setting('pocoma.runtime_role'); a text := current_setting('pocoma.reactivation_role'); t text; col record; allowed boolean;
begin
 if has_schema_privilege(r,current_schema(),'CREATE') or has_schema_privilege(a,current_schema(),'CREATE') then
   raise exception 'lifecycle roles may not create schema objects' using errcode='42501'; end if;
 foreach t in array array['consumption_routing_contract','partition_ownership','consumption_slot','discovery_checkpoint'] loop
   if has_table_privilege(r,t,'INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER')
      or (has_table_privilege(r,t,'DELETE') <> (t='consumption_slot'))
      or has_table_privilege(a,t,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') then
     raise exception 'unsafe effective table rights on %',t using errcode='42501'; end if;
   for col in select column_name from information_schema.columns where table_schema=current_schema() and table_name=t loop
     allowed := (t='partition_ownership' and col.column_name in ('owner_token','owned_until'))
       or (t='consumption_slot' and col.column_name in ('claim_token','claimed_until','retry_after','attempt_count','state'));
     if has_column_privilege(r,t,col.column_name,'UPDATE') <> allowed
        or has_column_privilege(r,t,col.column_name,'INSERT,REFERENCES')
        or has_column_privilege(a,t,col.column_name,'UPDATE') <> (t='consumption_slot' and col.column_name in ('claim_token','claimed_until','retry_after','attempt_count','state'))
        or has_column_privilege(a,t,col.column_name,'INSERT,REFERENCES') then
       raise exception 'unsafe effective column rights on %.%',t,col.column_name using errcode='42501'; end if;
   end loop;
 end loop;
end $$;
commit;
