do $$
declare
    terminal_task_count bigint;
    mapped_slot_count bigint;
begin
    select count(*) into terminal_task_count
    from tasks_4_pipeline where status in ('DONE', 'FAILED', 'SUPERSEDED');

    select count(*) into mapped_slot_count
    from tasks_4_pipeline task
    join consumption_slots slot
      on slot.consumable_type = 'TASK'
     and slot.consumable_components = jsonb_build_array(task.id::text)
     and slot.consumer_type = 'TASK_EXECUTOR'
     and slot.consumer_components = '[]'::jsonb
     and slot.status = 'DONE'
     and slot.terminal_outcome =
         case task.status when 'DONE' then 'SUCCESS' when 'FAILED' then 'FAILED' else 'ABANDONED' end
    where task.status in ('DONE', 'FAILED', 'SUPERSEDED');

    if terminal_task_count <> mapped_slot_count then
        raise exception 'Terminal Task/ConsumptionSlot mismatch: % Tasks, % slots',
            terminal_task_count, mapped_slot_count;
    end if;

    if exists (select 1 from tasks_4_pipeline where status in ('CLAIMED', 'ACCEPTED', 'RUNNING')) then
        raise exception 'Legacy in-flight states remain after cutover';
    end if;

	if exists (
		select 1 from tasks_4_pipeline
		where pipeline_id = 'balance-projection'
		  and (partition_key is null or not pg_input_is_valid(partition_key, 'uuid'))
	) then
		raise exception 'A Balance Task remains undiscoverable because partition_key is missing or non-UUID';
	end if;

	if exists (
		select 1 from tasks_4_pipeline
		where pipeline_id = 'balance-projection'
		  and (target_version is null or target_version < 1 or pipeline_version < 1
		       or task_type is null or btrim(task_type) = ''
		       or task_payload is null or btrim(task_payload) = '')
	) then
		raise exception 'A structurally invalid Balance Task remains after cutover';
	end if;

	if exists (
		select 1 from tasks_4_pipeline task
		left join consumption_slots slot
		  on slot.consumable_type = 'TASK'
		 and slot.consumable_components = jsonb_build_array(task.id::text)
		 and slot.consumer_type = 'TASK_EXECUTOR'
		 and slot.consumer_components = '[]'::jsonb
		left join consumption_claims claim
		  on claim.slot_id = slot.slot_id and claim.claim_id = slot.current_claim_id
		where task.status in ('DONE', 'FAILED', 'SUPERSEDED')
		  and (slot.slot_id is null or (slot.status = 'PENDING' and slot.next_claim_at <= clock_timestamp()
		       and (slot.current_claim_id is null or claim.lease_until <= clock_timestamp())))
	) then
		raise exception 'A terminal legacy Task remains eligible for replay';
	end if;
end $$;

select status, count(*) from tasks_4_pipeline group by status order by status;
select terminal_outcome, count(*)
from consumption_slots
where consumable_type = 'TASK' and consumer_type = 'TASK_EXECUTOR'
group by terminal_outcome order by terminal_outcome;
