-- Additive extension of the V3 READ Flyway history. Never installs/replays L2 V1.
do $$
begin
  if current_database() <> '${expectedDatabase}' or current_schema() <> '${expectedSchema}'
     or current_user <> '${expectedAdmin}' or current_schema() = 'public'
     or '${expectedRuntime}' = current_user
     or to_regclass('consumption_slot') is null
     or not exists(select 1 from pg_namespace where nspname=current_schema()
          and nspowner=(select oid from pg_roles where rolname=current_user)) then
    raise exception 'wrong READ subobject migration target' using errcode='23514';
  end if;
  if not exists(select 1 from pg_roles where rolname='${expectedRuntime}'
        and not (rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
     or exists(select 1 from pg_roles where rolname<>'${expectedRuntime}'
        and pg_has_role('${expectedRuntime}',oid,'MEMBER'))
     or has_schema_privilege('${expectedRuntime}',current_schema(),'CREATE') then
    raise exception 'unsafe READ subobject runtime role' using errcode='42501';
  end if;
end $$;

create table read_subobject_version (
  pot_id uuid not null,
  kind text not null,
  object_id uuid not null,
  local_version bigint not null check (local_version > 0),
  schema_version integer not null check (schema_version = 1),
  payload jsonb not null,
  primary key (pot_id,kind,object_id,local_version),
  check (kind in ('POT_HEADER','POT_DETAILS','SHAREHOLDER_HEADER','SHAREHOLDER_DETAILS',
      'SHAREHOLDER_WEIGHT','EXPENSE_HEADER','EXPENSE_DETAILS','EXPENSE_FINANCIALS')),
  check (kind not in ('POT_HEADER','POT_DETAILS') or object_id=pot_id),
  check ((jsonb_typeof(payload)='object') is true),
  constraint read_subobject_payload_shape check ((case kind
    when 'POT_HEADER' then payload ?& array['deleted']
      and payload-array['deleted']='{}'::jsonb and jsonb_typeof(payload->'deleted')='boolean'
    when 'SHAREHOLDER_HEADER' then payload ?& array['deleted']
      and payload-array['deleted']='{}'::jsonb and jsonb_typeof(payload->'deleted')='boolean'
    when 'EXPENSE_HEADER' then payload ?& array['deleted']
      and payload-array['deleted']='{}'::jsonb and jsonb_typeof(payload->'deleted')='boolean'
    when 'POT_DETAILS' then payload ?& array['label','creatorId']
      and payload-array['label','creatorId']='{}'::jsonb
      and jsonb_typeof(payload->'label')='string' and jsonb_typeof(payload->'creatorId')='string'
    when 'SHAREHOLDER_DETAILS' then payload ?& array['name','userId']
      and payload-array['name','userId']='{}'::jsonb
      and jsonb_typeof(payload->'name')='string' and jsonb_typeof(payload->'userId') in ('string','null')
    when 'SHAREHOLDER_WEIGHT' then payload ?& array['defaultWeight']
      and payload-array['defaultWeight']='{}'::jsonb and jsonb_typeof(payload->'defaultWeight')='object'
    when 'EXPENSE_DETAILS' then payload ?& array['label','date']
      and payload-array['label','date']='{}'::jsonb
      and jsonb_typeof(payload->'label')='string' and jsonb_typeof(payload->'date')='string'
    when 'EXPENSE_FINANCIALS' then payload ?& array['amount','payerId','participants']
      and payload-array['amount','payerId','participants']='{}'::jsonb
      and jsonb_typeof(payload->'amount')='object' and jsonb_typeof(payload->'payerId')='string'
      and case when jsonb_typeof(payload->'participants')='array'
        then jsonb_array_length(payload->'participants')>0 else false end
    else false end) is true)
);

create function read_subobject_immutable() returns trigger
language plpgsql security invoker set search_path=pg_catalog,pg_temp as $$
begin
  raise exception 'immutable READ subobject' using errcode='23514';
end $$;
create trigger read_subobject_immutable before update or delete on read_subobject_version
  for each row execute function read_subobject_immutable();
create trigger read_subobject_no_truncate before truncate on read_subobject_version
  for each statement execute function read_subobject_immutable();
alter table read_subobject_version enable always trigger read_subobject_immutable;
alter table read_subobject_version enable always trigger read_subobject_no_truncate;
revoke all on function read_subobject_immutable() from public, "${expectedRuntime}";
revoke all on read_subobject_version from public, "${expectedRuntime}";
grant select,insert on read_subobject_version to "${expectedRuntime}";

do $$
begin
  if has_table_privilege('${expectedRuntime}','read_subobject_version','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
     or has_any_column_privilege('${expectedRuntime}','read_subobject_version','UPDATE,REFERENCES')
     or exists(select 1 from pg_class c, lateral aclexplode(c.relacl) a
        where c.oid='read_subobject_version'::regclass and a.grantee=0)
     or exists(select 1 from pg_attribute c, lateral aclexplode(c.attacl) a
        where c.attrelid='read_subobject_version'::regclass and a.grantee=0)
     or exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
        where n.nspname not in ('pg_catalog','information_schema') and p.prosecdef
          and has_schema_privilege('${expectedRuntime}',n.oid,'USAGE')
          and has_function_privilege('${expectedRuntime}',p.oid,'EXECUTE')) then
    raise exception 'unsafe READ subobject effective privileges' using errcode='42501';
  end if;
end $$;
