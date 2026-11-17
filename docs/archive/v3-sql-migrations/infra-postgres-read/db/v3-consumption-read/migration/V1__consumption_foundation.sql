-- Administrative fresh V3 target only. Mandatory Flyway placeholders, no defaults.
do $$
declare t record; occupied boolean;
begin
  if current_database() <> '${expectedDatabase}' or current_schema() <> '${expectedSchema}'
     or current_user <> '${expectedAdmin}' or current_schema() = 'public'
     or '${expectedRuntime}' = current_user then
    raise exception 'wrong L2 migration target' using errcode='23514';
  end if;
  for t in select tablename from pg_tables where schemaname=current_schema()
      and tablename not in ('flyway_schema_history','v3_event_counter') order by tablename loop
    execute format('lock table %I.%I in access exclusive mode', current_schema(),t.tablename);
    execute format('select exists(select 1 from %I.%I)', current_schema(),t.tablename) into occupied;
    if occupied then raise exception 'nonempty L2 migration target: %',t.tablename using errcode='23514'; end if;
  end loop;
  if to_regclass('v3_event_counter') is not null then
    execute 'select exists(select 1 from v3_event_counter where position<>0)' into occupied;
    if occupied then raise exception 'nonempty event counter' using errcode='23514'; end if;
  end if;
end $$;

do $$
begin
  if to_regprocedure('public.digest(bytea,text)') is null then
    raise exception 'pgcrypto digest missing in public' using errcode='42883';
  end if;
end $$;
create table consumption_routing_contract (
  consumption_id uuid primary key,
  family text not null check (family in ('COMMAND','IDENTITY_REQUEST','POT_EVENT',
    'INVITATION_EVENT','IDENTITY_EVENT','COMMAND_COMPLETION_EVENT',
    'IDENTITY_COMPLETION_EVENT')),
  routing_version text not null check (routing_version = 'sha256-v1'),
  partition_count integer not null check (partition_count = 4)
);
create function consumption_contract_immutable() returns trigger language plpgsql as $$
begin
  raise exception 'immutable routing contract' using errcode='23514';
end $$;
create trigger consumption_contract_immutable before update or delete
  on consumption_routing_contract for each row execute function consumption_contract_immutable();

create function consumption_hash_v1(f text, id uuid) returns integer
language sql immutable strict as $$
  with bytes as (
    select public.digest(convert_to('pocoma-consumable-v1','UTF8') || decode('00','hex')
      || convert_to(f,'UTF8') || decode('00','hex') || uuid_send(id),'sha256') as d
  ), unsigned as (
    select get_byte(d,0)::bigint*16777216 + get_byte(d,1)::bigint*65536
      + get_byte(d,2)::bigint*256 + get_byte(d,3)::bigint as u from bytes
  )
  select (case when u >= 2147483648 then u-4294967296 else u end)::integer
  from unsigned
$$;

create table partition_ownership (
  consumption_id uuid not null references consumption_routing_contract(consumption_id),
  partition_id integer not null check (partition_id between 0 and 3),
  owner_token uuid, owned_until timestamptz,
  primary key (consumption_id,partition_id),
  check ((owner_token is null) = (owned_until is null))
);
create table discovery_checkpoint (
  consumption_id uuid not null references consumption_routing_contract(consumption_id),
  partition_id integer not null check (partition_id between 0 and 3),
  position bigint not null check (position >= 0),
  primary key (consumption_id,partition_id)
);
create table consumption_slot (
  consumption_id uuid not null references consumption_routing_contract(consumption_id),
  consumable_id uuid not null,
  partition_hash integer not null,
  partition_id integer not null check (partition_id between 0 and 3),
  claim_token uuid, claimed_until timestamptz, retry_after timestamptz,
  attempt_count integer not null default 0 check (attempt_count >= 0),
  state text not null default 'AVAILABLE'
    check (state in ('AVAILABLE','QUARANTINED')),
  primary key (consumption_id,consumable_id),
  check ((claim_token is null) = (claimed_until is null)),
  check (state <> 'QUARANTINED' or
    (claim_token is null and claimed_until is null and retry_after is null))
);
create index consumption_slot_partition_walk_idx
  on consumption_slot(consumption_id,partition_id,consumable_id);
create index consumption_slot_live_count_idx
  on consumption_slot(consumption_id,partition_id) where state <> 'QUARANTINED';
create index consumption_slot_available_due_idx
  on consumption_slot(consumption_id,partition_id,retry_after,claimed_until)
  where state = 'AVAILABLE';

create function consumption_row_guard() returns trigger language plpgsql as $$
declare f text;
begin
  select family into f from consumption_routing_contract
    where consumption_id = new.consumption_id;
  if not found then raise exception 'missing route' using errcode='23503'; end if;
  if tg_table_name = 'consumption_slot' then
    if new.partition_hash <> consumption_hash_v1(f,new.consumable_id)
       or new.partition_id <> ((new.partition_hash::bigint % 4 + 4) % 4) then
      raise exception 'slot route mismatch' using errcode='23514';
    end if;
    if tg_op = 'UPDATE' and
       (new.consumption_id,new.consumable_id,new.partition_hash,new.partition_id)
       is distinct from
       (old.consumption_id,old.consumable_id,old.partition_hash,old.partition_id) then
      raise exception 'slot key immutable' using errcode='23514';
    end if;
  elsif tg_table_name = 'discovery_checkpoint' then
    if f in ('COMMAND','IDENTITY_REQUEST') then
      raise exception 'burnable checkpoint forbidden' using errcode='23514';
    end if;
    if tg_op = 'UPDATE' and
       ((new.consumption_id,new.partition_id) is distinct from
        (old.consumption_id,old.partition_id) or new.position < old.position) then
      raise exception 'checkpoint regression' using errcode='23514';
    end if;
  elsif tg_op = 'UPDATE' and
    (new.consumption_id,new.partition_id) is distinct from
    (old.consumption_id,old.partition_id) then
    raise exception 'ownership key immutable' using errcode='23514';
  end if;
  return new;
end $$;
create trigger consumption_slot_guard before insert or update on consumption_slot
  for each row execute function consumption_row_guard();
create trigger discovery_checkpoint_guard before insert or update on discovery_checkpoint
  for each row execute function consumption_row_guard();
create trigger partition_ownership_guard before insert or update on partition_ownership
  for each row execute function consumption_row_guard();

-- I1 is inert. Runtime has SELECT only, including effective inherited privileges.
revoke all on consumption_routing_contract, partition_ownership, discovery_checkpoint, consumption_slot from public;
revoke all on consumption_routing_contract, partition_ownership, discovery_checkpoint, consumption_slot from "${expectedRuntime}";
revoke create on schema "${expectedSchema}" from public, "${expectedRuntime}";
grant usage on schema "${expectedSchema}" to "${expectedRuntime}";
grant select on consumption_routing_contract, partition_ownership, discovery_checkpoint, consumption_slot to "${expectedRuntime}";
do $$
declare t text;
begin
  if exists(select 1 from pg_roles where pg_has_role('${expectedRuntime}',oid,'MEMBER')
      and (rolsuper or rolcreaterole or rolcreatedb or rolreplication or rolbypassrls
        or has_schema_privilege(oid,current_schema(),'CREATE')))
     or pg_has_role('${expectedRuntime}',current_user,'MEMBER')
     or has_schema_privilege('${expectedRuntime}',current_schema(),'CREATE') then
    raise exception 'unsafe L2 runtime role' using errcode='42501';
  end if;
  foreach t in array array['consumption_routing_contract','partition_ownership','discovery_checkpoint','consumption_slot'] loop
    if exists(select 1 from pg_roles where pg_has_role('${expectedRuntime}',oid,'MEMBER')
        and has_table_privilege(oid,t,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')) then
      raise exception 'inherited L2 runtime write privilege: %',t using errcode='42501';
    end if;
  end loop;
end $$;
