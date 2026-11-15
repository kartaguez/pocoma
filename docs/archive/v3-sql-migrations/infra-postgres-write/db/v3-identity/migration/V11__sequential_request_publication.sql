do $$
declare
    r record;
begin
    if current_database() <> '${expectedDatabase}'
       or current_schema() <> '${expectedSchema}'
       or current_schema() = 'public'
       or current_user <> '${expectedAdmin}' then
        raise exception 'Invalid V11 sequential request publication target' using errcode = '23514';
    end if;
    if to_regclass('v3_command') is null
       or to_regclass('v3_identity_request') is null
       or to_regclass('consumption_slot') is null
       or to_regclass('discovery_checkpoint') is null
       or to_regclass('consumption_routing_contract') is null then
        raise exception 'Missing V2/V5/V6 prerequisite foundations for V11' using errcode = '23514';
    end if;
    if exists (select 1 from v3_command) or exists (select 1 from v3_identity_request) then
        raise exception 'Historical burnable rows present before V11 sequential publication install'
            using errcode = '23514';
    end if;
    if not exists (select 1 from pg_roles where rolname = '${expectedRuntime}') then
        raise exception 'Missing expected runtime role' using errcode = '23514';
    end if;
    create table v3_request_privilege_probe(id int primary key);
    for r in
        select rolname from pg_roles
        where pg_has_role('${expectedRuntime}', oid, 'member')
    loop
        if has_table_privilege(
            r.rolname,
            'v3_request_privilege_probe',
            'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'
        ) then
            raise exception 'Default write privilege inherited by runtime role' using errcode = '23514';
        end if;
    end loop;
    drop table v3_request_privilege_probe;
end $$;

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

create or replace function consumption_row_guard() returns trigger language plpgsql as $$
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

revoke all on table v3_request_counter, v3_request_position from public, "${expectedRuntime}";
