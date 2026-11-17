-- A Result is the terminal value of one recorded Command, not a versioned projection.
create table command_results (
    command_id uuid primary key references recorded_commands(command_id),
    owner_issuer text not null check (btrim(owner_issuer) <> ''),
    owner_subject text not null check (btrim(owner_subject) <> ''),
    schema_version integer not null default 1 check (schema_version = 1),
    outcome_type varchar(16) not null check (outcome_type in ('APPLIED', 'REJECTED', 'FAILED')),
    pot_id uuid,
    resulting_version bigint,
    public_code varchar(255),
    resolved_at timestamptz not null,
    constraint ck_command_results_shape check (
        (outcome_type = 'APPLIED' and pot_id is not null and resulting_version >= 1 and public_code is null)
        or (outcome_type in ('REJECTED', 'FAILED') and pot_id is null and resulting_version is null
            and public_code is not null and btrim(public_code) <> '')
    ),
    constraint ck_command_results_failed_code check (
        outcome_type <> 'FAILED' or public_code = 'COMMAND_PROCESSING_FAILED'
    )
);

create function prevent_command_result_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'command_results are immutable';
end $$;

create trigger command_result_immutable before update or delete on command_results
    for each row execute function prevent_command_result_mutation();

-- Offline cutover/backfill: every authoritative terminal Event has an outcome and
-- historical requester. A contradictory Event must stop migration, never become a Result.
do $$
begin
    if exists (
        select 1 from command_terminal_events event
        join command_outcomes outcome on outcome.command_id = event.command_id
        where event.event_type <> ('COMMAND_' || outcome.outcome_type)
    ) then
        raise exception 'Terminal Command Event and outcome disagree during Result backfill';
    end if;
end $$;

insert into command_results (command_id, owner_issuer, owner_subject, schema_version,
    outcome_type, pot_id, resulting_version, public_code, resolved_at)
select recorded.command_id, recorded.auth_issuer, recorded.auth_subject, 1,
       outcome.outcome_type, outcome.pot_id, outcome.resulting_version,
       outcome.public_code, outcome.resolved_at
from command_terminal_events event
join command_outcomes outcome on outcome.command_id = event.command_id
join recorded_commands recorded on recorded.command_id = outcome.command_id;

-- When the old READ schema exists, require parity before the GET switches stores.
-- In a fresh database, no legacy artifact exists and the direct consumer handles new Events.
do $$
begin
    if to_regclass('pocoma_read.projection_artifact') is not null then
      if exists (
        select 1
        from pocoma_read.projection_root root
        join pocoma_read.projection_artifact artifact on artifact.projection_root_id = root.id
        left join command_results result on result.command_id::text = root.target_object_id
        where root.projection_type = 'COMMAND_RESULT'
          and (result.command_id is null
            or root.target_object_type <> 'COMMAND'
            or root.target_version <> 1
            or artifact.artifact_type <> 'COMMAND_RESULT'
            or artifact.artifact_key <> root.target_object_id
            or artifact.payload->>'commandId' is distinct from root.target_object_id
            or artifact.payload->>'outcome' is distinct from result.outcome_type
            or artifact.payload->>'visibility' is distinct from 'EXACT_EXTERNAL_IDENTITY'
            or artifact.payload #>> '{visibleToExternalIdentity,issuer}' is distinct from result.owner_issuer
            or artifact.payload #>> '{visibleToExternalIdentity,subject}' is distinct from result.owner_subject
            or artifact.payload->>'potId' is distinct from result.pot_id::text
            or artifact.payload->>'resultingVersion' is distinct from result.resulting_version::text
            or artifact.payload->>'code' is distinct from result.public_code
            or (artifact.payload->>'resolvedAt')::timestamptz is distinct from result.resolved_at)
      ) then
        raise exception 'Legacy COMMAND_RESULT artifact differs from authoritative Result';
      end if;
    end if;
end $$;
