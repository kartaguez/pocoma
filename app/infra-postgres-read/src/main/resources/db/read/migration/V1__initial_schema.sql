-- Consolidated V3 READ initial baseline schema (replaces V1..V7, V10).
-- Administrative fresh target only. Mandatory Flyway placeholders, no defaults.
do $$
declare t record; occupied boolean;
begin
  if current_database() <> '${expectedDatabase}' or current_schema() <> '${expectedSchema}'
     or current_user <> '${expectedAdmin}' or current_schema() = 'public'
     or '${expectedRuntime}' = current_user then
    raise exception 'wrong L2 migration target' using errcode='23514';
  end if;
  for t in select tablename from pg_tables where schemaname=current_schema()
      and tablename <> 'flyway_schema_history' order by tablename loop
    execute format('lock table %I.%I in access exclusive mode', current_schema(),t.tablename);
    execute format('select exists(select 1 from %I.%I)', current_schema(),t.tablename) into occupied;
    if occupied then raise exception 'nonempty L2 migration target: %',t.tablename using errcode='23514'; end if;
  end loop;
  if to_regprocedure('public.digest(bytea,text)') is null then
    raise exception 'pgcrypto digest missing in public' using errcode='42883';
  end if;
  if not exists(select 1 from pg_namespace where nspname=current_schema()
        and nspowner=(select oid from pg_roles where rolname=current_user)) then
    raise exception 'wrong READ migration target owner' using errcode='23514';
  end if;
  if exists(select 1 from pg_roles where pg_has_role('${expectedRuntime}',oid,'MEMBER')
      and (rolsuper or rolcreaterole or rolcreatedb or rolreplication or rolbypassrls
        or has_schema_privilege(oid,current_schema(),'CREATE')))
     or pg_has_role('${expectedRuntime}',current_user,'MEMBER')
     or exists(select 1 from pg_roles where rolname<>'${expectedRuntime}'
        and pg_has_role('${expectedRuntime}',oid,'MEMBER'))
     or has_schema_privilege('${expectedRuntime}',current_schema(),'CREATE') then
    raise exception 'unsafe L2 runtime role' using errcode='42501';
  end if;
end $$;

-- ============================================================================
-- 1. Consumption foundation (V1)
-- ============================================================================
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

-- ============================================================================
-- 2. Business READ projections (V2..V7)
-- ============================================================================
create function read_subobject_immutable() returns trigger
language plpgsql security invoker set search_path=pg_catalog,pg_temp as $$
begin
  raise exception 'immutable READ subobject' using errcode='23514';
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
create trigger read_subobject_immutable before update or delete on read_subobject_version
  for each row execute function read_subobject_immutable();
create trigger read_subobject_no_truncate before truncate on read_subobject_version
  for each statement execute function read_subobject_immutable();
alter table read_subobject_version enable always trigger read_subobject_immutable;
alter table read_subobject_version enable always trigger read_subobject_no_truncate;

create table read_pot_version (
  pot_id uuid not null,
  pot_version bigint not null check(pot_version>0),
  schema_version integer not null check(schema_version=1),
  payload jsonb not null check(jsonb_typeof(payload)='object'),
  primary key(pot_id,pot_version)
);
create table read_current_pot (
  pot_id uuid primary key,
  pot_version bigint not null check(pot_version>0),
  foreign key(pot_id,pot_version) references read_pot_version(pot_id,pot_version)
);
create trigger read_pot_version_immutable before update or delete on read_pot_version
  for each row execute function read_subobject_immutable();
create trigger read_pot_version_no_truncate before truncate on read_pot_version
  for each statement execute function read_subobject_immutable();
alter table read_pot_version enable always trigger read_pot_version_immutable;
alter table read_pot_version enable always trigger read_pot_version_no_truncate;

create table read_pot_balance (
  pot_id uuid not null,
  pot_version bigint not null check(pot_version>0),
  schema_version integer not null check(schema_version=1),
  payload jsonb not null check(jsonb_typeof(payload)='array'),
  primary key(pot_id,pot_version)
);
create trigger read_pot_balance_immutable before update or delete on read_pot_balance
  for each row execute function read_subobject_immutable();
create trigger read_pot_balance_no_truncate before truncate on read_pot_balance
  for each statement execute function read_subobject_immutable();
alter table read_pot_balance enable always trigger read_pot_balance_immutable;
alter table read_pot_balance enable always trigger read_pot_balance_no_truncate;

create table read_pot_visibility_state (
  pot_id uuid primary key,
  materialized_version bigint not null check(materialized_version>=0)
);
create table read_user_pot (
  user_id uuid not null,
  pot_id uuid not null,
  pot_version bigint not null check(pot_version>0),
  visible boolean not null,
  primary key(user_id,pot_id)
);
create index read_visible_by_pot on read_user_pot(pot_id,user_id) where visible=true;
create index read_visible_by_user on read_user_pot(user_id,pot_id) where visible=true;

create table read_current_binding (
  issuer text collate "C" not null check(length(issuer)>0),
  subject text collate "C" not null check(length(subject)>0),
  eui_id uuid not null unique,
  eui_version bigint not null check(eui_version>0),
  user_id uuid,
  binding_token text check(binding_token is null or length(btrim(binding_token))>0),
  primary key(issuer,subject),
  check((user_id is null)=(binding_token is null))
);

create table read_command_result (
  request_id uuid primary key,
  operation text not null check(length(operation)>0),
  requester_issuer text collate "C" not null check(length(requester_issuer)>0),
  requester_subject text collate "C" not null check(length(requester_subject)>0),
  pot_id uuid not null,
  write_outcome text not null constraint read_command_result_write_outcome_check
    check(write_outcome in ('CHANGED','UNCHANGED','REJECTED','INVITATION_CREATED')),
  resulting_pot_version bigint,
  rejection text,
  constraint read_command_result_shape check(
    (operation='CreateInvitation' and write_outcome='INVITATION_CREATED' and resulting_pot_version is null and rejection is null)
    or (operation<>'CreateInvitation' and write_outcome in ('CHANGED','UNCHANGED') and resulting_pot_version>0 and resulting_pot_version is not null and rejection is null)
    or (write_outcome='REJECTED' and resulting_pot_version is null and rejection is not null and length(rejection)>0))
);
create table read_identity_request_result (
  request_id uuid primary key,
  operation text not null check(operation in ('REGISTRATION','LINK','UNLINK')),
  requester_issuer text collate "C" not null check(length(requester_issuer)>0),
  requester_subject text collate "C" not null check(length(requester_subject)>0),
  write_outcome text not null check(write_outcome in ('CHANGED','REJECTED')),
  target_issuer text collate "C",
  target_subject text collate "C",
  target_version bigint,
  rejection text,
  check((write_outcome='CHANGED' and target_issuer is not null and length(target_issuer)>0 and target_subject is not null and length(target_subject)>0 and target_version is not null and target_version>0 and rejection is null)
     or (write_outcome='REJECTED' and target_issuer is null and target_subject is null and target_version is null and rejection is not null and length(rejection)>0)),
  check(operation<>'REGISTRATION' or write_outcome<>'CHANGED' or (target_issuer=requester_issuer and target_subject=requester_subject))
);
create trigger read_command_result_immutable before update or delete on read_command_result for each row execute function read_subobject_immutable();
create trigger read_command_result_no_truncate before truncate on read_command_result for each statement execute function read_subobject_immutable();
create trigger read_identity_result_immutable before update or delete on read_identity_request_result for each row execute function read_subobject_immutable();
create trigger read_identity_result_no_truncate before truncate on read_identity_request_result for each statement execute function read_subobject_immutable();
alter table read_command_result enable always trigger read_command_result_immutable;
alter table read_command_result enable always trigger read_command_result_no_truncate;
alter table read_identity_request_result enable always trigger read_identity_result_immutable;
alter table read_identity_request_result enable always trigger read_identity_result_no_truncate;

-- ============================================================================
-- 3. Current invitation projection (V10)
-- ============================================================================
create table read_current_invitation (
  invitation_id uuid primary key,
  version bigint not null check(version>0),
  creation_request_id uuid not null unique,
  creator_issuer text collate "C" not null,
  creator_subject text collate "C" not null,
  pot_id uuid not null,
  expires_at timestamptz not null,
  token_hash text not null unique check(token_hash ~ '^[0-9a-f]{64}$'),
  protected_link text not null check(protected_link like 'v1.%')
);
create function read_invitation_monotone() returns trigger language plpgsql as $$
begin
 if tg_op<>'UPDATE' or new.version<=old.version
    or (to_jsonb(new)-array['version','expires_at','protected_link']) is distinct from (to_jsonb(old)-array['version','expires_at','protected_link']) then
  raise exception 'Invalid Invitation projection change' using errcode='23514';
 end if;
 return new;
end $$;
create trigger read_invitation_monotone before update or delete on read_current_invitation for each row execute function read_invitation_monotone();
create trigger read_invitation_no_truncate before truncate on read_current_invitation for each statement execute function read_subobject_immutable();

-- ============================================================================
-- 4. Permissions & final privilege verification (V1..V7, V10)
-- ============================================================================
revoke all on consumption_routing_contract, partition_ownership, discovery_checkpoint, consumption_slot from public, "${expectedRuntime}";
revoke create on schema "${expectedSchema}" from public, "${expectedRuntime}";
grant usage on schema "${expectedSchema}" to "${expectedRuntime}";
grant select on consumption_routing_contract, partition_ownership, discovery_checkpoint, consumption_slot to "${expectedRuntime}";

revoke all on function read_subobject_immutable() from public, "${expectedRuntime}";
revoke all on read_subobject_version from public, "${expectedRuntime}";
grant select,insert on read_subobject_version to "${expectedRuntime}";

revoke all on read_pot_version, read_current_pot from public, "${expectedRuntime}";
grant select,insert on read_pot_version, read_current_pot to "${expectedRuntime}";
grant update(pot_version) on read_current_pot to "${expectedRuntime}";

revoke all on read_pot_balance from public, "${expectedRuntime}";
grant select,insert on read_pot_balance to "${expectedRuntime}";

revoke all on read_pot_visibility_state, read_user_pot from public, "${expectedRuntime}";
grant select,insert on read_pot_visibility_state, read_user_pot to "${expectedRuntime}";
grant update(materialized_version) on read_pot_visibility_state to "${expectedRuntime}";
grant update(pot_version,visible) on read_user_pot to "${expectedRuntime}";

revoke all on read_current_binding from public, "${expectedRuntime}";
grant select,insert on read_current_binding to "${expectedRuntime}";
grant update(eui_version,user_id,binding_token) on read_current_binding to "${expectedRuntime}";

revoke all on read_command_result, read_identity_request_result from public, "${expectedRuntime}";
grant select,insert on read_command_result, read_identity_request_result to "${expectedRuntime}";

revoke all on read_current_invitation from public, "${expectedRuntime}";
grant select,insert on read_current_invitation to "${expectedRuntime}";
grant update(version,expires_at,protected_link) on read_current_invitation to "${expectedRuntime}";

do $$
declare t text;
begin
  foreach t in array array['consumption_routing_contract','partition_ownership','discovery_checkpoint','consumption_slot'] loop
    if exists(select 1 from pg_roles where pg_has_role('${expectedRuntime}',oid,'MEMBER')
        and has_table_privilege(oid,t,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')) then
      raise exception 'inherited L2 runtime write privilege: %',t using errcode='42501';
    end if;
  end loop;
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
