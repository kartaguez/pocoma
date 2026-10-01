-- Read-only preflight before enabling the EPT Event materializer runtime.
do $$
begin
    if to_regclass('business_event_outbox') is null
       or to_regclass('projection_tasks') is null
       or to_regclass('consumption_slots') is null
       or to_regclass('consumption_claims') is null then
        raise exception 'EPT persistence tables are missing';
    end if;

    if exists (
        select 1
        from projection_tasks
        where nullif(btrim(projection_type), '') is null
           or nullif(btrim(target_object_type), '') is null
           or nullif(btrim(target_object_id), '') is null
           or target_version < 1
    ) then
        raise exception 'A canonical ProjectionTask has an invalid ProjectionKey';
    end if;

    if exists (
        select 1
        from projection_tasks
        group by projection_type, target_object_type, target_object_id, target_version
        having count(*) > 1
    ) then
        raise exception 'Multiple ProjectionTasks share one canonical ProjectionKey';
    end if;
end $$;
