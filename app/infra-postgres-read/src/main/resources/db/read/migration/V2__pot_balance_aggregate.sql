-- Additive V3 READ migration introducing immutable weight-independent PotBalance aggregates (L7.4-A4.2).
-- Mandatory Flyway placeholders, no defaults. Safe on both fresh and populated databases.
do $$
begin
  if current_database() <> '${expectedDatabase}' or current_schema() <> '${expectedSchema}'
     or current_user <> '${expectedAdmin}' or current_schema() = 'public'
     or '${expectedRuntime}' = current_user then
    raise exception 'wrong L2 migration target' using errcode='23514';
  end if;
  if not exists(select 1 from pg_namespace where nspname=current_schema()
        and nspowner=(select oid from pg_roles where rolname=current_user)) then
    raise exception 'wrong READ migration target owner' using errcode='23514';
  end if;
  if to_regclass('read_pot_balance') is null then
    raise exception 'missing prerequisite table read_pot_balance' using errcode='42P01';
  end if;
  if to_regprocedure('read_subobject_immutable()') is null then
    raise exception 'missing prerequisite function read_subobject_immutable' using errcode='42883';
  end if;
end $$;

create table read_pot_balance_aggregate (
  pot_id uuid not null,
  pot_version bigint not null check (pot_version > 0),
  schema_version integer not null check (schema_version = 1),
  bucket_count integer not null check (bucket_count >= 0),
  payload jsonb not null check (jsonb_typeof(payload) = 'object'),
  primary key (pot_id, pot_version),
  foreign key (pot_id, pot_version) references read_pot_balance(pot_id, pot_version)
);

create trigger read_pot_balance_aggregate_immutable
  before update or delete on read_pot_balance_aggregate
  for each row execute function read_subobject_immutable();
create trigger read_pot_balance_aggregate_no_truncate
  before truncate on read_pot_balance_aggregate
  for each statement execute function read_subobject_immutable();
alter table read_pot_balance_aggregate enable always trigger read_pot_balance_aggregate_immutable;
alter table read_pot_balance_aggregate enable always trigger read_pot_balance_aggregate_no_truncate;

revoke all on read_pot_balance_aggregate from public, "${expectedRuntime}";
grant select, insert on read_pot_balance_aggregate to "${expectedRuntime}";

do $$
begin
  if has_table_privilege('${expectedRuntime}','read_pot_balance_aggregate','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
     or has_any_column_privilege('${expectedRuntime}','read_pot_balance_aggregate','UPDATE,REFERENCES')
     or exists(select 1 from pg_class c, lateral aclexplode(c.relacl) a
        where c.oid='read_pot_balance_aggregate'::regclass and a.grantee=0)
     or exists(select 1 from pg_attribute c, lateral aclexplode(c.attacl) a
        where c.attrelid='read_pot_balance_aggregate'::regclass and a.grantee=0) then
    raise exception 'unsafe READ pot balance aggregate effective privileges' using errcode='42501';
  end if;
end $$;
