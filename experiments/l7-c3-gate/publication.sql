-- Only deployed inside the experimental READ fixture. The CommandResult pilot
-- keeps its own tables, production store, and unmodified V5 triggers.
create table c3_bench_counter (
  family text primary key,
  position bigint not null default 0 check (position >= 0),
  writer_xid xid8,
  check ((position = 0) = (writer_xid is null))
);
insert into c3_bench_counter(family) values
 ('IDENTITY_REQUEST_RESULT'), ('POT_VERSION'), ('POT_BALANCE');

create table c3_bench_event (
  family text not null,
  position bigint not null check (position > 0),
  event_id uuid not null unique,
  projection_key uuid not null,
  logical_version bigint,
  primary key (family, position),
  check (event_id <> projection_key),
  unique nulls not distinct (family, projection_key, logical_version),
  check ((family = 'IDENTITY_REQUEST_RESULT' and logical_version is null)
      or (family in ('POT_VERSION', 'POT_BALANCE') and logical_version is not null and logical_version > 0))
);

create function c3_bench_step() returns trigger language plpgsql as $$
begin
  if new.position <> old.position + 1
      or old.writer_xid is not distinct from pg_current_xact_id() then
    raise check_violation using message = 'Invalid experimental counter step';
  end if;
  new.writer_xid := pg_current_xact_id();
  return new;
end $$;

create function c3_bench_position() returns trigger language plpgsql as $$
begin
  if not exists (select from c3_bench_counter
      where family = new.family and position = new.position and writer_xid = pg_current_xact_id()) then
    raise check_violation using message = 'Experimental position was not allocated in this transaction';
  end if;
  return new;
end $$;

create function c3_bench_require_event() returns trigger language plpgsql as $$
begin
  if not exists (select from c3_bench_event where family = new.family and position = new.position) then
    raise check_violation using message = 'Experimental allocation lacks an event';
  end if;
  return null;
end $$;

create function c3_bench_require_effect() returns trigger language plpgsql as $$
begin
  if not (case new.family
    when 'IDENTITY_REQUEST_RESULT' then exists (
      select from read_identity_request_result where request_id = new.projection_key)
    when 'POT_VERSION' then exists (
      select from read_pot_version where pot_id = new.projection_key and pot_version = new.logical_version)
    when 'POT_BALANCE' then exists (
      select from read_pot_balance where pot_id = new.projection_key and pot_version = new.logical_version)
    else false end) then
    raise check_violation using message = 'Experimental event lacks its effect';
  end if;
  return null;
end $$;

create function c3_bench_require_publication() returns trigger language plpgsql as $$
declare
  effect_key uuid;
  effect_version bigint;
begin
  if tg_argv[0] = 'IDENTITY_REQUEST_RESULT' then
    effect_key := new.request_id;
    effect_version := null;
  else
    effect_key := new.pot_id;
    effect_version := new.pot_version;
  end if;
  if not exists (
    select from c3_bench_event where family = tg_argv[0] and projection_key = effect_key
      and logical_version is not distinct from effect_version
  ) then
    raise check_violation using message = 'Experimental effect lacks its event';
  end if;
  return null;
end $$;

create trigger c3_bench_step before update on c3_bench_counter
  for each row execute function c3_bench_step();
create trigger c3_bench_position before insert on c3_bench_event
  for each row execute function c3_bench_position();
create constraint trigger c3_bench_event after update on c3_bench_counter
  deferrable initially deferred for each row execute function c3_bench_require_event();
create constraint trigger c3_bench_effect after insert on c3_bench_event
  deferrable initially deferred for each row execute function c3_bench_require_effect();

create constraint trigger c3_bench_publication after insert on read_identity_request_result
  deferrable initially deferred for each row execute function c3_bench_require_publication('IDENTITY_REQUEST_RESULT');
create constraint trigger c3_bench_publication after insert on read_pot_version
  deferrable initially deferred for each row execute function c3_bench_require_publication('POT_VERSION');
create constraint trigger c3_bench_publication after insert on read_pot_balance
  deferrable initially deferred for each row execute function c3_bench_require_publication('POT_BALANCE');

grant select on c3_bench_counter, c3_bench_event to "__WORKER__";
grant update(position) on c3_bench_counter to "__WORKER__";
grant insert on c3_bench_event to "__WORKER__";
