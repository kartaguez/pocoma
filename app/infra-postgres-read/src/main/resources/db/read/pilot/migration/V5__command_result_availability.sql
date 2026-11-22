-- Explicit C3 pilot. Apply alongside the canonical READ migrations only in a fresh pilot schema.
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
    raise exception 'READ availability pilot requires V4' using errcode = '23514';
  end if;
  if exists (select from read_pot_version)
     or exists (select from read_pot_balance)
     or exists (select from read_pot_visibility_state)
     or exists (select from read_user_pot)
     or exists (select from read_current_binding)
     or exists (select from read_command_result)
     or exists (select from read_identity_request_result)
     or exists (select from read_current_invitation)
     or exists (select from consumption_slot)
     or exists (select from discovery_checkpoint where position > 0) then
    raise exception 'READ availability pilot requires no projections, slots or advanced checkpoints'
      using errcode = '23514';
  end if;
end $$;

create table read_availability_counter (
  family text primary key check (family = 'COMMAND_RESULT_AVAILABLE'),
  position bigint not null check (position >= 0),
  writer_xid xid8,
  check ((position = 0) = (writer_xid is null))
);
insert into read_availability_counter (family, position) values ('COMMAND_RESULT_AVAILABLE', 0);

create table read_availability_event (
  family text not null check (family = 'COMMAND_RESULT_AVAILABLE'),
  position bigint not null check (position > 0),
  event_id uuid not null unique,
  projection_key uuid not null,
  primary key (family, position),
  unique (family, projection_key),
  check (event_id <> projection_key)
);

create function read_availability_counter_step() returns trigger language plpgsql
set search_path to pg_catalog, "${expectedSchema}" as $$
begin
  if tg_op <> 'UPDATE' then
    raise exception 'Cannot remove an availability counter' using errcode = '23514';
  end if;
  if new.family <> old.family
     or new.position <> old.position + 1
     or old.writer_xid is not distinct from pg_current_xact_id() then
    raise exception 'Invalid availability counter step' using errcode = '23514';
  end if;
  new.writer_xid := pg_current_xact_id();
  return new;
end $$;

create function read_availability_event_position() returns trigger language plpgsql
set search_path to pg_catalog, "${expectedSchema}" as $$
begin
  if not exists (
    select from read_availability_counter
    where family = new.family and position = new.position and writer_xid = pg_current_xact_id()
  ) then
    raise exception 'Position not allocated by this transaction' using errcode = '23514';
  end if;
  return new;
end $$;

create function read_availability_require_event() returns trigger language plpgsql
set search_path to pg_catalog, "${expectedSchema}" as $$
begin
  if not exists (
    select from read_availability_event where family = new.family and position = new.position
  ) then
    raise exception 'Availability allocation lacks its event' using errcode = '23514';
  end if;
  return null;
end $$;

create function read_availability_require_command_result() returns trigger language plpgsql
set search_path to pg_catalog, "${expectedSchema}" as $$
begin
  if not exists (select from read_command_result where request_id = new.projection_key) then
    raise exception 'Availability event lacks its CommandResult' using errcode = '23514';
  end if;
  return null;
end $$;

create function read_command_result_require_availability() returns trigger language plpgsql
set search_path to pg_catalog, "${expectedSchema}" as $$
begin
  if not exists (
    select from read_availability_event
    where family = 'COMMAND_RESULT_AVAILABLE' and projection_key = new.request_id
  ) then
    raise exception 'CommandResult lacks its availability event' using errcode = '23514';
  end if;
  return null;
end $$;

create trigger read_availability_counter_step before update or delete on read_availability_counter
  for each row execute function read_availability_counter_step();
create trigger read_availability_counter_no_truncate before truncate on read_availability_counter
  for each statement execute function read_subobject_immutable();
create trigger read_availability_event_position before insert on read_availability_event
  for each row execute function read_availability_event_position();
create trigger read_availability_event_immutable before update or delete on read_availability_event
  for each row execute function read_subobject_immutable();
create trigger read_availability_event_no_truncate before truncate on read_availability_event
  for each statement execute function read_subobject_immutable();

create constraint trigger read_availability_counter_event after update on read_availability_counter
  deferrable initially deferred for each row execute function read_availability_require_event();
create constraint trigger read_availability_event_command_result after insert on read_availability_event
  deferrable initially deferred for each row execute function read_availability_require_command_result();
create constraint trigger read_command_result_availability after insert on read_command_result
  deferrable initially deferred for each row execute function read_command_result_require_availability();

alter table read_availability_counter enable always trigger read_availability_counter_step;
alter table read_availability_counter enable always trigger read_availability_counter_no_truncate;
alter table read_availability_counter enable always trigger read_availability_counter_event;
alter table read_availability_event enable always trigger read_availability_event_position;
alter table read_availability_event enable always trigger read_availability_event_immutable;
alter table read_availability_event enable always trigger read_availability_event_no_truncate;
alter table read_availability_event enable always trigger read_availability_event_command_result;
alter table read_command_result enable always trigger read_command_result_availability;

revoke all on read_availability_counter, read_availability_event from public, "${expectedRuntime}";
revoke all on function read_availability_counter_step(), read_availability_event_position(),
  read_availability_require_event(), read_availability_require_command_result(),
  read_command_result_require_availability() from public, "${expectedRuntime}";
grant select on read_availability_counter, read_availability_event to "${expectedRuntime}";
grant update (position) on read_availability_counter to "${expectedRuntime}";
grant insert on read_availability_event to "${expectedRuntime}";
