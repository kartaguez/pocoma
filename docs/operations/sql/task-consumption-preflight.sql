-- Must return no exception before cutover. Run only after the legacy runtime has been stopped.
do $$
begin
	-- Operationally, every legacy executor must already be stopped and restart-disabled.
    if exists (
        select 1 from tasks_4_pipeline
        where status in ('CLAIMED', 'ACCEPTED', 'RUNNING')
          and (lease_until is null or lease_until > clock_timestamp())
    ) then
        raise exception 'Active or unbounded legacy Task claims remain';
    end if;

	if exists (
		select 1 from tasks_4_pipeline
		where status in ('CLAIMED', 'ACCEPTED', 'RUNNING')
		  and (claim_token is null or claimed_by is null or claimed_at is null)
	) then
		raise exception 'Ambiguous legacy Task claim metadata remains';
	end if;

    if exists (select 1 from tasks_4_pipeline where target_version is null or target_version < 1) then
        raise exception 'A Task has no valid exact target_version';
    end if;

	if exists (
		select 1 from tasks_4_pipeline
		where pipeline_id = 'balance-projection'
		  and (partition_key is null or not pg_input_is_valid(partition_key, 'uuid'))
	) then
		raise exception 'A Balance Task has a missing or non-UUID partition_key';
	end if;

	if exists (
		select 1 from tasks_4_pipeline
		where pipeline_id = 'balance-projection'
		  and (pipeline_version < 1 or task_type is null or btrim(task_type) = ''
		       or task_payload is null or btrim(task_payload) = '')
	) then
		raise exception 'A Balance Task has an invalid structural binding or empty opaque payload';
	end if;

    if exists (
        select 1 from tasks_4_pipeline
        where status in ('DONE', 'FAILED', 'SUPERSEDED')
          and coalesce(done_at, failed_at, updated_at, created_at) is null
    ) then
        raise exception 'A terminal legacy Task has no usable completion timestamp';
    end if;
end $$;
