-- Operational READ cutover gate. Stop old projection writers before applying it.
-- This migration changes no historical result; a V1, mixed, or malformed
-- artifact aborts Flyway and prevents target-only GET/projector deployment.
do $$
declare
    artifact record;
    identity jsonb;
begin
    for artifact in
        select a.payload, a.artifact_type, a.artifact_key,
               r.target_object_type, r.target_object_id, r.target_version
        from pocoma_read.projection_root r
        join pocoma_read.projection_artifact a on a.projection_root_id = r.id
        where r.projection_type = 'COMMAND_RESULT'
    loop
        if artifact.target_object_type <> 'COMMAND'
           or artifact.target_version <> 1
           or artifact.artifact_type <> 'COMMAND_RESULT'
           or artifact.artifact_key <> artifact.target_object_id
           or jsonb_typeof(artifact.payload) is distinct from 'object' then
            raise exception 'WA.7: legacy or malformed COMMAND_RESULT artifact remains';
        end if;
        if not (artifact.payload ?& array['commandId','outcome','potId',
                                               'resultingVersion','code','resolvedAt',
                                               'visibility','visibleToExternalIdentity'])
           or artifact.payload - array['commandId','outcome','potId',
                                       'resultingVersion','code','resolvedAt',
                                       'visibility','visibleToExternalIdentity'] <> '{}'::jsonb
           or artifact.payload->>'visibility' is distinct from 'EXACT_EXTERNAL_IDENTITY'
           or artifact.payload->>'commandId' is distinct from artifact.target_object_id
           or artifact.target_object_id !~
              '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
           or coalesce(artifact.payload->>'outcome' not in ('APPLIED','REJECTED','FAILED'), true)
           or jsonb_typeof(artifact.payload->'resolvedAt') is distinct from 'string'
           or nullif(artifact.payload->>'resolvedAt','') is null then
            raise exception 'WA.7: legacy or malformed COMMAND_RESULT artifact remains';
        end if;
        if artifact.payload->>'outcome' = 'APPLIED' then
            if jsonb_typeof(artifact.payload->'potId') is distinct from 'string'
               or (artifact.payload->>'potId') !~
                  '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
               or jsonb_typeof(artifact.payload->'resultingVersion') is distinct from 'number'
               or (artifact.payload->>'resultingVersion')::numeric < 1
               or (artifact.payload->>'resultingVersion')::numeric > 9223372036854775807
               or (artifact.payload->>'resultingVersion')::numeric <>
                  trunc((artifact.payload->>'resultingVersion')::numeric)
               or artifact.payload->'code' <> 'null'::jsonb then
                raise exception 'WA.7: malformed APPLIED COMMAND_RESULT remains';
            end if;
        elsif artifact.payload->'potId' <> 'null'::jsonb
           or artifact.payload->'resultingVersion' <> 'null'::jsonb
           or jsonb_typeof(artifact.payload->'code') is distinct from 'string'
           or nullif(artifact.payload->>'code','') is null then
            raise exception 'WA.7: malformed terminal COMMAND_RESULT remains';
        end if;
        identity := artifact.payload->'visibleToExternalIdentity';
        if jsonb_typeof(identity) is distinct from 'object'
           or jsonb_typeof(identity->'issuer') is distinct from 'string'
           or jsonb_typeof(identity->'subject') is distinct from 'string'
           or nullif(identity->>'issuer','') is null
           or nullif(identity->>'subject','') is null
           or identity - array['issuer','subject'] <> '{}'::jsonb then
            raise exception 'WA.7: malformed exact-E COMMAND_RESULT artifact remains';
        end if;
    end loop;
end $$;
