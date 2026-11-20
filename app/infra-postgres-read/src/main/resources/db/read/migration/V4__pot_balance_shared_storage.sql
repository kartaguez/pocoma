-- V4 READ migration introducing immutable content-addressed PotBalance, PotBalanceAggregate,
-- and shared PotShareholderSet storage (L7-C2-B2, ARB-1b).
-- Mandatory Flyway placeholders, no defaults. Requires empty read_pot_balance and read_pot_balance_aggregate.
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
  if to_regclass('read_pot_balance') is null or to_regclass('read_pot_balance_aggregate') is null then
    raise exception 'missing prerequisite PotBalance tables' using errcode='42P01';
  end if;
  if to_regprocedure('read_subobject_immutable()') is null then
    raise exception 'missing prerequisite function read_subobject_immutable' using errcode='42883';
  end if;
  if exists(select 1 from read_pot_balance) or exists(select 1 from read_pot_balance_aggregate) then
    raise exception 'V4 PotBalance shared storage migration requires empty read_pot_balance and read_pot_balance_aggregate' using errcode='23514';
  end if;
end $$;

create table read_pot_shareholder_set (
  set_hash           bytea   primary key check (octet_length(set_hash) = 32),
  schema_version     integer not null    check (schema_version = 1),
  shareholders_count integer not null    check (shareholders_count >= 0),
  payload            bytea   not null    check (octet_length(payload) = 4 + shareholders_count * 16)
);

create table read_pot_balance_content (
  content_hash         bytea   primary key check (octet_length(content_hash) = 32),
  schema_version       integer not null    check (schema_version = 1),
  shareholders_count   integer not null    check (shareholders_count >= 0),
  shareholder_set_hash bytea   not null    references read_pot_shareholder_set(set_hash),
  payload              bytea   not null    check (octet_length(payload) >= 4)
);

create table read_pot_balance_aggregate_content (
  content_hash         bytea   primary key check (octet_length(content_hash) = 32),
  schema_version       integer not null    check (schema_version = 1),
  shareholders_count   integer not null    check (shareholders_count >= 0),
  bucket_count         integer not null    check (bucket_count >= 0),
  shareholder_set_hash bytea   not null    references read_pot_shareholder_set(set_hash),
  payload              bytea   not null    check (octet_length(payload) >= 8)
);

drop table read_pot_balance_aggregate;
drop table read_pot_balance;

create table read_pot_balance (
  pot_id       uuid   not null,
  pot_version  bigint not null check (pot_version > 0),
  content_hash bytea  not null references read_pot_balance_content(content_hash),
  primary key (pot_id, pot_version)
);

create table read_pot_balance_aggregate (
  pot_id       uuid   not null,
  pot_version  bigint not null check (pot_version > 0),
  content_hash bytea  not null references read_pot_balance_aggregate_content(content_hash),
  primary key (pot_id, pot_version),
  foreign key (pot_id, pot_version) references read_pot_balance(pot_id, pot_version)
);

-- ============================================================================
-- Effective immutability triggers (ENABLE ALWAYS)
-- ============================================================================
create trigger read_pot_shareholder_set_immutable
  before update or delete on read_pot_shareholder_set
  for each row execute function read_subobject_immutable();
create trigger read_pot_shareholder_set_no_truncate
  before truncate on read_pot_shareholder_set
  for each statement execute function read_subobject_immutable();
alter table read_pot_shareholder_set enable always trigger read_pot_shareholder_set_immutable;
alter table read_pot_shareholder_set enable always trigger read_pot_shareholder_set_no_truncate;

create trigger read_pot_balance_content_immutable
  before update or delete on read_pot_balance_content
  for each row execute function read_subobject_immutable();
create trigger read_pot_balance_content_no_truncate
  before truncate on read_pot_balance_content
  for each statement execute function read_subobject_immutable();
alter table read_pot_balance_content enable always trigger read_pot_balance_content_immutable;
alter table read_pot_balance_content enable always trigger read_pot_balance_content_no_truncate;

create trigger read_pot_balance_aggregate_content_immutable
  before update or delete on read_pot_balance_aggregate_content
  for each row execute function read_subobject_immutable();
create trigger read_pot_balance_aggregate_content_no_truncate
  before truncate on read_pot_balance_aggregate_content
  for each statement execute function read_subobject_immutable();
alter table read_pot_balance_aggregate_content enable always trigger read_pot_balance_aggregate_content_immutable;
alter table read_pot_balance_aggregate_content enable always trigger read_pot_balance_aggregate_content_no_truncate;

create trigger read_pot_balance_immutable
  before update or delete on read_pot_balance
  for each row execute function read_subobject_immutable();
create trigger read_pot_balance_no_truncate
  before truncate on read_pot_balance
  for each statement execute function read_subobject_immutable();
alter table read_pot_balance enable always trigger read_pot_balance_immutable;
alter table read_pot_balance enable always trigger read_pot_balance_no_truncate;

create trigger read_pot_balance_aggregate_immutable
  before update or delete on read_pot_balance_aggregate
  for each row execute function read_subobject_immutable();
create trigger read_pot_balance_aggregate_no_truncate
  before truncate on read_pot_balance_aggregate
  for each statement execute function read_subobject_immutable();
alter table read_pot_balance_aggregate enable always trigger read_pot_balance_aggregate_immutable;
alter table read_pot_balance_aggregate enable always trigger read_pot_balance_aggregate_no_truncate;

-- ============================================================================
-- Minimal runtime privileges & ACL assertions
-- ============================================================================
revoke all on read_pot_shareholder_set, read_pot_balance_content, read_pot_balance_aggregate_content, read_pot_balance, read_pot_balance_aggregate
  from public, "${expectedRuntime}";
grant select, insert on read_pot_shareholder_set, read_pot_balance_content, read_pot_balance_aggregate_content, read_pot_balance, read_pot_balance_aggregate
  to "${expectedRuntime}";

do $$
declare
  v_table text;
begin
  foreach v_table in array array[
    'read_pot_shareholder_set',
    'read_pot_balance_content',
    'read_pot_balance_aggregate_content',
    'read_pot_balance',
    'read_pot_balance_aggregate'
  ] loop
    if has_table_privilege('${expectedRuntime}', v_table, 'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
       or has_any_column_privilege('${expectedRuntime}', v_table, 'UPDATE,REFERENCES')
       or exists(select 1 from pg_class c, lateral aclexplode(c.relacl) a
          where c.oid = v_table::regclass and a.grantee = 0)
       or exists(select 1 from pg_attribute c, lateral aclexplode(c.attacl) a
          where c.attrelid = v_table::regclass and a.grantee = 0) then
      raise exception 'unsafe READ PotBalance shared storage privileges on %', v_table using errcode = '42501';
    end if;
  end loop;
end $$;
