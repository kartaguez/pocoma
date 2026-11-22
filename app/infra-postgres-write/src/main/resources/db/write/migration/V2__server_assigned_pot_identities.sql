-- Server-assigned Pot, Shareholder, and Expense identities (Phase B).
-- Preserves V1 immutability of v3_command, historical commands/results, and admission_role column projections.

alter table v3_command alter column pot_id drop not null;
alter table v3_command add constraint v3_command_pot_id_presence
    check (operation = 'CreatePot' or pot_id is not null);

alter table v3_command_result add column pot_id uuid;
alter table v3_command_result add column created_resources jsonb;
alter table v3_command_result add constraint v3_command_result_created_resources_shape
    check (created_resources is null or (outcome = 'CHANGED' and jsonb_typeof(created_resources) = 'object'));

create or replace function v3_require_event_request() returns trigger language plpgsql as $$
begin
    if not exists(
        select 1
        from v3_command c
        join v3_command_result r using(request_id)
        where c.request_id = new.request_id
          and c.status = 'TERMINAL'
          and coalesce(r.pot_id, c.pot_id) = new.pot_id
          and (c.pot_id is null or r.pot_id is null or r.pot_id = c.pot_id)
          and r.outcome = 'CHANGED'
          and r.resulting_pot_version = new.subject_version
    ) then
        raise exception 'Pot event does not match its terminal Command' using errcode = '23514';
    end if;
    return new;
end $$;

-- Preserves V8/V10 admission SECURITY INVOKER projection: PENDING path never reads binding_token, payload, r.pot_id, or r.created_resources.
create or replace function v3_require_command_unit() returns trigger language plpgsql as $$
declare
    c v3_command%rowtype;
    r v3_command_result%rowtype;
    effective_pot_id uuid;
begin
    select request_id, status, pot_id
      into c.request_id, c.status, c.pot_id
      from v3_command
     where request_id = new.request_id;
    select request_id, outcome, resulting_pot_version
      into r.request_id, r.outcome, r.resulting_pot_version
      from v3_command_result
     where request_id = c.request_id;
    if c.status = 'PENDING' then
        if r.request_id is not null then
            raise exception 'Pending Command has result' using errcode = '23514';
        end if;
        return new;
    end if;
    select operation, requester_issuer, requester_subject
      into c.operation, c.requester_issuer, c.requester_subject
      from v3_command
     where request_id = c.request_id;
    select pot_id, created_resources
      into r.pot_id, r.created_resources
      from v3_command_result
     where request_id = c.request_id;
    if r.request_id is null
       or exists(select 1 from consumption_slot s where s.consumption_id = '98f7268d-d340-581c-b83d-010926223e14'::uuid and s.consumable_id = c.request_id)
       or not exists(select 1 from v3_command_completion_event e join v3_event_position p on p.event_id = e.event_id and p.family = 'COMMAND_COMPLETION' where e.subject_id = c.request_id) then
        raise exception 'Incomplete terminal Command unit' using errcode = '23514';
    end if;
    if c.pot_id is not null and r.pot_id is not null and r.pot_id <> c.pot_id then
        raise exception 'Command result pot_id mismatch' using errcode = '23514';
    end if;
    effective_pot_id := coalesce(r.pot_id, c.pot_id);
    if c.operation = 'CreatePot' and c.pot_id is null then
        if r.outcome = 'REJECTED' then
            if r.pot_id is not null or r.created_resources is not null then
                raise exception 'Rejected server-assigned CreatePot must not assign pot_id' using errcode = '23514';
            end if;
        elsif r.outcome <> 'CHANGED' or r.pot_id is null then
            raise exception 'Server-assigned CreatePot requires assigned pot_id on CHANGED' using errcode = '23514';
        end if;
    elsif effective_pot_id is null then
        raise exception 'Missing effective pot_id on terminal Command' using errcode = '23514';
    end if;
    if c.operation = 'CreateInvitation' then
        if r.outcome not in ('INVITATION_CREATED', 'REJECTED')
           or exists(select 1 from v3_pot_event where request_id = c.request_id) then
            raise exception 'Invalid Invitation command shape' using errcode = '23514';
        end if;
        if r.outcome = 'INVITATION_CREATED' then
            if not exists(
                select 1
                from v3_invitation i
                join v3_invitation_event e using(invitation_id, version)
                join v3_event_position p on p.event_id = e.event_id and p.family = 'INVITATION'
                where i.creation_request_id = c.request_id
                  and i.pot_id = effective_pot_id
                  and i.creator_issuer = c.requester_issuer
                  and i.creator_subject = c.requester_subject
            )
               or exists(select 1 from v3_pot_version where pot_id = effective_pot_id and writer_xid = pg_current_xact_id()) then
                raise exception 'Invitation success without exact effect' using errcode = '23514';
            end if;
        elsif exists(select 1 from v3_invitation where creation_request_id = c.request_id) then
            raise exception 'Rejected invitation has effect' using errcode = '23514';
        end if;
    elsif r.outcome = 'INVITATION_CREATED' then
        raise exception 'Invitation outcome on Pot operation' using errcode = '23514';
    end if;
    if r.outcome = 'CHANGED' then
        if effective_pot_id is null
           or not exists(
                select 1
                from v3_pot_event e
                join v3_event_position p on p.event_id = e.event_id and p.family = 'POT'
                where e.request_id = c.request_id
                  and e.pot_id = effective_pot_id
                  and e.subject_version = r.resulting_pot_version
            )
           or not exists(
                select 1
                from v3_pot_event_component x
                join v3_pot_event e using(event_id)
                where e.request_id = c.request_id
            ) then
            raise exception 'Changed Command missing exact publication' using errcode = '23514';
        end if;
    elsif exists(select 1 from v3_pot_event where request_id = c.request_id) then
        raise exception 'No-effect Command has mutation event' using errcode = '23514';
    end if;
    return new;
end $$;
