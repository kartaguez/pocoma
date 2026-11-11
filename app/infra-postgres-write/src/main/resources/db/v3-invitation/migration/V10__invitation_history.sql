-- Additive CURRENT WRITE extension, explicitly selected for Invitation activation.
do $$ begin
 if current_database()<>'${expectedDatabase}' or current_schema()<>'${expectedSchema}' or current_user<>'${expectedAdmin}' or current_schema()='public' then
  raise exception 'Wrong Invitation migration target' using errcode='23514';
 end if;
end $$;
create table v3_invitation (
 invitation_id uuid not null, version bigint not null check(version=1),
 creation_request_id uuid not null unique references v3_command(request_id),
 creator_issuer text collate "C" not null, creator_subject text collate "C" not null,
 pot_id uuid not null references v3_pot_identity(pot_id), expires_at timestamptz not null,
 token_hash text not null unique check(token_hash ~ '^[0-9a-f]{64}$'),
 protected_link text not null check(protected_link like 'v1.%'),
 writer_xid xid8 not null default pg_current_xact_id(),
 primary key(invitation_id,version)
);
create table v3_invitation_event (
 event_id uuid primary key, subject_type text not null default 'INVITATION' check(subject_type='INVITATION'),
 invitation_id uuid not null, version bigint not null, unique(invitation_id,version),
 foreign key(invitation_id,version) references v3_invitation(invitation_id,version),
 recorded_at timestamptz not null
);
create trigger v3_invitation_immutable before update or delete on v3_invitation for each row execute function v3_immutable_execution_row();
create trigger v3_invitation_event_immutable before update or delete on v3_invitation_event for each row execute function v3_immutable_execution_row();
alter table v3_command_result drop constraint v3_command_result_outcome_check;
alter table v3_command_result drop constraint v3_command_result_check;
alter table v3_command_result add constraint v3_command_result_outcome_check check(outcome in ('CHANGED','UNCHANGED','REJECTED','INVITATION_CREATED'));
alter table v3_command_result add constraint v3_command_result_shape check(
 (outcome in ('CHANGED','UNCHANGED') and rejection is null and resulting_pot_version is not null)
 or (outcome='REJECTED' and rejection is not null and resulting_pot_version is null)
 or (outcome='INVITATION_CREATED' and rejection is null and resulting_pot_version is null));
alter table v3_event_counter drop constraint v3_event_counter_family_check;
alter table v3_event_counter add constraint v3_event_counter_family_check check(family in ('POT','COMMAND_COMPLETION','IDENTITY','IDENTITY_COMPLETION','INVITATION'));
insert into v3_event_counter values ('INVITATION',0);
create or replace function v3_require_command_unit() returns trigger language plpgsql as $$
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
revoke all on v3_invitation,v3_invitation_event from public,"${expectedRuntime}";
grant select,insert on v3_invitation,v3_invitation_event to "${expectedRuntime}";

-- Final clock check executes at physical commit, after any publication-counter waits.
-- A deterministic rollback is re-evaluated by the existing Command collision path.
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
create trigger v3_invitation_no_truncate before truncate on v3_invitation for each statement execute function v3_immutable_execution_row();
create trigger v3_invitation_event_no_truncate before truncate on v3_invitation_event for each statement execute function v3_immutable_execution_row();
