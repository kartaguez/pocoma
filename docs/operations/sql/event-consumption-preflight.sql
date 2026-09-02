-- Read-only preflight. Run after every legacy Event/materialization worker is stopped.
do $$
begin
    if exists (
        select 1 from event_4_pipeline_materialization_status m
        where m.pipeline_id is null or btrim(m.pipeline_id) = '' or m.pipeline_version is null
           or m.pipeline_version < 1
    ) then
        raise exception 'Cannot reconstruct the exact Event consumer identity';
    end if;

    if exists (
        select 1 from event_4_pipeline_materialization_status m
        left join business_event_outbox e on e.id = m.event_id
        where e.id is null
    ) then
        raise exception 'A legacy materialization references a missing Event';
    end if;

    if exists (
        select 1 from event_4_pipeline_materialization_status m
        where m.status = 'FAILED'
    ) then
        raise exception 'FAILED Event materializations require explicit resolution before cutover';
    end if;

    if exists (
        select 1
        from event_4_pipeline_materialization_status m
        join tasks_4_pipeline task on task.materialization_id = m.id
        where m.status = 'SKIPPED'
    ) then
        raise exception 'A SKIPPED Event materialization cannot own Tasks';
    end if;

    if exists (
        select 1
        from event_4_pipeline_materialization_status m
        join tasks_4_pipeline task on task.materialization_id = m.id
        where m.status = 'MATERIALIZED'
          and (task.event_id <> m.event_id
               or task.pipeline_id <> m.pipeline_id
               or task.pipeline_version <> m.pipeline_version)
    ) then
        raise exception 'A MATERIALIZED Event materialization owns Tasks with a mismatched Event/Pipeline identity';
    end if;
end $$;
