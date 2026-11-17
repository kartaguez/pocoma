-- Consolidated V3 WRITE initial baseline schema (replaces V1..V11).
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
  if not exists (select 1 from pg_roles where rolname = '${expectedRuntime}') then
    raise exception 'Missing expected runtime role' using errcode='23514';
  end if;
  if exists(select 1 from pg_roles where pg_has_role('${expectedRuntime}',oid,'MEMBER')
      and (rolsuper or rolcreaterole or rolcreatedb or rolreplication or rolbypassrls
        or has_schema_privilege(oid,current_schema(),'CREATE')))
     or pg_has_role('${expectedRuntime}',current_user,'MEMBER')
     or has_schema_privilege('${expectedRuntime}',current_schema(),'CREATE') then
    raise exception 'unsafe L2 runtime role' using errcode='42501';
  end if;
end $$;

-- ============================================================================
-- 1. Pot components & versioned state (V1, V2, V3, V4)
-- ============================================================================
create table v3_pot_identity (
    pot_id uuid primary key,
    current_version bigint not null default 0 check(current_version>=0)
);
create table v3_pot_version (
    pot_id uuid not null references v3_pot_identity(pot_id),
    version bigint not null check (version >= 1),
    recorded_at timestamptz not null,
    writer_xid xid8 not null default pg_current_xact_id(),
    primary key(pot_id,version)
);
create table v3_shareholder_identity (
    pot_id uuid not null references v3_pot_identity(pot_id),
    object_id uuid primary key,
    created_at bigint not null check (created_at >= 1),
    unique(pot_id,object_id),
    foreign key(pot_id,created_at) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create table v3_expense_identity (
    pot_id uuid not null references v3_pot_identity(pot_id),
    object_id uuid primary key,
    created_at bigint not null check (created_at >= 1),
    unique(pot_id,object_id),
    foreign key(pot_id,created_at) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create table v3_pot_header (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id),
    object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1),
    active_to bigint check(active_to > active_from),
    deleted boolean not null,
    unique(pot_id,object_id,local_version),
    unique(pot_id,revision_id),
    check (object_id=pot_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_pot_header_open on v3_pot_header(pot_id,object_id) where active_to is null;
create index v3_pot_header_at_version on v3_pot_header(pot_id,object_id,active_from,active_to);
create index v3_pot_header_interval on v3_pot_header using gist (int8range(active_from,active_to,'[)'));
create index v3_pot_header_effect on v3_pot_header(pot_id,active_from);

create table v3_pot_details (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id),
    object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1),
    active_to bigint check(active_to > active_from),
    label text not null,
    creator_id uuid not null,
    unique(pot_id,object_id,local_version),
    unique(pot_id,revision_id),
    check (object_id=pot_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_pot_details_open on v3_pot_details(pot_id,object_id) where active_to is null;
create index v3_pot_details_at_version on v3_pot_details(pot_id,object_id,active_from,active_to);
create index v3_pot_details_interval on v3_pot_details using gist (int8range(active_from,active_to,'[)'));
create index v3_pot_details_effect on v3_pot_details(pot_id,active_from);

create table v3_shareholder_header (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id),
    object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1),
    active_to bigint check(active_to > active_from),
    deleted boolean not null,
    unique(pot_id,object_id,local_version),
    unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_shareholder_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_shareholder_header_open on v3_shareholder_header(pot_id,object_id) where active_to is null;
create index v3_shareholder_header_at_version on v3_shareholder_header(pot_id,object_id,active_from,active_to);
create index v3_shareholder_header_interval on v3_shareholder_header using gist (int8range(active_from,active_to,'[)'));
create index v3_shareholder_header_effect on v3_shareholder_header(pot_id,active_from);

create table v3_shareholder_details (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id),
    object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1),
    active_to bigint check(active_to > active_from),
    name text not null,
    user_id uuid,
    unique(pot_id,object_id,local_version),
    unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_shareholder_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_shareholder_details_open on v3_shareholder_details(pot_id,object_id) where active_to is null;
create index v3_shareholder_details_at_version on v3_shareholder_details(pot_id,object_id,active_from,active_to);
create index v3_shareholder_details_interval on v3_shareholder_details using gist (int8range(active_from,active_to,'[)'));
create index v3_shareholder_details_effect on v3_shareholder_details(pot_id,active_from);

create table v3_shareholder_weight (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id),
    object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1),
    active_to bigint check(active_to > active_from),
    numerator text not null check(numerator ~ '^[1-9][0-9]*$'),
    denominator text not null check(denominator ~ '^[1-9][0-9]*$'),
    unique(pot_id,object_id,local_version),
    unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_shareholder_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_shareholder_weight_open on v3_shareholder_weight(pot_id,object_id) where active_to is null;
create index v3_shareholder_weight_at_version on v3_shareholder_weight(pot_id,object_id,active_from,active_to);
create index v3_shareholder_weight_interval on v3_shareholder_weight using gist (int8range(active_from,active_to,'[)'));
create index v3_shareholder_weight_effect on v3_shareholder_weight(pot_id,active_from);

create table v3_expense_header (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id),
    object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1),
    active_to bigint check(active_to > active_from),
    deleted boolean not null,
    unique(pot_id,object_id,local_version),
    unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_expense_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_expense_header_open on v3_expense_header(pot_id,object_id) where active_to is null;
create index v3_expense_header_at_version on v3_expense_header(pot_id,object_id,active_from,active_to);
create index v3_expense_header_interval on v3_expense_header using gist (int8range(active_from,active_to,'[)'));
create index v3_expense_header_effect on v3_expense_header(pot_id,active_from);

create table v3_expense_details (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id),
    object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1),
    active_to bigint check(active_to > active_from),
    label text not null,
    expense_date date not null,
    unique(pot_id,object_id,local_version),
    unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_expense_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_expense_details_open on v3_expense_details(pot_id,object_id) where active_to is null;
create index v3_expense_details_at_version on v3_expense_details(pot_id,object_id,active_from,active_to);
create index v3_expense_details_interval on v3_expense_details using gist (int8range(active_from,active_to,'[)'));
create index v3_expense_details_effect on v3_expense_details(pot_id,active_from);

create table v3_expense_financials (
    revision_id bigint generated always as identity primary key,
    pot_id uuid not null references v3_pot_identity(pot_id),
    object_id uuid not null,
    local_version bigint not null check(local_version >= 1),
    active_from bigint not null check(active_from >= 1),
    active_to bigint check(active_to > active_from),
    numerator text not null check(numerator ~ '^(0|[1-9][0-9]*)$'),
    denominator text not null check(denominator ~ '^[1-9][0-9]*$'),
    payer_id uuid not null,
    foreign key(pot_id,payer_id) references v3_shareholder_identity(pot_id,object_id),
    unique(pot_id,object_id,local_version),
    unique(pot_id,revision_id),
    foreign key(pot_id,object_id) references v3_expense_identity(pot_id,object_id),
    foreign key(pot_id,active_from) references v3_pot_version(pot_id,version) deferrable initially deferred,
    foreign key(pot_id,active_to) references v3_pot_version(pot_id,version) deferrable initially deferred
);
create unique index v3_expense_financials_open on v3_expense_financials(pot_id,object_id) where active_to is null;
create index v3_expense_financials_at_version on v3_expense_financials(pot_id,object_id,active_from,active_to);
create index v3_expense_financials_interval on v3_expense_financials using gist (int8range(active_from,active_to,'[)'));
create index v3_expense_financials_effect on v3_expense_financials(pot_id,active_from);
create index v3_financial_payer on v3_expense_financials(pot_id,payer_id,active_from,active_to);

create table v3_expense_participant (
    pot_id uuid not null,
    financial_revision_id bigint not null,
    shareholder_id uuid not null,
    numerator text,
    denominator text,
    primary key(financial_revision_id,shareholder_id),
    foreign key(pot_id,financial_revision_id) references v3_expense_financials(pot_id,revision_id),
    foreign key(pot_id,shareholder_id) references v3_shareholder_identity(pot_id,object_id),
    check ((numerator is null and denominator is null) or
           (numerator is not null and denominator is not null and numerator ~ '^[1-9][0-9]*$' and denominator ~ '^[1-9][0-9]*$'))
);
create index v3_participant_shareholder on v3_expense_participant(pot_id,shareholder_id,financial_revision_id);

create function v3_require_effective_version() returns trigger language plpgsql as $$
begin
    if not exists (
        select 1 from v3_pot_header where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_pot_details where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_shareholder_header where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_shareholder_details where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_shareholder_weight where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_expense_header where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_expense_details where pot_id=new.pot_id and active_from=new.version
        union all select 1 from v3_expense_financials where pot_id=new.pot_id and active_from=new.version
    ) then
        raise exception 'V3 Pot version has no component revision' using errcode='23514';
    end if;
    return new;
end $$;
create constraint trigger v3_version_has_effect after insert on v3_pot_version
    deferrable initially deferred for each row execute function v3_require_effective_version();

create function v3_require_initial_components() returns trigger language plpgsql as $$
begin
    if tg_table_name='v3_pot_identity' then
        if not exists(select 1 from v3_pot_header where pot_id=new.pot_id and local_version=1 and active_from=1)
           or not exists(select 1 from v3_pot_details where pot_id=new.pot_id and local_version=1 and active_from=1) then
            raise exception 'Incomplete initial Pot' using errcode='23514';
        end if;
    elsif tg_table_name='v3_shareholder_identity' then
        if not exists(select 1 from v3_shareholder_header where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at)
           or not exists(select 1 from v3_shareholder_details where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at)
           or not exists(select 1 from v3_shareholder_weight where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at) then
            raise exception 'Incomplete initial Shareholder' using errcode='23514';
        end if;
    else
        if not exists(select 1 from v3_expense_header where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at)
           or not exists(select 1 from v3_expense_details where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at)
           or not exists(select 1 from v3_expense_financials where pot_id=new.pot_id and object_id=new.object_id and local_version=1 and active_from=new.created_at) then
            raise exception 'Incomplete initial Expense' using errcode='23514';
        end if;
    end if;
    return new;
end $$;
create constraint trigger v3_pot_complete after insert on v3_pot_identity
    deferrable initially deferred for each row execute function v3_require_initial_components();
create constraint trigger v3_shareholder_complete after insert on v3_shareholder_identity
    deferrable initially deferred for each row execute function v3_require_initial_components();
create constraint trigger v3_expense_complete after insert on v3_expense_identity
    deferrable initially deferred for each row execute function v3_require_initial_components();

create function v3_advance_pot_cas() returns trigger language plpgsql as $$
begin
    update v3_pot_identity set current_version=new.version
        where pot_id=new.pot_id and current_version=new.version-1;
    if not found then raise exception 'Pot global CAS collision' using errcode='40001'; end if;
    return new;
end $$;
create trigger v3_pot_cas before insert on v3_pot_version for each row execute function v3_advance_pot_cas();

-- ============================================================================
-- 2. Consumption foundation (V5, V11)
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
-- 3. Commands, results & events (V2, V5, V8, V10)
-- ============================================================================
create table v3_command (
    request_id uuid primary key,
    requester_issuer text not null,
    requester_subject text not null,
    binding_token text not null,
    pot_id uuid not null,
    observed_version bigint not null check(observed_version>=0),
    operation text not null,
    payload jsonb not null,
    status text not null default 'PENDING' check(status in ('PENDING','TERMINAL'))
);
create index v3_command_pending_request_idx on v3_command(request_id) where status='PENDING';

create function v3_command_immutable() returns trigger language plpgsql as $$
begin
    if tg_op='DELETE' or old.status='TERMINAL' or
       (to_jsonb(new)-'status') is distinct from (to_jsonb(old)-'status') then
        raise exception 'Immutable admitted Command' using errcode='23514';
    end if;
    return new;
end $$;
create trigger v3_command_intention before update or delete on v3_command for each row execute function v3_command_immutable();

create table v3_command_result (
    request_id uuid primary key references v3_command(request_id),
    outcome text not null constraint v3_command_result_outcome_check
        check(outcome in ('CHANGED','UNCHANGED','REJECTED','INVITATION_CREATED')),
    resulting_pot_version bigint check(resulting_pot_version>=1),
    rejection text,
    constraint v3_command_result_shape check(
        (outcome in ('CHANGED','UNCHANGED') and rejection is null and resulting_pot_version is not null)
        or (outcome='REJECTED' and rejection is not null and resulting_pot_version is null)
        or (outcome='INVITATION_CREATED' and rejection is null and resulting_pot_version is null))
);
create table v3_pot_event (
    event_id uuid primary key,
    subject_type text not null default 'POT' check(subject_type='POT'),
    pot_id uuid not null,
    subject_version bigint not null,
    request_id uuid not null unique references v3_command(request_id),
    recorded_at timestamptz not null,
    unique(pot_id,subject_version),
    foreign key(pot_id,subject_version) references v3_pot_version(pot_id,version)
);
create table v3_pot_event_component (
    event_id uuid not null references v3_pot_event(event_id),
    kind text not null,
    object_id uuid not null,
    local_version bigint not null check(local_version>=1),
    primary key(event_id,kind,object_id)
);
create table v3_command_completion_event (
    event_id uuid primary key,
    subject_type text not null default 'COMMAND' check(subject_type='COMMAND'),
    subject_id uuid not null unique references v3_command_result(request_id),
    recorded_at timestamptz not null
);
create table v3_event_counter (
    family text primary key check(family in ('POT','COMMAND_COMPLETION','IDENTITY','IDENTITY_COMPLETION','INVITATION')),
    position bigint not null check(position>=0)
);
insert into v3_event_counter values
    ('POT',0),('COMMAND_COMPLETION',0),('IDENTITY',0),('IDENTITY_COMPLETION',0),('INVITATION',0);

create table v3_event_position (
    family text not null references v3_event_counter(family),
    position bigint not null check(position>=1),
    event_id uuid not null unique,
    primary key(family,position)
);

create function v3_immutable_execution_row() returns trigger language plpgsql as $$
begin raise exception 'Immutable Command result/publication' using errcode='23514'; end $$;
create trigger v3_result_immutable before update or delete on v3_command_result for each row execute function v3_immutable_execution_row();
create trigger v3_pot_event_immutable before update or delete on v3_pot_event for each row execute function v3_immutable_execution_row();
create trigger v3_component_event_immutable before update or delete on v3_pot_event_component for each row execute function v3_immutable_execution_row();
create trigger v3_completion_immutable before update or delete on v3_command_completion_event for each row execute function v3_immutable_execution_row();
create trigger v3_position_immutable before update or delete on v3_event_position for each row execute function v3_immutable_execution_row();

create function v3_require_event_request() returns trigger language plpgsql as $$
begin
    if not exists(select 1 from v3_command c join v3_command_result r using(request_id)
        where c.request_id=new.request_id and c.status='TERMINAL' and c.pot_id=new.pot_id
          and r.outcome='CHANGED' and r.resulting_pot_version=new.subject_version) then
        raise exception 'Pot event does not match its terminal Command' using errcode='23514';
    end if;
    return new;
end $$;
create constraint trigger v3_pot_event_request after insert on v3_pot_event
    deferrable initially deferred for each row execute function v3_require_event_request();

-- ============================================================================
-- 4. Invitations (V10) — placed before v3_require_command_unit()
-- ============================================================================
create table v3_invitation (
    invitation_id uuid not null,
    version bigint not null check(version=1),
    creation_request_id uuid not null unique references v3_command(request_id),
    creator_issuer text collate "C" not null,
    creator_subject text collate "C" not null,
    pot_id uuid not null references v3_pot_identity(pot_id),
    expires_at timestamptz not null,
    token_hash text not null unique check(token_hash ~ '^[0-9a-f]{64}$'),
    protected_link text not null check(protected_link like 'v1.%'),
    writer_xid xid8 not null default pg_current_xact_id(),
    primary key(invitation_id,version)
);
create table v3_invitation_event (
    event_id uuid primary key,
    subject_type text not null default 'INVITATION' check(subject_type='INVITATION'),
    invitation_id uuid not null,
    version bigint not null,
    unique(invitation_id,version),
    foreign key(invitation_id,version) references v3_invitation(invitation_id,version),
    recorded_at timestamptz not null
);
create trigger v3_invitation_immutable before update or delete on v3_invitation for each row execute function v3_immutable_execution_row();
create trigger v3_invitation_event_immutable before update or delete on v3_invitation_event for each row execute function v3_immutable_execution_row();
create trigger v3_invitation_no_truncate before truncate on v3_invitation for each statement execute function v3_immutable_execution_row();
create trigger v3_invitation_event_no_truncate before truncate on v3_invitation_event for each statement execute function v3_immutable_execution_row();

-- Final V10 (preserving V8 column projections, SECURITY INVOKER)
create function v3_require_command_unit() returns trigger language plpgsql as $$
declare c v3_command%rowtype; r v3_command_result%rowtype;
begin
    -- Preserve V8's admission SECURITY INVOKER projection: never read binding_token/payload here.
    select request_id,status,pot_id into c.request_id,c.status,c.pot_id from v3_command where request_id=new.request_id;
    select request_id,outcome,resulting_pot_version into r.request_id,r.outcome,r.resulting_pot_version from v3_command_result where request_id=c.request_id;
    if c.status='PENDING' then
        if r.request_id is not null then raise exception 'Pending Command has result' using errcode='23514'; end if;
        return new;
    end if;
    select operation,requester_issuer,requester_subject into c.operation,c.requester_issuer,c.requester_subject from v3_command where request_id=c.request_id;
    if r.request_id is null or exists(select 1 from consumption_slot s where s.consumption_id='98f7268d-d340-581c-b83d-010926223e14'::uuid and s.consumable_id=c.request_id)
       or not exists(select 1 from v3_command_completion_event e join v3_event_position p on p.event_id=e.event_id and p.family='COMMAND_COMPLETION' where e.subject_id=c.request_id) then
        raise exception 'Incomplete terminal Command unit' using errcode='23514';
    end if;
    if c.operation='CreateInvitation' then
        if r.outcome not in ('INVITATION_CREATED','REJECTED') or exists(select 1 from v3_pot_event where request_id=c.request_id) then
            raise exception 'Invalid Invitation command shape' using errcode='23514';
        end if;
        if r.outcome='INVITATION_CREATED' then
            if not exists(select 1 from v3_invitation i join v3_invitation_event e using(invitation_id,version)
                join v3_event_position p on p.event_id=e.event_id and p.family='INVITATION'
                where i.creation_request_id=c.request_id and i.pot_id=c.pot_id
                  and i.creator_issuer=c.requester_issuer and i.creator_subject=c.requester_subject)
               or exists(select 1 from v3_pot_version where pot_id=c.pot_id and writer_xid=pg_current_xact_id()) then
                raise exception 'Invitation success without exact effect' using errcode='23514';
            end if;
        elsif exists(select 1 from v3_invitation where creation_request_id=c.request_id) then
            raise exception 'Rejected invitation has effect' using errcode='23514';
        end if;
    elsif r.outcome='INVITATION_CREATED' then
        raise exception 'Invitation outcome on Pot operation' using errcode='23514';
    end if;
    if r.outcome='CHANGED' then
        if not exists(select 1 from v3_pot_event e join v3_event_position p on p.event_id=e.event_id and p.family='POT'
             where e.request_id=c.request_id and e.pot_id=c.pot_id and e.subject_version=r.resulting_pot_version)
           or not exists(select 1 from v3_pot_event_component x join v3_pot_event e using(event_id) where e.request_id=c.request_id) then
            raise exception 'Changed Command missing exact publication' using errcode='23514';
        end if;
    elsif exists(select 1 from v3_pot_event where request_id=c.request_id) then
        raise exception 'No-effect Command has mutation event' using errcode='23514';
    end if;
    return new;
end $$;
create constraint trigger v3_command_unit after insert or update on v3_command
    deferrable initially deferred for each row execute function v3_require_command_unit();
create constraint trigger v3_result_unit after insert on v3_command_result
    deferrable initially deferred for each row execute function v3_require_command_unit();

create function v3_invitation_check_unit() returns trigger language plpgsql as $$
declare i v3_invitation%rowtype; c v3_command%rowtype;
begin
 select * into i from v3_invitation where invitation_id=new.invitation_id and version=new.version;
 select * into c from v3_command where request_id=i.creation_request_id;
 if c.status<>'TERMINAL' or c.operation<>'CreateInvitation'
    or c.pot_id<>i.pot_id or c.requester_issuer<>i.creator_issuer or c.requester_subject<>i.creator_subject
    or not exists(select 1 from v3_command_result where request_id=c.request_id and outcome='INVITATION_CREATED')
    or not exists(select 1 from v3_invitation_event e join v3_event_position p on p.event_id=e.event_id and p.family='INVITATION'
                  where e.invitation_id=i.invitation_id and e.version=i.version) then
  raise exception 'Incomplete Invitation unit' using errcode='23514';
 end if;
 return new;
end $$;
create constraint trigger v3_invitation_unit after insert on v3_invitation deferrable initially deferred for each row execute function v3_invitation_check_unit();
create constraint trigger v3_invitation_event_unit after insert on v3_invitation_event deferrable initially deferred for each row execute function v3_invitation_check_unit();

create function v3_invitation_position_step() returns trigger language plpgsql as $$
begin
 if new.family='INVITATION' and (new.position<>(select position from v3_event_counter where family='INVITATION')
    or (new.position>1 and not exists(select 1 from v3_event_position where family='INVITATION' and position=new.position-1))
    or not exists(select 1 from v3_invitation_event where event_id=new.event_id)) then
  raise exception 'Invalid Invitation position' using errcode='23514';
 end if;
 return new;
end $$;
create trigger v3_invitation_position_step before insert on v3_event_position for each row execute function v3_invitation_position_step();

create function v3_invitation_counter_step() returns trigger language plpgsql as $$
begin
 if old.family='INVITATION' and (tg_op='DELETE' or new.family<>old.family or new.position<>old.position+1) then
  raise exception 'Invalid Invitation counter' using errcode='23514';
 end if;
 return new;
end $$;
create trigger v3_invitation_counter_step before update or delete on v3_event_counter for each row execute function v3_invitation_counter_step();

create function v3_invitation_counter_unit() returns trigger language plpgsql as $$
begin
 if not exists(select 1 from v3_event_position where family='INVITATION' and position=new.position) then
  raise exception 'Unpublished Invitation counter' using errcode='23514';
 end if;
 return new;
end $$;
create constraint trigger v3_invitation_counter_unit after update on v3_event_counter deferrable initially deferred for each row when (new.family='INVITATION') execute function v3_invitation_counter_unit();

create function v3_invitation_final_expiry() returns trigger language plpgsql as $$
declare c v3_command%rowtype; expiry timestamptz;
begin
 select * into c from v3_command where request_id=new.request_id;
 if c.operation='CreateInvitation' and new.outcome='INVITATION_CREATED' then
  select expires_at into expiry from v3_invitation where creation_request_id=c.request_id;
 elsif c.operation='AttachShareholderViaInvitation' and new.outcome='CHANGED' then
  select expires_at into expiry from v3_invitation where token_hash=c.payload->>'tokenHash' and pot_id=c.pot_id;
 else return new;
 end if;
 if expiry is null or clock_timestamp()>=expiry then
  raise exception 'Invitation expired before final commit' using errcode='40001',constraint='v3_invitation_final_expiry';
 end if;
 return new;
end $$;
create constraint trigger v3_invitation_final_expiry after insert on v3_command_result
 deferrable initially deferred for each row execute function v3_invitation_final_expiry();

-- ============================================================================
-- 5. Identity authority, requests, admission association & link proposal (V6..V9)
-- ============================================================================
create table v3_identity_request (
    request_id uuid constraint v3_identity_request_pk primary key,
    requester_issuer text collate "C" not null,
    requester_subject text collate "C" not null,
    operation text not null constraint v3_identity_operation check(operation in ('REGISTRATION','LINK','UNLINK')),
    binding_token text,
    target_issuer text collate "C" not null,
    target_subject text collate "C" not null,
    admission_context jsonb not null,
    created_at timestamptz not null,
    status text not null default 'PENDING' constraint v3_identity_status check(status in ('PENDING','TERMINAL')),
    link_proposal_id uuid,
    constraint v3_identity_request_keys check(length(requester_issuer)>0 and length(requester_subject)>0
        and length(target_issuer)>0 and length(target_subject)>0),
    constraint v3_identity_request_token check((operation='REGISTRATION' and binding_token is null)
        or (operation<>'REGISTRATION' and binding_token is not null and length(binding_token)>0 and binding_token !~ '^[[:space:]]*$')),
    constraint v3_identity_registration_target check(operation<>'REGISTRATION'
        or (requester_issuer=target_issuer and requester_subject=target_subject)),
    constraint v3_identity_admission_shape check((jsonb_typeof(admission_context)='object'
        and admission_context ?& array['validator','contractVersion','initiator','target',
            'intentionFingerprint','grantOrCorrelationReference','validatedAt','attestations','origin']
        and jsonb_typeof(admission_context->'attestations')='object'
        and admission_context->>'origin' in ('CONTROLLED_INTERNAL','EXTERNAL_AUTHORIZATION_SERVER','AUTHENTICATED_HTTP')
        and jsonb_typeof(admission_context->'validator')='string' and length(admission_context->>'validator')>0
        and jsonb_typeof(admission_context->'contractVersion')='string' and length(admission_context->>'contractVersion')>0
        and jsonb_typeof(admission_context->'intentionFingerprint')='string' and length(admission_context->>'intentionFingerprint')>0
        and jsonb_typeof(admission_context->'grantOrCorrelationReference')='string' and length(admission_context->>'grantOrCorrelationReference')>0
        and jsonb_typeof(admission_context->'validatedAt')='string' and length(admission_context->>'validatedAt')>0
        and admission_context->'initiator'=jsonb_build_object('issuer',requester_issuer,'subject',requester_subject)
        and admission_context->'target'=jsonb_build_object('issuer',target_issuer,'subject',target_subject)) is true),
    constraint v3_link_request_proposal unique(request_id,link_proposal_id),
    constraint v3_http_link_proposal_shape check (
        case when operation='LINK' and admission_context->>'origin'='AUTHENTICATED_HTTP'
             then link_proposal_id is not null and link_proposal_id=request_id
             else link_proposal_id is null end)
);
create index v3_identity_pending_idx on v3_identity_request(request_id) where status='PENDING';

create table v3_eui_authority (
    eui_id uuid constraint v3_identity_anchor_pk primary key,
    issuer text collate "C" not null,
    subject text collate "C" not null,
    current_version bigint not null default 0 constraint v3_identity_cursor check(current_version>=0),
    constraint v3_identity_anchor_key unique(issuer,subject),
    constraint v3_identity_anchor_keys check(length(issuer)>0 and length(subject)>0)
);
create table v3_pocoma_user (
    user_id uuid constraint v3_identity_user_pk primary key,
    created_request_id uuid not null constraint v3_identity_user_request_unique unique
        constraint v3_identity_user_request_fk references v3_identity_request(request_id) not deferrable,
    writer_xid xid8 not null default pg_current_xact_id()
);
create table v3_eui_version (
    eui_id uuid not null constraint v3_identity_eui_anchor_fk references v3_eui_authority(eui_id) not deferrable,
    version bigint not null constraint v3_identity_positive_version check(version>=1),
    user_id uuid constraint v3_identity_eui_user_fk references v3_pocoma_user(user_id) not deferrable,
    binding_token text constraint v3_identity_token_history unique,
    request_id uuid not null constraint v3_identity_eui_request_unique unique
        constraint v3_identity_eui_request_fk references v3_identity_request(request_id) not deferrable,
    writer_xid xid8 not null default pg_current_xact_id(),
    constraint v3_identity_version_pk primary key(eui_id,version),
    constraint v3_identity_binding_pair check((user_id is null)=(binding_token is null)),
    constraint v3_identity_initial_binding check(version>1 or user_id is not null),
    constraint v3_identity_nonempty_token check(binding_token is null or length(binding_token)>0 and binding_token !~ '^[[:space:]]*$')
);
create table v3_identity_request_result (
    request_id uuid constraint v3_identity_result_pk primary key
        constraint v3_identity_result_request_fk references v3_identity_request(request_id) not deferrable,
    outcome text not null constraint v3_identity_outcome check(outcome in ('CHANGED','REJECTED')),
    target_eui_id uuid,
    target_version bigint,
    rejection text,
    constraint v3_identity_result_target_fk foreign key(target_eui_id,target_version)
        references v3_eui_version(eui_id,version) not deferrable,
    constraint v3_identity_result_shape check(
        (outcome='CHANGED' and target_eui_id is not null and target_version is not null
            and target_version>=1 and rejection is null)
        or (outcome='REJECTED' and target_eui_id is null and target_version is null
            and rejection is not null and rejection in ('INVALID_BINDING','TARGET_ALREADY_BOUND','TARGET_NOT_BOUND','TARGET_BOUND_TO_ANOTHER_USER')))
);
create table v3_identity_event (
    event_id uuid constraint v3_identity_event_pk primary key,
    subject_type text not null constraint v3_identity_event_subject check(subject_type in ('POCOMA_USER','EXTERNAL_USER_IDENTITY')),
    subject_id uuid not null,
    subject_version bigint not null constraint v3_identity_event_version check(subject_version>=1),
    request_id uuid not null constraint v3_identity_event_request_fk references v3_identity_request(request_id) not deferrable,
    recorded_at timestamptz not null,
    constraint v3_identity_event_state_unique unique(subject_type,subject_id,subject_version),
    constraint v3_identity_event_request_unique unique(request_id,subject_type)
);
create table v3_identity_completion_event (
    event_id uuid constraint v3_identity_completion_pk primary key,
    subject_type text not null constraint v3_identity_completion_subject check(subject_type in ('REGISTRATION','LINK','UNLINK')),
    subject_id uuid not null constraint v3_identity_completion_request_unique unique
        constraint v3_identity_completion_result_fk references v3_identity_request_result(request_id) not deferrable,
    recorded_at timestamptz not null
);

create table v3_admission_association (
    request_id uuid primary key,
    category text not null check(category in ('COMMAND','IDENTITY')),
    operation text not null,
    canonical_version integer not null check(canonical_version>0),
    lookup_key_id text not null check(length(lookup_key_id)>0),
    comparison_key_id text not null check(length(comparison_key_id)>0),
    scope_key_fingerprint bytea not null unique check(octet_length(scope_key_fingerprint)=32),
    intent_fingerprint bytea not null check(octet_length(intent_fingerprint)=32),
    created_at timestamptz not null,
    command_id uuid unique references v3_command(request_id) deferrable initially deferred,
    identity_request_id uuid unique references v3_identity_request(request_id) deferrable initially deferred,
    check ((category='COMMAND' and command_id is not null and command_id=request_id and identity_request_id is null)
        or (category='IDENTITY' and identity_request_id is not null and identity_request_id=request_id and command_id is null)),
    constraint v3_link_association_intention unique (request_id,category,operation,intent_fingerprint)
);
create trigger v3_admission_immutable before update or delete on v3_admission_association
    for each row execute function v3_immutable_execution_row();

create table v3_link_proposal (
    link_request_id uuid primary key,
    initiator_issuer text not null check(length(initiator_issuer)>0),
    initiator_subject text not null check(length(initiator_subject)>0),
    target_issuer text not null check(length(target_issuer)>0),
    target_subject text not null check(length(target_subject)>0),
    binding_token text not null check(length(binding_token)>0),
    authenticated_at timestamptz not null,
    issued_at timestamptz not null,
    principal_expires_at timestamptz not null,
    created_at timestamptz not null,
    expires_at timestamptz not null check(expires_at>created_at),
    canonical_version integer not null check(canonical_version=1),
    lookup_key_id text not null check(length(lookup_key_id)>0),
    comparison_key_id text not null check(length(comparison_key_id)>0),
    scope_key_fingerprint bytea not null unique check(octet_length(scope_key_fingerprint)=32),
    intent_fingerprint bytea not null check(octet_length(intent_fingerprint)=32),
    accepted_at timestamptz,
    identity_request_id uuid unique,
    admission_category text generated always as ('IDENTITY'::text) stored,
    admission_operation text generated always as ('Link'::text) stored,
    check((initiator_issuer,initiator_subject)<>(target_issuer,target_subject)),
    check((accepted_at is null and identity_request_id is null) or
          (accepted_at is not null and identity_request_id is not null and identity_request_id=link_request_id
           and accepted_at>=created_at and accepted_at<expires_at)),
    foreign key(identity_request_id,link_request_id)
        references v3_identity_request(request_id,link_proposal_id)
        deferrable initially deferred,
    foreign key(identity_request_id,admission_category,admission_operation,intent_fingerprint)
        references v3_admission_association(request_id,category,operation,intent_fingerprint)
        deferrable initially deferred
);
alter table v3_identity_request add constraint v3_link_proposal_reference foreign key(link_proposal_id)
    references v3_link_proposal(link_request_id) deferrable initially deferred;

create function v3_link_proposal_guard() returns trigger language plpgsql as $$
begin
    if tg_op='INSERT' then
        if new.accepted_at is not null or new.identity_request_id is not null then
            raise exception 'Proposal must start unaccepted' using errcode='23514';
        end if;
    elsif tg_op='DELETE' then
        raise exception 'Proposal deletion forbidden' using errcode='23514';
    elsif (to_jsonb(new)-array['accepted_at','identity_request_id','admission_category','admission_operation']) is distinct from
          (to_jsonb(old)-array['accepted_at','identity_request_id','admission_category','admission_operation'])
          or old.accepted_at is not null or new.accepted_at is null or new.identity_request_id is null
          or clock_timestamp()>=new.expires_at then
        raise exception 'Immutable or expired proposal' using errcode='23514';
    end if;
    return new;
end $$;
create trigger v3_link_proposal_immutable before insert or update or delete on v3_link_proposal
    for each row execute function v3_link_proposal_guard();

create function v3_http_link_requires_proposal() returns trigger language plpgsql as $$
declare p v3_link_proposal%rowtype;
begin
    if new.operation<>'LINK' or new.admission_context->>'origin'<>'AUTHENTICATED_HTTP' then return new; end if;
    select * into p from v3_link_proposal where identity_request_id=new.request_id;
    if p.link_request_id is null
       or new.requester_issuer is distinct from p.initiator_issuer
       or new.requester_subject is distinct from p.initiator_subject
       or new.binding_token is distinct from p.binding_token
       or new.target_issuer is distinct from p.target_issuer
       or new.target_subject is distinct from p.target_subject
       or new.admission_context->>'validator'<>'pocoma-v3-link-post'
       or new.admission_context->>'contractVersion'<>'1'
       or new.admission_context->>'grantOrCorrelationReference'<>p.link_request_id::text
       or new.admission_context->>'intentionFingerprint'<>encode(p.intent_fingerprint,'hex')
       or (new.admission_context->>'validatedAt')::timestamptz is distinct from p.accepted_at
       or new.created_at is distinct from p.accepted_at
       or new.admission_context->'attestations'->>'acceptedIssuer' is distinct from p.target_issuer
       or new.admission_context->'attestations'->>'acceptedSubject' is distinct from p.target_subject
       or (new.admission_context->'attestations'->>'expiresAt')::timestamptz is null
       or (new.admission_context->'attestations'->>'expiresAt')::timestamptz<=p.accepted_at
       or (new.admission_context->'attestations'->>'authenticatedAt')::timestamptz is null
       or (new.admission_context->'attestations'->>'issuedAt')::timestamptz is null then
        raise exception 'HTTP Link requires exact accepted proposal' using errcode='23514';
    end if;
    return new;
end $$;
create constraint trigger v3_http_link_proposal after insert on v3_identity_request
    deferrable initially deferred for each row execute function v3_http_link_requires_proposal();

create function v3_identity_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'Immutable Identity row' using errcode='23514', constraint=tg_name;
end $$;
create trigger v3_identity_user_immutable before update or delete on v3_pocoma_user
    for each row execute function v3_identity_immutable();
create trigger v3_identity_version_immutable before update or delete on v3_eui_version
    for each row execute function v3_identity_immutable();
create trigger v3_identity_result_immutable before update or delete on v3_identity_request_result
    for each row execute function v3_identity_immutable();
create trigger v3_identity_event_immutable before update or delete on v3_identity_event
    for each row execute function v3_identity_immutable();
create trigger v3_identity_completion_immutable before update or delete on v3_identity_completion_event
    for each row execute function v3_identity_immutable();

create function v3_identity_request_guard() returns trigger language plpgsql as $$
begin
    if tg_op='INSERT' then
        if new.status='PENDING' then return new; end if;
    elsif tg_op='UPDATE' then
        if old.status='PENDING' and new.status='TERMINAL'
           and (to_jsonb(old)-'status')=(to_jsonb(new)-'status') then return new; end if;
    end if;
    raise exception 'Immutable Identity intention or invalid terminal transition'
        using errcode='23514', constraint='v3_identity_request_guard';
end $$;
create trigger v3_identity_request_guard before insert or update or delete on v3_identity_request
    for each row execute function v3_identity_request_guard();

create function v3_identity_anchor_guard() returns trigger language plpgsql as $$
begin
    if tg_op='INSERT' then
        if new.current_version=0 then return new; end if;
    elsif tg_op='UPDATE' then
        if new.eui_id=old.eui_id and new.issuer=old.issuer and new.subject=old.subject
           and new.current_version=old.current_version+1 then return new; end if;
    end if;
    raise exception 'Immutable EUI key or invalid cursor step'
        using errcode='23514', constraint='v3_identity_anchor_guard';
end $$;
create trigger v3_identity_anchor_guard before insert or update or delete on v3_eui_authority
    for each row execute function v3_identity_anchor_guard();

create function v3_identity_writer_guard() returns trigger language plpgsql as $$
begin
    if new.writer_xid<>pg_current_xact_id() then
        raise exception 'Foreign Identity writer transaction'
            using errcode='23514', constraint='v3_identity_writer_xid';
    end if;
    new.writer_xid:=pg_current_xact_id();
    return new;
end $$;
create trigger v3_identity_user_writer before insert on v3_pocoma_user
    for each row execute function v3_identity_writer_guard();
create trigger v3_identity_version_writer before insert on v3_eui_version
    for each row execute function v3_identity_writer_guard();

create function v3_identity_successor_guard() returns trigger language plpgsql as $$
declare cursor_version bigint;
begin
    select current_version into cursor_version from v3_eui_authority where eui_id=new.eui_id for update;
    if cursor_version is distinct from new.version-1 then
        raise exception 'EUI version is not the protected successor'
            using errcode='23514', constraint='v3_identity_successor';
    end if;
    return new;
end $$;
create trigger v3_identity_successor before insert on v3_eui_version
    for each row execute function v3_identity_successor_guard();

create function v3_identity_check_anchor() returns trigger language plpgsql as $$
declare a v3_eui_authority%rowtype;
begin
    select * into a from v3_eui_authority where eui_id=new.eui_id;
    if (a.current_version=0 and exists(select 1 from v3_eui_version where eui_id=a.eui_id and version=1))
       or (a.current_version>0 and not exists(select 1 from v3_eui_version where eui_id=a.eui_id and version=a.current_version))
       or exists(select 1 from v3_eui_version where eui_id=a.eui_id and version=a.current_version+1) then
        raise exception 'EUI cursor and exact history differ' using errcode='23514', constraint=tg_name;
    end if;
    if tg_table_name='v3_eui_version' then
        if new.version>1 and not exists(select 1 from v3_eui_version where eui_id=new.eui_id and version=new.version-1) then
            raise exception 'EUI history has a gap' using errcode='23514', constraint=tg_name;
        end if;
    end if;
    return new;
end $$;
create constraint trigger v3_identity_anchor_state after insert or update on v3_eui_authority
    deferrable initially deferred for each row execute function v3_identity_check_anchor();
create constraint trigger v3_identity_version_state after insert on v3_eui_version
    deferrable initially deferred for each row execute function v3_identity_check_anchor();

-- Final V8 definition (SECURITY INVOKER, column-projected reads)
create function v3_identity_check_unit(rid uuid, positioned boolean) returns void language plpgsql as $$
declare c v3_identity_request%rowtype; r v3_identity_request_result%rowtype;
    v v3_eui_version%rowtype; a v3_eui_authority%rowtype; u v3_pocoma_user%rowtype;
    completion v3_identity_completion_event%rowtype; eu v3_identity_event%rowtype; ee v3_identity_event%rowtype;
    users_count bigint; versions_count bigint; events_count bigint;
    user_position bigint; eui_position bigint;
begin
    select request_id,status,operation,target_issuer,target_subject
        into c.request_id,c.status,c.operation,c.target_issuer,c.target_subject from v3_identity_request where request_id=rid;
    select request_id,outcome,target_eui_id,target_version
        into r.request_id,r.outcome,r.target_eui_id,r.target_version from v3_identity_request_result where request_id=rid;
    select count(*) into users_count from v3_pocoma_user where created_request_id=rid;
    select count(*) into versions_count from v3_eui_version where request_id=rid;
    select count(*) into events_count from v3_identity_event where request_id=rid;
    select event_id,subject_type into completion.event_id,completion.subject_type
        from v3_identity_completion_event where subject_id=rid;
    -- Open the position and slot relations in the prefix even when there are no rows yet.
    perform 1 from v3_event_position where event_id=completion.event_id;
    perform 1 from consumption_slot where consumption_id='2999e75c-272d-5afe-a966-c9364ef021fe' and consumable_id=rid;
    if c.request_id is null then
        raise exception 'Missing Identity request' using errcode='23514', constraint='v3_identity_unit';
    end if;
    if c.status='PENDING' then
        if r.request_id is not null or users_count<>0 or versions_count<>0 or events_count<>0 or completion.event_id is not null then
            raise exception 'Pending Identity has effects' using errcode='23514', constraint='v3_identity_unit';
        end if;
        return;
    end if;
    if r.request_id is null or completion.event_id is null or completion.subject_type<>c.operation
       or exists(select 1 from consumption_slot where consumption_id='2999e75c-272d-5afe-a966-c9364ef021fe' and consumable_id=rid)
       or (positioned and not exists(select 1 from v3_event_position where family='IDENTITY_COMPLETION' and event_id=completion.event_id)) then
        raise exception 'Incomplete Identity terminal unit' using errcode='23514', constraint='v3_identity_unit';
    end if;
    if r.outcome='REJECTED' then
        if users_count<>0 or versions_count<>0 or events_count<>0 then
            raise exception 'Rejected Identity has effects' using errcode='23514', constraint='v3_identity_unit';
        end if;
        return;
    end if;
    select * into v from v3_eui_version where eui_id=r.target_eui_id and version=r.target_version;
    select * into a from v3_eui_authority where eui_id=v.eui_id;
    select * into ee from v3_identity_event where request_id=rid and subject_type='EXTERNAL_USER_IDENTITY';
    if versions_count<>1 or v.request_id is distinct from rid or v.writer_xid is distinct from pg_current_xact_id()
       or a.issuer is distinct from c.target_issuer or a.subject is distinct from c.target_subject
       or ee.subject_id is distinct from v.eui_id or ee.subject_version is distinct from v.version
       or (c.operation='UNLINK') is distinct from (v.user_id is null)
       or (positioned and not exists(select 1 from v3_event_position where family='IDENTITY' and event_id=ee.event_id)) then
        raise exception 'Identity result, request, state and event differ' using errcode='23514', constraint='v3_identity_unit';
    end if;
    if v.version>1 and not exists(select 1 from v3_eui_version p where p.eui_id=v.eui_id and p.version=v.version-1
            and (p.user_id is null)<>(v.user_id is null)) then
        raise exception 'Identity transition must change binding state' using errcode='23514', constraint='v3_identity_unit';
    end if;
    if c.operation='REGISTRATION' then
        select * into u from v3_pocoma_user where created_request_id=rid;
        select * into eu from v3_identity_event where request_id=rid and subject_type='POCOMA_USER';
        if users_count<>1 or events_count<>2 or u.user_id is distinct from v.user_id
           or u.writer_xid is distinct from v.writer_xid or eu.subject_id is distinct from u.user_id
           or eu.subject_version is distinct from 1::bigint then
            raise exception 'Registration is not an exact User and EUI creation' using errcode='23514', constraint='v3_identity_unit';
        end if;
        select position into user_position from v3_event_position where family='IDENTITY' and event_id=eu.event_id;
        select position into eui_position from v3_event_position where family='IDENTITY' and event_id=ee.event_id;
        if positioned and (user_position is null or eui_position is distinct from user_position+1) then
            raise exception 'Registration event batch is not User then EUI' using errcode='23514', constraint='v3_identity_unit';
        end if;
    elsif users_count<>0 or events_count<>1 then
        raise exception 'Link or Unlink has extra effects' using errcode='23514', constraint='v3_identity_unit';
    end if;
end $$;

create function v3_identity_require_unit() returns trigger language plpgsql as $$
begin
    perform v3_identity_check_unit(new.request_id,true);
    return new;
end $$;
create function v3_identity_require_user_unit() returns trigger language plpgsql as $$
begin
    perform v3_identity_check_unit(new.created_request_id,true);
    return new;
end $$;
create function v3_identity_require_completion_unit() returns trigger language plpgsql as $$
begin
    perform v3_identity_check_unit(new.subject_id,true);
    return new;
end $$;
create constraint trigger v3_identity_request_unit after insert or update on v3_identity_request
    deferrable initially deferred for each row execute function v3_identity_require_unit();
create constraint trigger v3_identity_result_unit after insert on v3_identity_request_result
    deferrable initially deferred for each row execute function v3_identity_require_unit();
create constraint trigger v3_identity_user_unit after insert on v3_pocoma_user
    deferrable initially deferred for each row execute function v3_identity_require_user_unit();
create constraint trigger v3_identity_version_unit after insert on v3_eui_version
    deferrable initially deferred for each row execute function v3_identity_require_unit();
create constraint trigger v3_identity_event_unit after insert on v3_identity_event
    deferrable initially deferred for each row execute function v3_identity_require_unit();
create constraint trigger v3_identity_completion_unit after insert on v3_identity_completion_event
    deferrable initially deferred for each row execute function v3_identity_require_completion_unit();

create function v3_identity_require_position() returns trigger language plpgsql as $$
declare rid uuid;
begin
    select request_id into rid from v3_identity_event where event_id=new.event_id;
    if rid is not null then
        if new.family<>'IDENTITY' then
            raise exception 'Identity position family mismatch' using errcode='23514', constraint='v3_identity_position_unit';
        end if;
    else
        select subject_id into rid from v3_identity_completion_event where event_id=new.event_id;
        if rid is not null and new.family<>'IDENTITY_COMPLETION' then
            raise exception 'Identity completion position family mismatch' using errcode='23514', constraint='v3_identity_position_unit';
        end if;
    end if;
    if rid is not null then
        perform v3_identity_check_unit(rid,true);
    elsif new.family in ('IDENTITY','IDENTITY_COMPLETION') then
        raise exception 'Orphan Identity position' using errcode='23514', constraint='v3_identity_position_unit';
    end if;
    return new;
end $$;
create constraint trigger v3_identity_position_unit after insert on v3_event_position
    deferrable initially deferred for each row when (new.family in ('IDENTITY','IDENTITY_COMPLETION'))
    execute function v3_identity_require_position();

create function v3_identity_position_step() returns trigger language plpgsql as $$
begin
    if new.family in ('IDENTITY','IDENTITY_COMPLETION') and
       (new.position>(select position from v3_event_counter where family=new.family)
        or (new.position>1 and not exists(select 1 from v3_event_position where family=new.family and position=new.position-1))) then
        raise exception 'Identity positions must be allocated densely' using errcode='23514', constraint='v3_identity_position_step';
    end if;
    return new;
end $$;
create trigger v3_identity_position_step before insert on v3_event_position
    for each row execute function v3_identity_position_step();
create function v3_identity_counter_guard() returns trigger language plpgsql as $$
begin
    if old.family in ('IDENTITY','IDENTITY_COMPLETION') and
       (tg_op='DELETE' or new.family<>old.family or new.position<=old.position) then
        raise exception 'Invalid Identity counter step' using errcode='23514', constraint='v3_identity_counter_guard';
    end if;
    return new;
end $$;
create trigger v3_identity_counter_guard before update or delete on v3_event_counter
    for each row execute function v3_identity_counter_guard();
create function v3_identity_require_counter() returns trigger language plpgsql as $$
declare last_position bigint;
begin
    select position into last_position from v3_event_counter where family=new.family;
    if not exists(select 1 from v3_event_position where family=new.family and position=last_position) then
        raise exception 'Identity counter lacks its publication' using errcode='23514', constraint='v3_identity_counter_unit';
    end if;
    return new;
end $$;
create constraint trigger v3_identity_counter_unit after update on v3_event_counter
    deferrable initially deferred for each row when (new.family in ('IDENTITY','IDENTITY_COMPLETION'))
    execute function v3_identity_require_counter();

-- ============================================================================
-- 6. Sequential request publication (V11)
-- ============================================================================
create table v3_request_counter (
    family text constraint v3_request_counter_pk primary key
        constraint v3_request_counter_family_check check(family in ('COMMAND','IDENTITY_REQUEST')),
    position bigint not null constraint v3_request_counter_position_check check(position>=0)
);
insert into v3_request_counter(family,position) values ('COMMAND',0),('IDENTITY_REQUEST',0);

create table v3_request_position (
    family text not null constraint v3_request_position_family_fk references v3_request_counter(family)
        constraint v3_request_position_family_check check(family in ('COMMAND','IDENTITY_REQUEST')),
    position bigint not null constraint v3_request_position_positive_check check(position>=1),
    request_id uuid not null,
    constraint v3_request_position_pk primary key(family,position),
    constraint v3_request_position_request_unique unique(family,request_id)
);

create function v3_request_position_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'Immutable request publication position' using errcode='23514', constraint=tg_name;
end $$;
create trigger v3_request_position_immutable before update or delete on v3_request_position
    for each row execute function v3_request_position_immutable();
create trigger v3_request_position_no_truncate before truncate on v3_request_position
    for each statement execute function v3_request_position_immutable();
create trigger v3_request_counter_no_truncate before truncate on v3_request_counter
    for each statement execute function v3_request_position_immutable();

create function v3_request_counter_guard() returns trigger language plpgsql as $$
begin
    if tg_op<>'UPDATE' or new.family<>old.family or new.position<>old.position+1 then
        raise exception 'Invalid request counter step' using errcode='23514', constraint='v3_request_counter_step';
    end if;
    return new;
end $$;
create trigger v3_request_counter_step before insert or update or delete on v3_request_counter
    for each row execute function v3_request_counter_guard();

create function v3_request_position_step() returns trigger language plpgsql as $$
begin
    if new.position>(select position from v3_request_counter where family=new.family)
       or (new.position>1 and not exists(select 1 from v3_request_position where family=new.family and position=new.position-1)) then
        raise exception 'Request positions must be allocated densely' using errcode='23514', constraint='v3_request_position_step';
    end if;
    return new;
end $$;
create trigger v3_request_position_step after insert on v3_request_position
    for each row execute function v3_request_position_step();

create function v3_request_require_counter() returns trigger language plpgsql as $$
declare last_position bigint;
begin
    select position into last_position from v3_request_counter where family=new.family;
    if not exists(select 1 from v3_request_position where family=new.family and position=last_position) then
        raise exception 'Request counter lacks its publication' using errcode='23514', constraint='v3_request_counter_unit';
    end if;
    return new;
end $$;
create constraint trigger v3_request_counter_unit after update on v3_request_counter
    deferrable initially deferred for each row execute function v3_request_require_counter();

create function v3_request_require_position() returns trigger language plpgsql as $$
begin
    if new.family='COMMAND' then
        if not exists(select 1 from v3_command where request_id=new.request_id) then
            raise exception 'Orphan COMMAND request position' using errcode='23514', constraint='v3_request_position_unit';
        end if;
    elsif new.family='IDENTITY_REQUEST' then
        if not exists(select 1 from v3_identity_request where request_id=new.request_id) then
            raise exception 'Orphan IDENTITY_REQUEST position' using errcode='23514', constraint='v3_request_position_unit';
        end if;
    else
        raise exception 'Unknown request position family' using errcode='23514', constraint='v3_request_position_unit';
    end if;
    return new;
end $$;
create constraint trigger v3_request_position_unit after insert on v3_request_position
    deferrable initially deferred for each row execute function v3_request_require_position();

create function v3_require_command_positioned() returns trigger language plpgsql as $$
begin
    if not exists(select 1 from v3_request_position where family='COMMAND' and request_id=new.request_id) then
        raise exception 'Admitted Command lacks its sequential publication position'
            using errcode='23514', constraint='v3_command_request_positioned';
    end if;
    return new;
end $$;
create constraint trigger v3_command_request_positioned after insert on v3_command
    deferrable initially deferred for each row execute function v3_require_command_positioned();

create function v3_require_identity_request_positioned() returns trigger language plpgsql as $$
begin
    if not exists(select 1 from v3_request_position where family='IDENTITY_REQUEST' and request_id=new.request_id) then
        raise exception 'Admitted IdentityRequest lacks its sequential publication position'
            using errcode='23514', constraint='v3_identity_request_positioned';
    end if;
    return new;
end $$;
create constraint trigger v3_identity_request_positioned after insert on v3_identity_request
    deferrable initially deferred for each row execute function v3_require_identity_request_positioned();

-- ============================================================================
-- 7. Permissions & final privilege verification (V5, V9, V10, V11)
-- ============================================================================
revoke all on consumption_routing_contract, partition_ownership, discovery_checkpoint, consumption_slot from public, "${expectedRuntime}";
revoke create on schema "${expectedSchema}" from public, "${expectedRuntime}";
grant usage on schema "${expectedSchema}" to "${expectedRuntime}";
grant select on consumption_routing_contract, partition_ownership, discovery_checkpoint, consumption_slot to "${expectedRuntime}";
revoke all on v3_link_proposal from public;
revoke all on v3_invitation, v3_invitation_event from public, "${expectedRuntime}";
grant select,insert on v3_invitation, v3_invitation_event to "${expectedRuntime}";
revoke all on table v3_request_counter, v3_request_position from public, "${expectedRuntime}";

do $$
declare t text;
begin
  foreach t in array array['consumption_routing_contract','partition_ownership','discovery_checkpoint','consumption_slot','v3_request_counter','v3_request_position'] loop
    if exists(select 1 from pg_roles where pg_has_role('${expectedRuntime}',oid,'MEMBER')
        and has_table_privilege(oid,t,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')) then
      raise exception 'inherited L2 runtime write privilege: %',t using errcode='42501';
    end if;
  end loop;
end $$;
