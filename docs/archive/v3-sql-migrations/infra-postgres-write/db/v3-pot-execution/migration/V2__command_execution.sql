-- B's immutable version history remains authoritative. This cursor is the global CAS authority.
alter table v3_pot_identity add column current_version bigint not null default 0 check(current_version>=0);
update v3_pot_identity p set current_version=(select max(version) from v3_pot_version v where v.pot_id=p.pot_id);
create function v3_advance_pot_cas() returns trigger language plpgsql as $$
begin
    update v3_pot_identity set current_version=new.version
        where pot_id=new.pot_id and current_version=new.version-1;
    if not found then raise exception 'Pot global CAS collision' using errcode='40001'; end if;
    return new;
end $$;
create trigger v3_pot_cas before insert on v3_pot_version for each row execute function v3_advance_pot_cas();

create table v3_command (
    request_id uuid primary key, requester_issuer text not null, requester_subject text not null,
    binding_token text not null, pot_id uuid not null, observed_version bigint not null check(observed_version>=0),
    operation text not null, payload jsonb not null, status text not null default 'PENDING' check(status in ('PENDING','TERMINAL'))
);
create function v3_command_immutable() returns trigger language plpgsql as $$
begin
    if tg_op='DELETE' or old.status='TERMINAL' or
       (to_jsonb(new)-'status') is distinct from (to_jsonb(old)-'status') then
        raise exception 'Immutable admitted Command' using errcode='23514';
    end if;
    return new;
end $$;
create trigger v3_command_intention before update or delete on v3_command for each row execute function v3_command_immutable();
create table v3_command_slot (
    request_id uuid primary key references v3_command(request_id), claim_token uuid, claimed_until timestamptz,
    retry_after timestamptz, attempt_count integer not null default 0 check(attempt_count>=0),
    state text not null default 'AVAILABLE' check(state in ('AVAILABLE','QUARANTINED')),
    check((claim_token is null)=(claimed_until is null)),
    check(state<>'QUARANTINED' or (claim_token is null and retry_after is null))
);
create table v3_command_result (
    request_id uuid primary key references v3_command(request_id), outcome text not null check(outcome in ('CHANGED','UNCHANGED','REJECTED')),
    resulting_pot_version bigint check(resulting_pot_version>=1), rejection text,
    check(case when outcome='REJECTED' then rejection is not null and resulting_pot_version is null
          else rejection is null and resulting_pot_version is not null end)
);
create table v3_pot_event (
    event_id uuid primary key, subject_type text not null default 'POT' check(subject_type='POT'),
    pot_id uuid not null, subject_version bigint not null,
    request_id uuid not null unique references v3_command(request_id), recorded_at timestamptz not null,
    unique(pot_id,subject_version), foreign key(pot_id,subject_version) references v3_pot_version(pot_id,version)
);
create table v3_pot_event_component (
    event_id uuid not null references v3_pot_event(event_id), kind text not null,
    object_id uuid not null, local_version bigint not null check(local_version>=1),
    primary key(event_id,kind,object_id)
);
create table v3_command_completion_event (
    event_id uuid primary key, subject_type text not null default 'COMMAND' check(subject_type='COMMAND'),
    subject_id uuid not null unique references v3_command_result(request_id), recorded_at timestamptz not null
);
create table v3_event_counter (family text primary key check(family in ('POT','COMMAND_COMPLETION')), position bigint not null check(position>=0));
insert into v3_event_counter values ('POT',0),('COMMAND_COMPLETION',0);
create table v3_event_position (
    family text not null references v3_event_counter(family), position bigint not null check(position>=1),
    event_id uuid not null unique, primary key(family,position)
);
create function v3_immutable_execution_row() returns trigger language plpgsql as $$
begin raise exception 'Immutable Command result/publication' using errcode='23514'; end $$;
create trigger v3_result_immutable before update or delete on v3_command_result for each row execute function v3_immutable_execution_row();
create trigger v3_pot_event_immutable before update or delete on v3_pot_event for each row execute function v3_immutable_execution_row();
create trigger v3_component_event_immutable before update or delete on v3_pot_event_component for each row execute function v3_immutable_execution_row();
create trigger v3_completion_immutable before update or delete on v3_command_completion_event for each row execute function v3_immutable_execution_row();
create trigger v3_position_immutable before update or delete on v3_event_position for each row execute function v3_immutable_execution_row();

-- Executed at physical commit, read-only: no new business locks after sequence locks.
create function v3_require_command_unit() returns trigger language plpgsql as $$
declare c v3_command%rowtype; r v3_command_result%rowtype;
begin
    select * into c from v3_command where request_id=new.request_id;
    select * into r from v3_command_result where request_id=c.request_id;
    if c.status='PENDING' then
        if r.request_id is not null then raise exception 'Pending Command has result' using errcode='23514'; end if;
        return new;
    end if;
    if r.request_id is null or exists(select 1 from v3_command_slot where request_id=c.request_id)
       or not exists(select 1 from v3_command_completion_event e join v3_event_position p on p.event_id=e.event_id and p.family='COMMAND_COMPLETION' where e.subject_id=c.request_id) then
        raise exception 'Incomplete terminal Command unit' using errcode='23514';
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
