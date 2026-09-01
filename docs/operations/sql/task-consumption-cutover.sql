-- Execute atomically after stopping every legacy Task executor.
begin;

-- Operational prerequisite: every legacy executor is stopped and restart-disabled.

do $$
begin
    if exists (
        select 1 from tasks_4_pipeline
        where status in ('CLAIMED', 'ACCEPTED', 'RUNNING')
          and (lease_until is null or lease_until > clock_timestamp())
    ) then
        raise exception 'Cannot cut over while a legacy Task claim is active';
    end if;
end $$;

do $$
begin
	if exists (
		select 1 from tasks_4_pipeline
		where status in ('CLAIMED', 'ACCEPTED', 'RUNNING')
		  and (claim_token is null or claimed_by is null or claimed_at is null or lease_until is null)
	) then
		raise exception 'Cannot cut over ambiguous or unbounded legacy Task claims';
	end if;
end $$;

update tasks_4_pipeline
set status = 'PENDING',
    claim_token = null,
    claimed_by = null,
    lease_until = null,
    claimed_at = null,
    accepted_at = null,
    started_at = null,
    updated_at = greatest(updated_at, clock_timestamp())
where status in ('CLAIMED', 'ACCEPTED', 'RUNNING')
  and lease_until <= clock_timestamp();

with terminal_tasks as (
    select task.*,
           case task.status
               when 'DONE' then 'SUCCESS'
               when 'FAILED' then 'FAILED'
               when 'SUPERSEDED' then 'ABANDONED'
           end as target_outcome,
           md5(task.id::text || ':task-consumption-slot') as slot_hash
    from tasks_4_pipeline task
    where task.status in ('DONE', 'FAILED', 'SUPERSEDED')
), normalized as (
    select *,
           (substr(slot_hash, 1, 8) || '-' || substr(slot_hash, 9, 4) || '-4' ||
            substr(slot_hash, 14, 3) || '-a' || substr(slot_hash, 18, 3) || '-' ||
            substr(slot_hash, 21, 12))::uuid as generated_slot_id
    from terminal_tasks
)
insert into consumption_slots (
    slot_id, consumable_type, consumable_components, consumer_type, consumer_components,
    revision, last_attempt_number, status, terminal_outcome, current_claim_id,
    next_claim_at, created_at, done_at
)
select generated_slot_id, 'TASK', jsonb_build_array(id::text), 'TASK_EXECUTOR', '[]'::jsonb,
       0, 0, 'DONE', target_outcome, null,
       created_at, created_at, coalesce(done_at, failed_at, updated_at, created_at)
from normalized
on conflict (consumable_type, consumable_components, consumer_type, consumer_components) do nothing;

do $$
begin
    if exists (
        select 1
        from tasks_4_pipeline task
        join consumption_slots slot
          on slot.consumable_type = 'TASK'
         and slot.consumable_components = jsonb_build_array(task.id::text)
         and slot.consumer_type = 'TASK_EXECUTOR'
         and slot.consumer_components = '[]'::jsonb
        where task.status in ('DONE', 'FAILED', 'SUPERSEDED')
          and (slot.status <> 'DONE' or slot.terminal_outcome <>
              case task.status when 'DONE' then 'SUCCESS' when 'FAILED' then 'FAILED' else 'ABANDONED' end)
    ) then
        raise exception 'A pre-existing consumption slot contradicts a terminal legacy Task';
    end if;
end $$;

commit;
