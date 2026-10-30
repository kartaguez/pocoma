\if :{?identity_read_role}
\else
\set identity_read_role ''
\endif
\if :{?identity_authority}
\else
\set identity_authority fixture
\endif
-- Explicit I4 union after grant-l2-lifecycle.sql. Administrative psql -X only; never runtime startup.
-- Existing strict I1/I3 scripts remain unchanged. Reexecution refuses excess effective rights.
\set ON_ERROR_STOP on
begin;
-- L3 real is explicit, requires V6, and never installs the fixture.
select set_config('pocoma.identity_authority', :'identity_authority', true);
select set_config('pocoma.identity_read_role', :'identity_read_role', true);
do $$
begin
 if current_setting('pocoma.identity_authority') not in ('fixture','real') then raise exception 'invalid Identity authority';end if;
 if current_setting('pocoma.identity_authority')='real' and to_regclass('v3_command') is not null then
 if exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace,
    lateral aclexplode(c.relacl) acl where n.nspname=current_schema()
    and c.relname in ('v3_identity_request','v3_eui_authority','v3_pocoma_user','v3_eui_version',
      'v3_identity_request_result','v3_identity_event','v3_identity_completion_event') and acl.grantee=0)
    or exists(select 1 from pg_attribute att join pg_class c on c.oid=att.attrelid
      join pg_namespace n on n.oid=c.relnamespace,lateral aclexplode(att.attacl) acl
      where n.nspname=current_schema() and c.relname in ('v3_identity_request','v3_eui_authority',
      'v3_pocoma_user','v3_eui_version','v3_identity_request_result','v3_identity_event',
      'v3_identity_completion_event') and acl.grantee=0)
 then raise exception 'PUBLIC privileges expose Identity authority' using errcode='42501';end if;
  if to_regclass('v3_identity_request') is null or to_regprocedure('v3_identity_check_unit(uuid,boolean)') is null
    or to_regclass('i5_fixture_identity') is not null then raise exception 'Identity real requires V6 without fixture';end if;
 end if;
end $$;

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
    or not exists(select 1 from pg_roles where rolname=r and rolcanlogin and not (rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
    or not exists(select 1 from pg_roles where rolname=a and rolcanlogin and not (rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
    or exists(select 1 from pg_roles where rolname<>r and pg_has_role(r,oid,'MEMBER'))
    or exists(select 1 from pg_roles where rolname<>a and pg_has_role(a,oid,'MEMBER'))
    or not exists(select 1 from consumption_routing_contract)
 then raise exception 'unsafe Discovery grant target/roles' using errcode='23514'; end if;
end $$;
-- Extend only the technical columns; all lifecycle and WRITE business grants are prerequisites.
grant insert(consumption_id,consumable_id,partition_hash,partition_id) on consumption_slot to :"runtime_role";
grant insert(consumption_id,partition_id,position),update(position) on discovery_checkpoint to :"runtime_role";
select (to_regclass('v3_command') is not null) as is_write \gset
\if :is_write
grant select on v3_command,v3_event_counter,v3_event_position,v3_pot_event,v3_command_completion_event to :"runtime_role";
\endif
-- Refuse preexisting broader effective grants; reexecution never silently normalizes an unsafe role.
do $$
declare r text := current_setting('pocoma.runtime_role'); a text := current_setting('pocoma.reactivation_role'); t text; col record; allowed boolean;
begin
 if has_schema_privilege(r,current_schema(),'CREATE') or has_schema_privilege(a,current_schema(),'CREATE') then
   raise exception 'lifecycle roles may not create schema objects' using errcode='42501'; end if;
 if exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where n.nspname=current_schema() and c.relname in ('consumption_routing_contract','partition_ownership','consumption_slot','discovery_checkpoint')
      and (c.relowner in (select oid from pg_roles where rolname in (r,a))
        or exists(select 1 from aclexplode(c.relacl) acl where acl.grantee=0 and acl.privilege_type<>'SELECT')))
    or exists(select 1 from pg_attribute att join pg_class c on c.oid=att.attrelid join pg_namespace n on n.oid=c.relnamespace,
       lateral aclexplode(att.attacl) acl where n.nspname=current_schema()
        and c.relname in ('consumption_routing_contract','partition_ownership','consumption_slot','discovery_checkpoint')
        and acl.grantee=0 and acl.privilege_type<>'SELECT')
 then raise exception 'ownership or PUBLIC mutation grants forbidden' using errcode='42501'; end if;
 foreach t in array array['consumption_routing_contract','partition_ownership','consumption_slot','discovery_checkpoint'] loop
   if not has_table_privilege(r,t,'SELECT') or has_table_privilege(r,t,'INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER')
      or (has_table_privilege(r,t,'DELETE') <> (t='consumption_slot'))
      or has_table_privilege(a,t,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') then
     raise exception 'unsafe Discovery effective table rights on %',t using errcode='42501'; end if;
   for col in select column_name from information_schema.columns where table_schema=current_schema() and table_name=t loop
     allowed := (t='partition_ownership' and col.column_name in ('owner_token','owned_until'))
       or (t='consumption_slot' and col.column_name in ('claim_token','claimed_until','retry_after','attempt_count','state'))
       or (t='discovery_checkpoint' and col.column_name='position');
     if has_column_privilege(r,t,col.column_name,'UPDATE') <> allowed
        or has_column_privilege(r,t,col.column_name,'INSERT') <> ((t='consumption_slot' and col.column_name in ('consumption_id','consumable_id','partition_hash','partition_id')) or t='discovery_checkpoint')
        or has_column_privilege(r,t,col.column_name,'REFERENCES')
        or has_column_privilege(a,t,col.column_name,'UPDATE') <> (t='consumption_slot' and col.column_name in ('claim_token','claimed_until','retry_after','attempt_count','state'))
        or has_column_privilege(a,t,col.column_name,'INSERT,REFERENCES') then
       raise exception 'unsafe Discovery effective column rights on %.%',t,col.column_name using errcode='42501'; end if;
   end loop;
 end loop;
end $$;
-- The combined WRITE profile preserves exactly the delivered I2/I3 business rights too.
\if :is_write
do $$
declare r text:=current_setting('pocoma.runtime_role'); a text:=current_setting('pocoma.reactivation_role');
 t text; col record; ins boolean; upd boolean;
begin
 foreach t in array array['v3_command','v3_command_result','v3_pot_identity','v3_pot_version','v3_shareholder_identity','v3_expense_identity','v3_pot_header','v3_pot_details','v3_shareholder_header','v3_shareholder_details','v3_shareholder_weight','v3_expense_header','v3_expense_details','v3_expense_financials','v3_expense_participant','v3_pot_event','v3_pot_event_component','v3_command_completion_event','v3_event_counter','v3_event_position'] loop
  ins:=t not in ('v3_command','v3_event_counter');
  if not has_table_privilege(r,t,'SELECT') or has_table_privilege(r,t,'INSERT')<>ins
    or has_table_privilege(r,t,'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
    or has_table_privilege(a,t,'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
    or has_any_column_privilege(a,t,'SELECT,INSERT,UPDATE,REFERENCES')
    or exists(select 1 from pg_class c where c.oid=to_regclass(t) and c.relowner in (select oid from pg_roles where rolname in (r,a)))
  then raise exception 'unsafe combined business table privileges on %',t using errcode='42501'; end if;
  for col in select column_name from information_schema.columns where table_schema=current_schema() and table_name=t loop
   upd:=(t='v3_command' and col.column_name='status') or (t='v3_event_counter' and col.column_name='position')
    or (t='v3_pot_identity' and col.column_name='current_version')
    or (t in ('v3_pot_header','v3_pot_details','v3_shareholder_header','v3_shareholder_details','v3_shareholder_weight','v3_expense_header','v3_expense_details','v3_expense_financials') and col.column_name='active_to');
   if has_column_privilege(r,t,col.column_name,'UPDATE')<>upd or has_column_privilege(r,t,col.column_name,'INSERT')<>ins
      or has_column_privilege(r,t,col.column_name,'REFERENCES') then
    raise exception 'unsafe combined business column privileges on %.%',t,col.column_name using errcode='42501'; end if;
  end loop;
 end loop;
 if exists(select 1 from pg_proc where pronamespace=(select oid from pg_namespace where nspname=current_schema()) and prosecdef)
 then raise exception 'SECURITY DEFINER forbidden in combined authority' using errcode='42501'; end if;
end $$;
\endif
do $$
declare r text:=current_setting('pocoma.runtime_role');a text:=current_setting('pocoma.reactivation_role');t text;col record;ins boolean;upd boolean;
begin
 if current_setting('pocoma.identity_authority')='real' and to_regclass('v3_command') is not null then
 if exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace,
    lateral aclexplode(c.relacl) acl where n.nspname=current_schema()
    and c.relname in ('v3_identity_request','v3_eui_authority','v3_pocoma_user','v3_eui_version',
      'v3_identity_request_result','v3_identity_event','v3_identity_completion_event') and acl.grantee=0)
    or exists(select 1 from pg_attribute att join pg_class c on c.oid=att.attrelid
      join pg_namespace n on n.oid=c.relnamespace,lateral aclexplode(att.attacl) acl
      where n.nspname=current_schema() and c.relname in ('v3_identity_request','v3_eui_authority',
      'v3_pocoma_user','v3_eui_version','v3_identity_request_result','v3_identity_event',
      'v3_identity_completion_event') and acl.grantee=0)
 then raise exception 'PUBLIC privileges expose Identity authority' using errcode='42501';end if;
  foreach t in array array['v3_identity_request','v3_eui_authority','v3_pocoma_user','v3_eui_version','v3_identity_request_result','v3_identity_event','v3_identity_completion_event'] loop
   ins:=t<>'v3_identity_request';
   if not has_table_privilege(r,t,'SELECT') or has_table_privilege(r,t,'INSERT')<>ins
     or has_table_privilege(r,t,'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
     or has_any_column_privilege(a,t,'SELECT,INSERT,UPDATE,REFERENCES') then raise exception 'unsafe Identity rights';end if;
   for col in select column_name from information_schema.columns where table_schema=current_schema() and table_name=t loop
    upd:=(t='v3_identity_request' and col.column_name='status') or (t='v3_eui_authority' and col.column_name='current_version');
    if has_column_privilege(r,t,col.column_name,'INSERT')<>ins or has_column_privilege(r,t,col.column_name,'UPDATE')<>upd
       or has_column_privilege(r,t,col.column_name,'REFERENCES') then raise exception 'unsafe Identity column rights';end if;
   end loop;
  end loop;
 end if;
end $$;
do $$
declare reader text:=current_setting('pocoma.identity_read_role');t text;
begin
 if current_setting('pocoma.identity_authority')='real' and to_regclass('v3_command') is not null then
  if reader='' or reader=current_setting('pocoma.runtime_role') or not exists(select 1 from pg_roles where rolname=reader)
  then raise exception 'distinct explicit Identity READ principal required' using errcode='42501';end if;
  foreach t in array array['v3_identity_request','v3_eui_authority','v3_pocoma_user','v3_eui_version',
       'v3_identity_request_result','v3_identity_event','v3_identity_completion_event'] loop
   if has_any_column_privilege(reader,t,'SELECT,INSERT,UPDATE,REFERENCES') or has_table_privilege(reader,t,'DELETE,TRUNCATE,TRIGGER')
   then raise exception 'READ principal has Identity WRITE authority privileges' using errcode='42501';end if;
  end loop;
 end if;
end $$;
commit;
