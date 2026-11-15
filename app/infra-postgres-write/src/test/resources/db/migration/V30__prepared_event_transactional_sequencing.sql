-- Additive, dormant installation. No activation, backfill or runtime membership grant.
-- Migration/maintenance owns these objects; runtime logins must NOT own them or
-- inherit the migration role. Grant pocoma_event_runtime to a separately managed
-- runtime login when provisioning it. Existing CURRENT grants are unchanged.
do $$ begin
    if not exists (select 1 from pg_roles where rolname = 'pocoma_event_runtime') then
        create role pocoma_event_runtime nologin;
    end if;
    if exists (select 1 from pg_roles where rolname = 'pocoma_event_runtime'
               and (rolsuper or rolcreaterole or rolcreatedb or rolreplication or rolbypassrls)) then
        raise exception 'pocoma_event_runtime must be an unprivileged runtime group';
    end if;
end $$;

create table event_sequencing_protocol (
    singleton boolean primary key default true check (singleton),
    protocol_version integer not null check (protocol_version = 1),
    mode text not null check (mode in ('PREPARED', 'ACTIVE'))
);
insert into event_sequencing_protocol values (true, 1, 'PREPARED');

create table event_sequence_authorities (
    family text primary key check (family in
        ('BUSINESS_EVENT', 'COMMAND_TERMINAL_EVENT', 'BINDING_FACT', 'USER_CREATED_FACT')),
    last_position bigint not null check (last_position >= 0)
);
insert into event_sequence_authorities values
    ('BUSINESS_EVENT',0), ('COMMAND_TERMINAL_EVENT',0), ('BINDING_FACT',0), ('USER_CREATED_FACT',0);

create function reject_event_position_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'Event positions are immutable' using errcode = '23000';
end $$;

create table business_event_positions (
    event_id uuid not null unique references business_event_outbox(id) on delete restrict,
    position bigint primary key check (position > 0)
);
create trigger business_event_positions_immutable before update or delete on business_event_positions
    for each row execute function reject_event_position_mutation();

create table command_terminal_event_positions (
    event_id uuid not null unique references command_terminal_events(event_id) on delete restrict,
    position bigint primary key check (position > 0)
);
create trigger command_terminal_event_positions_immutable before update or delete on command_terminal_event_positions
    for each row execute function reject_event_position_mutation();

create table binding_fact_positions (
    event_id uuid not null unique references external_identity_binding_facts(event_id) on delete restrict,
    position bigint primary key check (position > 0)
);
create trigger binding_fact_positions_immutable before update or delete on binding_fact_positions
    for each row execute function reject_event_position_mutation();

create table user_created_fact_positions (
    event_id uuid not null unique references user_created_facts(event_id) on delete restrict,
    position bigint primary key check (position > 0)
);
create trigger user_created_fact_positions_immutable before update or delete on user_created_fact_positions
    for each row execute function reject_event_position_mutation();

create view event_position as
select 'BUSINESS_EVENT'::text as family, event_id, position from business_event_positions
union all
select 'COMMAND_TERMINAL_EVENT'::text as family, event_id, position from command_terminal_event_positions
union all
select 'BINDING_FACT'::text as family, event_id, position from binding_fact_positions
union all
select 'USER_CREATED_FACT'::text as family, event_id, position from user_created_fact_positions;

-- Only indexed existence probes on the inserted UUID; no source read/lock, E
-- access, global scan or DML. FK in the other direction remains immediate.
create function check_business_event_positions_complete() returns trigger
language plpgsql security definer set search_path = pg_catalog as $$
begin
    if (select mode = 'ACTIVE' from event_sequencing_protocol where singleton) then
        if not exists (select 1 from business_event_positions where event_id = NEW.id) then
            raise exception 'Missing BUSINESS_EVENT position for %', NEW.id using errcode = '23514';
        end if;
    end if;
    return null;
end $$;
create constraint trigger business_event_positions_complete after insert on business_event_outbox
    deferrable initially deferred for each row execute function check_business_event_positions_complete();

create function check_command_terminal_event_positions_complete() returns trigger
language plpgsql security definer set search_path = pg_catalog as $$
begin
    if (select mode = 'ACTIVE' from event_sequencing_protocol where singleton) then
        if not exists (select 1 from command_terminal_event_positions where event_id = NEW.event_id) then
            raise exception 'Missing COMMAND_TERMINAL_EVENT position for %', NEW.event_id using errcode = '23514';
        end if;
    end if;
    return null;
end $$;
create constraint trigger command_terminal_event_positions_complete after insert on command_terminal_events
    deferrable initially deferred for each row execute function check_command_terminal_event_positions_complete();

create function check_binding_fact_positions_complete() returns trigger
language plpgsql security definer set search_path = pg_catalog as $$
begin
    if (select mode = 'ACTIVE' from event_sequencing_protocol where singleton) then
        if not exists (select 1 from binding_fact_positions where event_id = NEW.event_id) then
            raise exception 'Missing BINDING_FACT position for %', NEW.event_id using errcode = '23514';
        end if;
    end if;
    return null;
end $$;
create constraint trigger binding_fact_positions_complete after insert on external_identity_binding_facts
    deferrable initially deferred for each row execute function check_binding_fact_positions_complete();

create function check_user_created_fact_positions_complete() returns trigger
language plpgsql security definer set search_path = pg_catalog as $$
begin
    if (select mode = 'ACTIVE' from event_sequencing_protocol where singleton) then
        if not exists (select 1 from user_created_fact_positions where event_id = NEW.event_id) then
            raise exception 'Missing USER_CREATED_FACT position for %', NEW.event_id using errcode = '23514';
        end if;
    end if;
    return null;
end $$;
create constraint trigger user_created_fact_positions_complete after insert on user_created_facts
    deferrable initially deferred for each row execute function check_user_created_fact_positions_complete();

-- Narrow technical privilege: runtime cannot freely UPDATE/reset authorities.
-- This function executes the single transactional UPDATE RETURNING for a block.
-- It neither owns a transaction nor inserts associations; caller errors roll back all.
create function reserve_event_positions(requested_family text, block_size bigint) returns bigint
language plpgsql security definer set search_path = pg_catalog as $$
declare last_reserved bigint;
begin
    if block_size is null or block_size <= 0 or requested_family is null
       or requested_family not in ('BUSINESS_EVENT','COMMAND_TERMINAL_EVENT','BINDING_FACT','USER_CREATED_FACT') then
        raise exception 'Invalid event block' using errcode = '22023';
    end if;
    if not exists (select 1 from event_sequencing_protocol
                   where singleton and protocol_version = 1 and mode = 'ACTIVE') then
        raise exception 'Event sequencing is not ACTIVE' using errcode = '55000';
    end if;
    update event_sequence_authorities
       set last_position = last_position + block_size
     where family = requested_family and last_position <= 9223372036854775807 - block_size
     returning last_position into last_reserved;
    if not found then
        raise exception 'Event authority missing or capacity exceeded: %', requested_family using errcode = '22003';
    end if;
    return last_reserved;
end $$;

revoke all on event_sequencing_protocol, event_sequence_authorities, event_position from public, pocoma_event_runtime;
-- Pin SECURITY DEFINER resolution to the migration's actual trusted schema,
-- with pg_temp last. Also supports isolated CURRENT schemas, never a caller path.
do $$
declare installed_schema text := current_schema(); routine text;
begin
    execute format('grant usage on schema %I to pocoma_event_runtime', installed_schema);
    execute format('alter function %I.reserve_event_positions(text,bigint) set search_path = pg_catalog, %I, pg_temp', installed_schema, installed_schema);
    foreach routine in array array['check_business_event_positions_complete',
        'check_command_terminal_event_positions_complete', 'check_binding_fact_positions_complete',
        'check_user_created_fact_positions_complete'] loop
        execute format('alter function %I.%I() set search_path = pg_catalog, %I, pg_temp', installed_schema, routine, installed_schema);
    end loop;
end $$;
grant select on event_sequencing_protocol, event_position to pocoma_event_runtime;
revoke all on function reserve_event_positions(text,bigint) from public;
grant execute on function reserve_event_positions(text,bigint) to pocoma_event_runtime;
revoke all on function reject_event_position_mutation() from public;
revoke all on business_event_positions from public, pocoma_event_runtime;
grant select, insert on business_event_positions to pocoma_event_runtime;
revoke all on function check_business_event_positions_complete() from public;
revoke all on command_terminal_event_positions from public, pocoma_event_runtime;
grant select, insert on command_terminal_event_positions to pocoma_event_runtime;
revoke all on function check_command_terminal_event_positions_complete() from public;
revoke all on binding_fact_positions from public, pocoma_event_runtime;
grant select, insert on binding_fact_positions to pocoma_event_runtime;
revoke all on function check_binding_fact_positions_complete() from public;
revoke all on user_created_fact_positions from public, pocoma_event_runtime;
grant select, insert on user_created_fact_positions to pocoma_event_runtime;
revoke all on function check_user_created_fact_positions_complete() from public;
