-- First activation requires an empty READ authority (new install or the exclusive L6/I2 reset).
do $$
begin
  if current_database() <> '${expectedDatabase}' or current_schema() <> '${expectedSchema}'
     or current_user <> '${expectedAdmin}' or current_schema() = 'public'
     or '${expectedRuntime}' = current_user
     or not exists (
       select 1 from pg_namespace
       where nspname = current_schema() and nspowner = current_user::regrole
     ) then
    raise exception 'Wrong READ availability migration target' using errcode = '23514';
  end if;
  if to_regclass('read_pot_balance_content') is null then
    raise exception 'READ availability requires V4' using errcode = '23514';
  end if;
end $$;

lock table read_pot_version, read_current_pot, read_pot_balance, read_pot_balance_aggregate,
  read_pot_visibility_state, read_user_pot, read_current_binding, read_command_result,
  read_identity_request_result, read_current_invitation, consumption_slot, discovery_checkpoint
  in access exclusive mode;

do $$
begin
  if exists (select from read_pot_version)
     or exists (select from read_current_pot)
     or exists (select from read_pot_balance)
     or exists (select from read_pot_balance_aggregate)
     or exists (select from read_pot_visibility_state)
     or exists (select from read_user_pot)
     or exists (select from read_current_binding)
     or exists (select from read_command_result)
     or exists (select from read_identity_request_result)
     or exists (select from read_current_invitation)
     or exists (select from consumption_slot)
     or exists (select from discovery_checkpoint where position > 0) then
    raise exception 'READ V5 requires an empty authority; use the exclusive READ rebuild'
      using errcode = '23514';
  end if;
end $$;

create table read_availability_counter (
  family text primary key check (family in (
    'POT_VERSION_AVAILABLE', 'POT_BALANCE_AVAILABLE', 'COMMAND_RESULT_AVAILABLE',
    'IDENTITY_REQUEST_RESULT_AVAILABLE', 'CURRENT_POT_CHANGED', 'POT_VISIBILITY_CHANGED',
    'CURRENT_BINDING_CHANGED', 'CURRENT_INVITATION_CHANGED'
  )),
  position bigint not null check (position >= 0),
  writer_xid xid8,
  check ((position = 0) = (writer_xid is null))
) with (fillfactor = 80);

insert into read_availability_counter (family, position) values
  ('POT_VERSION_AVAILABLE', 0), ('POT_BALANCE_AVAILABLE', 0), ('COMMAND_RESULT_AVAILABLE', 0),
  ('IDENTITY_REQUEST_RESULT_AVAILABLE', 0), ('CURRENT_POT_CHANGED', 0), ('POT_VISIBILITY_CHANGED', 0),
  ('CURRENT_BINDING_CHANGED', 0), ('CURRENT_INVITATION_CHANGED', 0);

create table read_availability_event (
  family text not null,
  position bigint not null check (position > 0),
  event_id uuid not null unique,
  projection_key uuid not null,
  logical_version bigint,
  primary key (family, position),
  unique nulls not distinct (family, projection_key, logical_version),
  check (event_id <> projection_key),
  check (
    (family in ('POT_VERSION_AVAILABLE', 'POT_BALANCE_AVAILABLE')
      and logical_version is not null and logical_version > 0)
    or (family in ('COMMAND_RESULT_AVAILABLE', 'IDENTITY_REQUEST_RESULT_AVAILABLE')
      and logical_version is null)
  )
);

create table read_current_availability (
  family text not null check (family in (
    'CURRENT_POT_CHANGED', 'POT_VISIBILITY_CHANGED', 'CURRENT_BINDING_CHANGED', 'CURRENT_INVITATION_CHANGED'
  )),
  projection_key uuid not null,
  logical_version bigint not null check (logical_version > 0),
  position bigint not null check (position > 0),
  primary key (family, projection_key),
  unique (family, position)
) with (autovacuum_vacuum_scale_factor = 0.1);

create function read_availability_counter_step() returns trigger language plpgsql
set search_path to pg_catalog, "${expectedSchema}" as $$
begin
  if tg_op <> 'UPDATE' then
    raise exception 'Cannot remove an availability counter' using errcode = '23514';
  end if;
  if new.family <> old.family or new.position <> old.position + 1
     or old.writer_xid is not distinct from pg_current_xact_id() then
    raise exception 'Invalid availability counter step' using errcode = '23514';
  end if;
  new.writer_xid := pg_current_xact_id();
  return new;
end $$;

create function read_availability_position() returns trigger language plpgsql
set search_path to pg_catalog, "${expectedSchema}" as $$
begin
  if not exists (
    select from read_availability_counter
    where family = new.family and position = new.position and writer_xid = pg_current_xact_id()
  ) then
    raise exception 'Position not allocated by this transaction' using errcode = '23514';
  end if;
  if tg_op = 'UPDATE' then
    if new.family <> old.family or new.projection_key <> old.projection_key
       or new.logical_version <= old.logical_version or new.position <= old.position then
      raise exception 'Current availability must strictly advance for the same key' using errcode = '23514';
    end if;
  end if;
  return new;
end $$;

create function read_availability_require_position() returns trigger language plpgsql
set search_path to pg_catalog, "${expectedSchema}" as $$
begin
  if not (exists (
    select from read_availability_event where family = new.family and position = new.position
  ) or exists (
    select from read_current_availability where family = new.family and position = new.position
  )) then
    raise exception 'Availability allocation lacks its publication' using errcode = '23514';
  end if;
  return null;
end $$;

-- The trigger arguments are the family, projection key column and optional version column.
create function read_effect_require_availability() returns trigger language plpgsql
set search_path to pg_catalog, "${expectedSchema}" as $$
declare
  effect jsonb := to_jsonb(new);
  effect_key uuid := (effect ->> tg_argv[1])::uuid;
  effect_version bigint := (effect ->> tg_argv[2])::bigint;
  is_current boolean := tg_nargs = 4;
  valid boolean;
begin
  if is_current then
    -- Visibility can insert version 0 and then advance it in the same transaction.
    -- Verify the final business row for every deferred invocation.
    execute format(
      'select exists (
        select from %I projection
        join read_current_availability marker
          on marker.family = $1 and marker.projection_key = projection.%I
         and marker.logical_version = projection.%I
        where projection.%I = $2)', tg_table_name, tg_argv[1], tg_argv[2], tg_argv[1]
    ) into valid using tg_argv[0], effect_key;
  elsif effect_version is null then
    select exists (
      select from read_availability_event
      where family = tg_argv[0] and projection_key = effect_key and logical_version is null
    ) into valid;
  else
    select exists (
      select from read_availability_event
      where family = tg_argv[0] and projection_key = effect_key and logical_version = effect_version
    ) into valid;
  end if;
  if not valid then
    raise exception 'READ effect lacks availability for %', tg_argv[0] using errcode = '23514';
  end if;
  return null;
end $$;

create function read_current_effect_step() returns trigger language plpgsql
set search_path to pg_catalog, "${expectedSchema}" as $$
declare
  previous jsonb := to_jsonb(old);
  proposed jsonb := to_jsonb(new);
  previous_version bigint := (previous ->> tg_argv[2])::bigint;
  proposed_version bigint := (proposed ->> tg_argv[2])::bigint;
  previous_key uuid := (previous ->> tg_argv[1])::uuid;
begin
  if previous_key is distinct from (proposed ->> tg_argv[1])::uuid
     or proposed_version <= previous_version then
    raise exception 'Current projection must strictly advance for the same key' using errcode = '23514';
  end if;
  -- Only visibility creates a transient version-zero row to acquire the per-Pot lock.
  if tg_argv[0] = 'POT_VISIBILITY_CHANGED' and previous_version = 0 then
    return new;
  end if;
  if not exists (
    select from read_current_availability marker
    join read_availability_counter counter using (family)
    where marker.family = tg_argv[0] and marker.projection_key = previous_key
      and marker.logical_version = previous_version and marker.position <= counter.position
  ) then
    raise exception 'Previous READ effect lacks current availability for %', tg_argv[0] using errcode = '23514';
  end if;
  return new;
end $$;

create view read_availability_effects with (security_invoker = true) as
  select 'POT_VERSION_AVAILABLE'::text as family, pot_id as projection_key,
         pot_version as logical_version from read_pot_version
  union all select 'POT_BALANCE_AVAILABLE', pot_id, pot_version from read_pot_balance
  union all select 'COMMAND_RESULT_AVAILABLE', request_id, null::bigint from read_command_result
  union all select 'IDENTITY_REQUEST_RESULT_AVAILABLE', request_id, null::bigint
    from read_identity_request_result
  union all select 'CURRENT_POT_CHANGED', pot_id, pot_version from read_current_pot
  union all select 'POT_VISIBILITY_CHANGED', pot_id, materialized_version from read_pot_visibility_state
  union all select 'CURRENT_BINDING_CHANGED', eui_id, eui_version from read_current_binding
  union all select 'CURRENT_INVITATION_CHANGED', invitation_id, version from read_current_invitation;

create function read_availability_require_effect() returns trigger language plpgsql
set search_path to pg_catalog, "${expectedSchema}" as $$
declare
  valid boolean;
begin
  if new.logical_version is null then
    select exists (
      select from read_availability_effects
      where family = new.family and projection_key = new.projection_key and logical_version is null
    ) into valid;
  else
    select exists (
      select from read_availability_effects
      where family = new.family and projection_key = new.projection_key
        and logical_version = new.logical_version
    ) into valid;
  end if;
  if not valid then
    raise exception 'Availability lacks its READ effect for %', new.family using errcode = '23514';
  end if;
  return null;
end $$;

create trigger read_availability_counter_step before update or delete on read_availability_counter
  for each row execute function read_availability_counter_step();
create constraint trigger read_availability_counter_position after update on read_availability_counter
  deferrable initially deferred for each row execute function read_availability_require_position();
create trigger read_availability_event_position before insert on read_availability_event
  for each row execute function read_availability_position();
create constraint trigger read_availability_event_effect after insert on read_availability_event
  deferrable initially deferred for each row execute function read_availability_require_effect();
create trigger read_current_availability_position before insert or update on read_current_availability
  for each row execute function read_availability_position();
create constraint trigger read_current_availability_effect after insert or update on read_current_availability
  deferrable initially deferred for each row execute function read_availability_require_effect();
create trigger read_availability_event_immutable before update or delete on read_availability_event
  for each row execute function read_subobject_immutable();
create trigger read_current_availability_no_delete before delete on read_current_availability
  for each row execute function read_subobject_immutable();

-- Install the same pair of indexed coherence checks on each projection with the family-specific key.
do $$
declare
  effect record;
  relation text;
  trigger_name name;
begin
  for effect in select * from (values
    ('read_pot_version', 'POT_VERSION_AVAILABLE', 'pot_id', 'pot_version', false),
    ('read_pot_balance', 'POT_BALANCE_AVAILABLE', 'pot_id', 'pot_version', false),
    ('read_command_result', 'COMMAND_RESULT_AVAILABLE', 'request_id', '', false),
    ('read_identity_request_result', 'IDENTITY_REQUEST_RESULT_AVAILABLE', 'request_id', '', false),
    ('read_current_pot', 'CURRENT_POT_CHANGED', 'pot_id', 'pot_version', true),
    ('read_pot_visibility_state', 'POT_VISIBILITY_CHANGED', 'pot_id', 'materialized_version', true),
    ('read_current_binding', 'CURRENT_BINDING_CHANGED', 'eui_id', 'eui_version', true),
    ('read_current_invitation', 'CURRENT_INVITATION_CHANGED', 'invitation_id', 'version', true)
  ) spec(relation, family, key_column, version_column, is_current)
  loop
    if effect.is_current then
      execute format('create trigger read_current_effect_step before update on %I
        for each row execute function read_current_effect_step(%L, %L, %L)',
        effect.relation, effect.family, effect.key_column, effect.version_column);
      execute format('alter table %I enable always trigger read_current_effect_step', effect.relation);
      execute format('create constraint trigger read_effect_availability after insert or update on %I
        deferrable initially deferred for each row execute function read_effect_require_availability(%L, %L, %L, %L)',
        effect.relation, effect.family, effect.key_column, effect.version_column, 'current');
    else
      execute format('create constraint trigger read_effect_availability after insert on %I
        deferrable initially deferred for each row execute function read_effect_require_availability(%L, %L, %L)',
        effect.relation, effect.family, effect.key_column, effect.version_column);
    end if;
    execute format('alter table %I enable always trigger read_effect_availability', effect.relation);
  end loop;

  foreach relation in array array['read_availability_counter', 'read_availability_event', 'read_current_availability']
  loop
    execute format('create trigger read_availability_no_truncate before truncate on %I
      for each statement execute function read_subobject_immutable()', relation);
    for trigger_name in
      select tgname from pg_trigger where tgrelid = relation::regclass and not tgisinternal
    loop
      execute format('alter table %I enable always trigger %I', relation, trigger_name);
    end loop;
  end loop;
end $$;

-- A STABLE function uses the calling statement's snapshot for the counter and page.
create function read_current_availability_page(requested_family text, cursor_position bigint, page_size integer)
returns table (head bigint, next_cursor bigint, changes jsonb)
language plpgsql stable
set search_path to pg_catalog, "${expectedSchema}" as $$
declare
  family_head bigint;
begin
  if requested_family is null or requested_family not in (
    'CURRENT_POT_CHANGED', 'POT_VISIBILITY_CHANGED', 'CURRENT_BINDING_CHANGED', 'CURRENT_INVITATION_CHANGED'
  ) or cursor_position is null or cursor_position < 0 or page_size is null or page_size < 1 then
    raise exception 'Invalid current availability page' using errcode = '22023';
  end if;

  select position into strict family_head from read_availability_counter where family = requested_family;
  if cursor_position > family_head then
    raise exception 'Current availability cursor is beyond the family head' using errcode = '22023';
  end if;

  return query
    select family_head, case when count(*) = page_size then max(page.position) else family_head end,
           coalesce(jsonb_agg(to_jsonb(page) order by page.position), '[]'::jsonb)
    from (
      select projection_key, logical_version, position
      from read_current_availability
      where family = requested_family and position > cursor_position and position <= family_head
      order by position limit page_size
    ) page;
end $$;

-- Same convergence predicate for runtime and operations readiness, in the caller's READ snapshot.
create function read_availability_valid() returns boolean language sql stable
set search_path to pg_catalog, "${expectedSchema}" as $$
  with families(family, historical) as (values
    ('POT_VERSION_AVAILABLE', true), ('POT_BALANCE_AVAILABLE', true),
    ('COMMAND_RESULT_AVAILABLE', true), ('IDENTITY_REQUEST_RESULT_AVAILABLE', true),
    ('CURRENT_POT_CHANGED', false), ('POT_VISIBILITY_CHANGED', false),
    ('CURRENT_BINDING_CHANGED', false), ('CURRENT_INVITATION_CHANGED', false)
  ),
  historical as (
    select family, count(*) as size, min(position) as first, max(position) as last
    from read_availability_event group by family
  ),
  current_markers as (
    select family, max(position) as last from read_current_availability group by family
  ),
  published as materialized (
    select family, projection_key, logical_version from read_availability_event
    union all select family, projection_key, logical_version from read_current_availability
  )
  select
    (select count(*) from read_availability_counter) = 8
    and not exists (
      select from families
      left join read_availability_counter counter using (family)
      left join historical using (family)
      left join current_markers using (family)
      where counter.family is null
         or (families.historical and (
           coalesce(historical.size, 0) <> counter.position
           or (counter.position > 0 and (historical.first <> 1 or historical.last <> counter.position))
         ))
         or (not families.historical and coalesce(current_markers.last, 0) <> counter.position)
    )
    and not exists (
      select family, projection_key, logical_version from read_availability_effects
      except all
      select family, projection_key, logical_version from published
    )
    and not exists (
      select family, projection_key, logical_version from published
      except all
      select family, projection_key, logical_version from read_availability_effects
    )
    and not exists (
      select from read_pot_balance balance join read_pot_version version using (pot_id, pot_version)
      where version.deleted
    );
$$;

-- Check the deployed catalog without scanning the projection stock at worker startup.
create function read_availability_schema_valid() returns boolean language sql stable
set search_path to pg_catalog, "${expectedSchema}" as $$
  with expected_triggers(relation, trigger_name) as (
    values
      ('read_availability_counter', 'read_availability_counter_step'),
      ('read_availability_counter', 'read_availability_counter_position'),
      ('read_availability_counter', 'read_availability_no_truncate'),
      ('read_availability_event', 'read_availability_event_position'),
      ('read_availability_event', 'read_availability_event_effect'),
      ('read_availability_event', 'read_availability_event_immutable'),
      ('read_availability_event', 'read_availability_no_truncate'),
      ('read_current_availability', 'read_current_availability_position'),
      ('read_current_availability', 'read_current_availability_effect'),
      ('read_current_availability', 'read_current_availability_no_delete'),
      ('read_current_availability', 'read_availability_no_truncate'),
      ('read_pot_version', 'read_effect_availability'),
      ('read_pot_balance', 'read_effect_availability'),
      ('read_command_result', 'read_effect_availability'),
      ('read_identity_request_result', 'read_effect_availability'),
      ('read_current_pot', 'read_effect_availability'),
      ('read_pot_visibility_state', 'read_effect_availability'),
      ('read_current_binding', 'read_effect_availability'),
      ('read_current_invitation', 'read_effect_availability'),
      ('read_current_pot', 'read_current_effect_step'),
      ('read_pot_visibility_state', 'read_current_effect_step'),
      ('read_current_binding', 'read_current_effect_step'),
      ('read_current_invitation', 'read_current_effect_step')
  )
  select not exists (
    select relation, trigger_name from expected_triggers
    except
    select c.relname::text, t.tgname::text
    from pg_trigger t join pg_class c on c.oid = t.tgrelid
    join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = '${expectedSchema}' and t.tgenabled = 'A'
  )
  and (select count(*) from read_availability_counter) = 8
  and exists (
    select from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = '${expectedSchema}' and c.relname = 'read_availability_effects'
      and c.relkind = 'v' and c.relowner = n.nspowner
      and 'security_invoker=true' = any (c.reloptions)
  )
  and to_regprocedure('${expectedSchema}.read_current_availability_page(text,bigint,integer)') is not null
  and to_regprocedure('${expectedSchema}.read_availability_valid()') is not null;
$$;

revoke all on read_availability_counter, read_availability_event, read_current_availability,
  read_availability_effects from public, "${expectedRuntime}";
revoke all on function read_availability_counter_step(), read_availability_position(),
  read_availability_require_position(), read_effect_require_availability(), read_current_effect_step(),
  read_availability_require_effect(), read_current_availability_page(text, bigint, integer),
  read_availability_valid(), read_availability_schema_valid() from public, "${expectedRuntime}";
grant select on read_availability_counter, read_availability_event, read_current_availability,
  read_availability_effects to "${expectedRuntime}";
grant update (position) on read_availability_counter to "${expectedRuntime}";
grant insert on read_availability_event, read_current_availability to "${expectedRuntime}";
grant update (logical_version, position) on read_current_availability to "${expectedRuntime}";
grant execute on function read_availability_valid(), read_availability_schema_valid() to "${expectedRuntime}";
