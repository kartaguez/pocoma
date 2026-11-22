-- V3 READ migration introducing immutable content-addressed Radix-16 PotVersion storage (L7-C2-B1).
-- Mandatory Flyway placeholders, no defaults. Requires empty read_pot_version / read_current_pot.
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
  if to_regclass('read_pot_version') is null or to_regclass('read_current_pot') is null then
    raise exception 'missing prerequisite PotVersion tables' using errcode='42P01';
  end if;
  if to_regprocedure('read_subobject_immutable()') is null then
    raise exception 'missing prerequisite function read_subobject_immutable' using errcode='42883';
  end if;
  if exists(select 1 from read_pot_version) or exists(select 1 from read_current_pot) then
    raise exception 'V3 Radix-16 migration requires empty read_pot_version and read_current_pot' using errcode='23514';
  end if;
end $$;

create table read_pot_user_set (
  set_hash       bytea   primary key check (octet_length(set_hash) = 32),
  schema_version integer not null    check (schema_version = 1),
  user_count     integer not null    check (user_count > 0),
  user_ids       uuid[]  not null    check (array_ndims(user_ids) = 1
                                        and array_length(user_ids, 1) = user_count)
);

create table read_pot_version_node (
  node_hash      bytea   primary key check (octet_length(node_hash) = 32),
  schema_version integer not null    check (schema_version = 1),
  node_kind      char(1) not null    check (node_kind in ('I', 'L')),
  tree_kind      char(1) not null    check (tree_kind in ('S', 'E')),
  entry_count    integer not null    check (entry_count > 0),
  prefix         text    not null    default '' check (prefix ~ '^[0-9a-f]{0,31}$'),
  child_hashes   bytea[] null,
  payload        bytea   not null,
  check (
    (node_kind = 'L'
      and prefix = ''
      and child_hashes is null
      and entry_count between 1 and 64
      and octet_length(payload) = entry_count * 41)
    or
    (node_kind = 'I'
      and child_hashes is not null
      and array_ndims(child_hashes) = 1
      and array_length(child_hashes, 1) between 2 and 16
      and entry_count > 64
      and octet_length(payload) = 2 + array_length(child_hashes, 1) * 4)
  )
);

drop table read_current_pot;
drop table read_pot_version;

create table read_pot_version (
  pot_id                     uuid    not null,
  pot_version                bigint  not null check (pot_version > 0),
  schema_version             integer not null check (schema_version = 1),
  header_version             bigint  not null check (header_version > 0),
  details_version            bigint  not null check (details_version > 0),
  deleted                    boolean not null,
  shareholders_count         integer not null check (shareholders_count >= 0),
  expenses_count             integer not null check (expenses_count >= 0),
  shareholders_root          bytea   null references read_pot_version_node(node_hash),
  expenses_root              bytea   null references read_pot_version_node(node_hash),
  authorized_users_hash      bytea   null references read_pot_user_set(set_hash),
  attached_active_users_hash bytea   null references read_pot_user_set(set_hash),
  primary key (pot_id, pot_version),
  check ((shareholders_count = 0) = (shareholders_root is null)),
  check ((expenses_count = 0) = (expenses_root is null)),
  check (attached_active_users_hash is null or authorized_users_hash is not null)
);

create table read_current_pot (
  pot_id      uuid   primary key,
  pot_version bigint not null check (pot_version > 0),
  foreign key (pot_id, pot_version) references read_pot_version(pot_id, pot_version)
);

-- ============================================================================
-- Structural integrity, acyclicity, and root-consistency triggers (F2)
-- ============================================================================
create function read_pot_version_node_guard() returns trigger
  language plpgsql
  security invoker
  set search_path = pg_catalog, pg_temp
as $$
declare
  v_len      integer;
  v_distinct integer;
  v_found    integer;
  v_sum      bigint;
begin
  if new.node_kind = 'I' then
    v_len := array_length(new.child_hashes, 1);

    if exists (
      select 1
      from unnest(new.child_hashes) as ch(h)
      where ch.h is null
         or octet_length(ch.h) <> 32
         or ch.h = new.node_hash
    ) then
      raise exception 'Invalid or self-referential child hash in read_pot_version_node' using errcode = '23514';
    end if;

    select count(distinct ch.h)
    into v_distinct
    from unnest(new.child_hashes) as ch(h);

    if v_distinct <> v_len then
      raise exception 'Duplicate child hash in read_pot_version_node' using errcode = '23514';
    end if;

    select count(*), coalesce(sum(c.entry_count), 0)
    into v_found, v_sum
    from (select distinct h from unnest(new.child_hashes) as ch(h)) d
    join "${expectedSchema}".read_pot_version_node c
      on c.node_hash = d.h and c.tree_kind = new.tree_kind;

    if v_found <> v_len then
      raise exception 'Missing or cross-tree child node referenced by read_pot_version_node' using errcode = '23503';
    end if;

    if v_sum <> new.entry_count then
      raise exception 'Internal node entry_count (%) does not match sum of children (%)',
        new.entry_count, v_sum using errcode = '23514';
    end if;
  end if;

  return new;
end $$;

create function read_pot_version_root_guard() returns trigger
  language plpgsql
  security invoker
  set search_path = pg_catalog, pg_temp
as $$
declare
  v_tree_kind   char(1);
  v_entry_count integer;
begin
  if new.shareholders_root is not null then
    select tree_kind, entry_count
    into v_tree_kind, v_entry_count
    from "${expectedSchema}".read_pot_version_node
    where node_hash = new.shareholders_root;

    if not found then
      raise exception 'Missing shareholders_root node in read_pot_version_node' using errcode = '23503';
    end if;
    if v_tree_kind <> 'S' or v_entry_count <> new.shareholders_count then
      raise exception 'Invalid shareholders_root tree_kind or entry_count' using errcode = '23514';
    end if;
  end if;

  if new.expenses_root is not null then
    select tree_kind, entry_count
    into v_tree_kind, v_entry_count
    from "${expectedSchema}".read_pot_version_node
    where node_hash = new.expenses_root;

    if not found then
      raise exception 'Missing expenses_root node in read_pot_version_node' using errcode = '23503';
    end if;
    if v_tree_kind <> 'E' or v_entry_count <> new.expenses_count then
      raise exception 'Invalid expenses_root tree_kind or entry_count' using errcode = '23514';
    end if;
  end if;

  return new;
end $$;

create trigger read_pot_version_node_guard_trg
  before insert on read_pot_version_node
  for each row execute function read_pot_version_node_guard();
alter table read_pot_version_node enable always trigger read_pot_version_node_guard_trg;

create trigger read_pot_version_root_guard_trg
  before insert on read_pot_version
  for each row execute function read_pot_version_root_guard();
alter table read_pot_version enable always trigger read_pot_version_root_guard_trg;

-- ============================================================================
-- Effective immutability triggers (ENABLE ALWAYS)
-- ============================================================================
create trigger read_pot_user_set_immutable
  before update or delete on read_pot_user_set
  for each row execute function read_subobject_immutable();
create trigger read_pot_user_set_no_truncate
  before truncate on read_pot_user_set
  for each statement execute function read_subobject_immutable();
alter table read_pot_user_set enable always trigger read_pot_user_set_immutable;
alter table read_pot_user_set enable always trigger read_pot_user_set_no_truncate;

create trigger read_pot_version_node_immutable
  before update or delete on read_pot_version_node
  for each row execute function read_subobject_immutable();
create trigger read_pot_version_node_no_truncate
  before truncate on read_pot_version_node
  for each statement execute function read_subobject_immutable();
alter table read_pot_version_node enable always trigger read_pot_version_node_immutable;
alter table read_pot_version_node enable always trigger read_pot_version_node_no_truncate;

create trigger read_pot_version_immutable
  before update or delete on read_pot_version
  for each row execute function read_subobject_immutable();
create trigger read_pot_version_no_truncate
  before truncate on read_pot_version
  for each statement execute function read_subobject_immutable();
alter table read_pot_version enable always trigger read_pot_version_immutable;
alter table read_pot_version enable always trigger read_pot_version_no_truncate;

-- ============================================================================
-- Minimal runtime privileges & ACL assertions
-- ============================================================================
revoke all on function read_pot_version_node_guard(), read_pot_version_root_guard() from public, "${expectedRuntime}";

revoke all on read_pot_user_set, read_pot_version_node, read_pot_version, read_current_pot from public, "${expectedRuntime}";
grant select, insert on read_pot_user_set, read_pot_version_node, read_pot_version, read_current_pot to "${expectedRuntime}";
grant update(pot_version) on read_current_pot to "${expectedRuntime}";

do $$
declare
  v_table text;
begin
  foreach v_table in array array['read_pot_user_set', 'read_pot_version_node', 'read_pot_version'] loop
    if has_table_privilege('${expectedRuntime}', v_table, 'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
       or has_any_column_privilege('${expectedRuntime}', v_table, 'UPDATE,REFERENCES')
       or exists(select 1 from pg_class c, lateral aclexplode(c.relacl) a
          where c.oid = v_table::regclass and a.grantee = 0)
       or exists(select 1 from pg_attribute c, lateral aclexplode(c.attacl) a
          where c.attrelid = v_table::regclass and a.grantee = 0) then
      raise exception 'unsafe READ Radix-16 table privileges on %', v_table using errcode = '42501';
    end if;
  end loop;

  if has_table_privilege('${expectedRuntime}', 'read_current_pot', 'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
     or has_column_privilege('${expectedRuntime}', 'read_current_pot', 'pot_id', 'UPDATE,REFERENCES')
     or not has_column_privilege('${expectedRuntime}', 'read_current_pot', 'pot_version', 'UPDATE')
     or exists(select 1 from pg_class c, lateral aclexplode(c.relacl) a
        where c.oid = 'read_current_pot'::regclass and a.grantee = 0)
     or exists(select 1 from pg_attribute c, lateral aclexplode(c.attacl) a
        where c.attrelid = 'read_current_pot'::regclass and a.grantee = 0) then
    raise exception 'unsafe READ read_current_pot effective privileges' using errcode = '42501';
  end if;
end $$;
