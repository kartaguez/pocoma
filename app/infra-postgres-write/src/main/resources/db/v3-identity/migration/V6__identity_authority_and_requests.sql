-- Additive CURRENT V3 WRITE. No runtime activation, grants, or changes to published V1..V5.
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
        and admission_context->>'origin' in ('CONTROLLED_INTERNAL','EXTERNAL_AUTHORIZATION_SERVER')
        and jsonb_typeof(admission_context->'validator')='string' and length(admission_context->>'validator')>0
        and jsonb_typeof(admission_context->'contractVersion')='string' and length(admission_context->>'contractVersion')>0
        and jsonb_typeof(admission_context->'intentionFingerprint')='string' and length(admission_context->>'intentionFingerprint')>0
        and jsonb_typeof(admission_context->'grantOrCorrelationReference')='string' and length(admission_context->>'grantOrCorrelationReference')>0
        and jsonb_typeof(admission_context->'validatedAt')='string' and length(admission_context->>'validatedAt')>0
        and admission_context->'initiator'=jsonb_build_object('issuer',requester_issuer,'subject',requester_subject)
        and admission_context->'target'=jsonb_build_object('issuer',target_issuer,'subject',target_subject)) is true)
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

alter table v3_event_counter drop constraint v3_event_counter_family_check;
alter table v3_event_counter add constraint v3_event_counter_family_check
    check(family in ('POT','COMMAND_COMPLETION','IDENTITY','IDENTITY_COMPLETION'));
insert into v3_event_counter values ('IDENTITY',0),('IDENTITY_COMPLETION',0);

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
-- v3_position_immutable already protects every family; do not replace it.

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

-- Prefix only: adversarial SQL must serialize too. This never advances the cursor for the writer.
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

-- Structural constraints are forced by name before publication closure. Final table state, not NEW.
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

-- Closed call graph: this function and all terminal wrappers below use plain SELECT only.
-- positioned=false is a prefix preflight: opens every terminal relation before sequence locks.
create function v3_identity_check_unit(rid uuid, positioned boolean) returns void language plpgsql as $$
declare c v3_identity_request%rowtype; r v3_identity_request_result%rowtype;
    v v3_eui_version%rowtype; a v3_eui_authority%rowtype; u v3_pocoma_user%rowtype;
    completion v3_identity_completion_event%rowtype; eu v3_identity_event%rowtype; ee v3_identity_event%rowtype;
    users_count bigint; versions_count bigint; events_count bigint;
    user_position bigint; eui_position bigint;
begin
    select * into c from v3_identity_request where request_id=rid;
    select * into r from v3_identity_request_result where request_id=rid;
    select count(*) into users_count from v3_pocoma_user where created_request_id=rid;
    select count(*) into versions_count from v3_eui_version where request_id=rid;
    select count(*) into events_count from v3_identity_event where request_id=rid;
    select * into completion from v3_identity_completion_event where subject_id=rid;
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

-- No polymorphic deferred FK. These indexed SELECTs do not acquire row protections.
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
-- Crucial: a POT/COMMAND suffix must never open Identity business relations at commit.
-- Identity event/unit triggers still reject a wrong-family position via the missing exact position.
create constraint trigger v3_identity_position_unit after insert on v3_event_position
    deferrable initially deferred for each row when (new.family in ('IDENTITY','IDENTITY_COMPLETION'))
    execute function v3_identity_require_position();

-- Suffix-only checks: counters/positions, no business relation or lock.
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
