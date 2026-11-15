-- CURRENT additive post-audit correction. V1..V7 remain unchanged.
-- SECURITY INVOKER throughout. Only projections change: all integrity predicates survive.
-- Request tokens/payload/context and result rejection are never used by these checks.
create or replace function v3_require_command_unit() returns trigger language plpgsql as $$
declare c v3_command%rowtype; r v3_command_result%rowtype;
begin
    select request_id,status,pot_id into c.request_id,c.status,c.pot_id from v3_command where request_id=new.request_id;
    select request_id,outcome,resulting_pot_version into r.request_id,r.outcome,r.resulting_pot_version from v3_command_result where request_id=c.request_id;
    if c.status='PENDING' then
        if r.request_id is not null then raise exception 'Pending Command has result' using errcode='23514'; end if;
        return new;
    end if;
    if r.request_id is null or exists(select 1 from consumption_slot s where s.consumption_id='98f7268d-d340-581c-b83d-010926223e14'::uuid and s.consumable_id=c.request_id)
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
create or replace function v3_identity_check_unit(rid uuid, positioned boolean) returns void language plpgsql as $$
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
