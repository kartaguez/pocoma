-- Test fixture for archived Identity history. Execute with the fixture's schema administrator
-- before any worker can discover a pending request. The pending-only mode tests the cutover guard.
-- This file is never used by bootstrap, Compose or a Runtime.
create or replace function pg_temp.seed_historical_link(
    rid uuid, issuer text, subject text, target_issuer text, target_subject text,
    binding_token text, created_at timestamptz, admission_context jsonb
) returns void language sql as $$
    with request as (
        insert into v3_identity_request(request_id,requester_issuer,requester_subject,operation,
                                       binding_token,target_issuer,target_subject,admission_context,created_at)
        values($1,$2,$3,'LINK',$6,$4,$5,$8,$7) returning request_id
    ), next_position as (
        update v3_request_counter set position=position+1 where family='IDENTITY_REQUEST' returning position
    )
    insert into v3_request_position(family,position,request_id)
    select 'IDENTITY_REQUEST',next_position.position,request.request_id from next_position,request;
$$;

create or replace function pg_temp.complete_historical_link(rid uuid) returns void language plpgsql as $$
declare
    request v3_identity_request%rowtype;
    actor v3_eui_version%rowtype;
    target v3_eui_authority%rowtype;
    rejection text;
    eui_event uuid;
    completion uuid := gen_random_uuid();
    position_end bigint;
begin
    select * into strict request from v3_identity_request
      where request_id = rid and operation = 'LINK' and status = 'PENDING'
        and admission_context->>'origin' = 'CONTROLLED_INTERNAL';
    select v.* into actor from v3_eui_authority a join v3_eui_version v
      on v.eui_id = a.eui_id and v.version = a.current_version
      where a.issuer = request.requester_issuer and a.subject = request.requester_subject;
    if actor.binding_token is distinct from request.binding_token then
        rejection := 'INVALID_BINDING';
    else
        insert into v3_eui_authority(eui_id,issuer,subject)
          values(gen_random_uuid(),request.target_issuer,request.target_subject)
          on conflict(issuer,subject) do nothing;
        select * into strict target from v3_eui_authority
          where issuer = request.target_issuer and subject = request.target_subject for update;
        if exists(select 1 from v3_eui_version
                  where eui_id = target.eui_id and version = target.current_version and user_id is not null) then
            rejection := 'TARGET_ALREADY_BOUND';
        else
            insert into v3_eui_version(eui_id,version,user_id,binding_token,request_id)
              values(target.eui_id,target.current_version+1,actor.user_id,gen_random_uuid()::text,rid);
            update v3_eui_authority set current_version=target.current_version+1
              where eui_id = target.eui_id;
            insert into v3_identity_request_result
              values(rid,'CHANGED',target.eui_id,target.current_version+1,null);
            eui_event := gen_random_uuid();
            insert into v3_identity_event
              values(eui_event,'EXTERNAL_USER_IDENTITY',target.eui_id,target.current_version+1,rid,clock_timestamp());
        end if;
    end if;
    if rejection is not null then
        insert into v3_identity_request_result values(rid,'REJECTED',null,null,rejection);
    end if;
    update v3_identity_request set status='TERMINAL' where request_id=rid;
    insert into v3_identity_completion_event values(completion,'LINK',rid,clock_timestamp());
    set constraints v3_identity_anchor_state,v3_identity_version_state immediate;
    perform v3_identity_check_unit(rid,false);
    if eui_event is not null then
        update v3_event_counter set position=position+1 where family='IDENTITY' returning position into strict position_end;
        insert into v3_event_position values('IDENTITY',position_end,eui_event);
    end if;
    update v3_event_counter set position=position+1 where family='IDENTITY_COMPLETION' returning position into strict position_end;
    insert into v3_event_position values('IDENTITY_COMPLETION',position_end,completion);
end $$;
