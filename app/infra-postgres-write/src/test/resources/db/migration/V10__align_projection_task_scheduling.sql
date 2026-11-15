-- Lot 7.5: direct identity for Event-derived projection Tasks.
-- This is not the universal identity of future administrative projection Tasks.

do $$
begin
    if exists (
        select 1 from tasks_4_pipeline
        group by event_id, pipeline_id, pipeline_version having count(*) > 1
    ) then
        raise exception 'V10 preflight: multiple Tasks share an Event-derived identity';
    end if;

    if exists (
        select 1 from tasks_4_pipeline task
        left join business_event_outbox event on event.id = task.event_id
        where event.id is null
    ) then
        raise exception 'V10 preflight: orphan Event-derived Task';
    end if;

    if exists (
        select 1 from tasks_4_pipeline task
        join business_event_outbox event on event.id = task.event_id
        where task.target_version <> event.version
           or case
                when task.partition_key is null
                  or task.partition_key !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
                then true
                else task.partition_key::uuid <> event.pot_id
              end
    ) then
        raise exception 'V10 preflight: Task Pot/version differs from its Event';
    end if;

    if exists (
        select 1 from tasks_4_pipeline task
        join event_4_pipeline_materialization_status materialization
          on materialization.id = task.materialization_id
        where task.event_id <> materialization.event_id
           or task.pipeline_id <> materialization.pipeline_id
           or task.pipeline_version <> materialization.pipeline_version
    ) then
        raise exception 'V10 preflight: Task identity differs from legacy materialization';
    end if;

    if exists (
        select 1 from event_4_pipeline_materialization_status materialization
        left join tasks_4_pipeline task on task.materialization_id = materialization.id
        group by materialization.id, materialization.status
        having (materialization.status = 'MATERIALIZED' and count(task.id) <> 1)
            or (materialization.status = 'SKIPPED' and count(task.id) <> 0)
            or materialization.status not in ('MATERIALIZED', 'SKIPPED')
    ) then
        raise exception 'V10 preflight: unresolved legacy materialization state';
    end if;
end $$;

alter table tasks_4_pipeline add column pot_id uuid;

update tasks_4_pipeline task
set pot_id = event.pot_id
from business_event_outbox event
where event.id = task.event_id;

alter table tasks_4_pipeline
    alter column pot_id set not null,
    add constraint ck_tasks_4_pipeline_pipeline_id check (length(trim(pipeline_id)) > 0),
    add constraint ck_tasks_4_pipeline_pipeline_version check (pipeline_version >= 1);

drop index uk_tasks_4_pipeline_materialization_key;
drop index uk_tasks_4_pipeline_event_key;

alter table tasks_4_pipeline
    drop constraint fk_tasks_4_pipeline_materialization,
    drop column materialization_id;

create unique index uk_tasks_4_pipeline_event_generation
    on tasks_4_pipeline (event_id, pipeline_id, pipeline_version);

drop table event_4_pipeline_materialization_status;
