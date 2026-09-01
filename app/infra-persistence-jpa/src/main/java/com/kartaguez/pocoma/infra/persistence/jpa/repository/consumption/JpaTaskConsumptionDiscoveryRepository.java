package com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.kartaguez.pocoma.engine.processing.task.ordering.TaskSearchCursor;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaTaskReadRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaTaskReadRepository.TaskRow;

@Repository
public class JpaTaskConsumptionDiscoveryRepository {
	public static final String SELECT_ELIGIBLE = """
			select task.id, task.pipeline_id, task.pipeline_version, task.partition_key,
			       task.target_version, task.created_at, task.task_type, task.task_payload
			from tasks_4_pipeline task
			left join consumption_slots slot
			  on slot.consumable_type = 'TASK'
			 and slot.consumable_components = jsonb_build_array(task.id::text)
			 and slot.consumer_type = 'TASK_EXECUTOR'
			 and slot.consumer_components = '[]'::jsonb
			left join consumption_claims claim
			  on claim.slot_id = slot.slot_id and claim.claim_id = slot.current_claim_id
			where task.pipeline_id = ? and task.pipeline_version = ?
			  and (task.created_at > ? or (task.created_at = ? and task.id > ?))
			  and (slot.slot_id is null or (
			       slot.status = 'PENDING' and slot.next_claim_at <= ?
			       and (slot.current_claim_id is null or claim.lease_until <= ?)))
			order by task.created_at, task.id
			limit ?
			""";

	private final JdbcTemplate jdbc;

	public JpaTaskConsumptionDiscoveryRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

	public List<TaskRow> findNextEligible(String pipelineId, int pipelineVersion, Instant now,
			TaskSearchCursor afterExclusive, int limit) {
		return jdbc.query(SELECT_ELIGIBLE, JpaTaskReadRepository::map, pipelineId, pipelineVersion,
				Timestamp.from(afterExclusive.createdAt()), Timestamp.from(afterExclusive.createdAt()),
				afterExclusive.taskId(), Timestamp.from(now), Timestamp.from(now), limit);
	}
}
