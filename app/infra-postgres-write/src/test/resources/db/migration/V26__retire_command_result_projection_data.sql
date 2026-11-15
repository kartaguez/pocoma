-- Apply during the offline COMMAND_RESULT cutover, after V25 has backfilled and
-- parity-checked Result, with old Event/Task workers stopped. Historical
-- Consumption slots and terminal Events remain for audit and replay provenance.
do $$
begin
    if exists (
        select 1 from projection_tasks task
        left join command_results result on result.command_id::text = task.target_object_id
        where task.projection_type = 'COMMAND_RESULT' and result.command_id is null
    ) then
        raise exception 'COMMAND_RESULT Task without authoritative Result';
    end if;
    if to_regclass('pocoma_read.projection_root') is not null then
      if exists (
          select 1 from pocoma_read.projection_root root
          left join command_results result on result.command_id::text = root.target_object_id
          where root.projection_type = 'COMMAND_RESULT' and result.command_id is null
      ) then
          raise exception 'COMMAND_RESULT root without authoritative Result';
      end if;
    end if;
    if to_regclass('pocoma_read.projection_failure') is not null then
      if exists (
          select 1 from pocoma_read.projection_failure failure
          left join command_results result on result.command_id::text = failure.target_object_id
          where failure.projection_type = 'COMMAND_RESULT' and result.command_id is null
      ) then
          raise exception 'COMMAND_RESULT technical failure without authoritative Result';
      end if;
    end if;
end $$;

delete from projection_tasks where projection_type = 'COMMAND_RESULT';

do $$
begin
    if to_regclass('pocoma_read.projection_artifact') is not null then
      delete from pocoma_read.projection_artifact artifact
      using pocoma_read.projection_root root
      where artifact.projection_root_id = root.id and root.projection_type = 'COMMAND_RESULT';
      delete from pocoma_read.projection_root where projection_type = 'COMMAND_RESULT';
      delete from pocoma_read.projection_failure where projection_type = 'COMMAND_RESULT';
    end if;
end $$;
