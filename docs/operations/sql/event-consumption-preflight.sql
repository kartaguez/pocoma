-- Read-only Lot 7.5 preflight after V10 and before enabling the catalog-driven scheduler.
do $$
begin
    if to_regclass('event_4_pipeline_materialization_status') is not null then
        raise exception 'V10 is not complete: the legacy Event materialization table still exists';
    end if;

    if exists (
        select 1 from tasks_4_pipeline task
        left join business_event_outbox event on event.id = task.event_id
        where event.id is null
           or task.pot_id <> event.pot_id
           or task.target_version <> event.version
           or task.pipeline_id is null or btrim(task.pipeline_id) = ''
           or task.pipeline_version < 1
    ) then
        raise exception 'An Event-derived Task has an invalid Event, Pot, version or pipeline binding';
    end if;

    if exists (
        select 1 from tasks_4_pipeline
        group by event_id, pipeline_id, pipeline_version having count(*) > 1
    ) then
        raise exception 'Multiple Tasks share one Event-derived scheduling identity';
    end if;
end $$;
