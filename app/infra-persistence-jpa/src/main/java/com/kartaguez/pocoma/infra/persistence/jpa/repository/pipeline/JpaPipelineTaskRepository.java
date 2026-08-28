package com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline;

import java.util.UUID;
import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskEntity;

public interface JpaPipelineTaskRepository extends JpaRepository<JpaPipelineTaskEntity, UUID> {

	@Query(
			value = """
					select *
					from tasks_4_pipeline
					where pipeline_id = :pipelineId
						and pipeline_version = :pipelineVersion
						and mod(partition_hash, :segmentCount) = :segmentIndex
						and (
							status = 'PENDING'
							or (status in ('CLAIMED', 'ACCEPTED', 'RUNNING') and lease_until < :now)
						)
						and (:matchEveryTaskType = true or task_type in (:taskTypes))
					order by updated_at, created_at
					limit :limit
					for update skip locked
					""",
			nativeQuery = true)
	List<JpaPipelineTaskEntity> findClaimable(
			@Param("now") Instant now,
			@Param("limit") int limit,
			@Param("segmentIndex") int segmentIndex,
			@Param("segmentCount") int segmentCount,
			@Param("pipelineId") String pipelineId,
			@Param("pipelineVersion") int pipelineVersion,
			@Param("matchEveryTaskType") boolean matchEveryTaskType,
			@Param("taskTypes") List<String> taskTypes);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaPipelineTaskEntity task
			set task.status = com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus.ACCEPTED,
				task.updatedAt = :now,
				task.acceptedAt = :now,
				task.failureKind = null,
				task.lastError = null
			where task.id = :taskId
				and task.claimToken = :claimToken
				and task.status = com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus.CLAIMED
			""")
	int markAccepted(@Param("taskId") UUID taskId, @Param("claimToken") UUID claimToken, @Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaPipelineTaskEntity task
			set task.status = com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus.RUNNING,
				task.updatedAt = :now,
				task.startedAt = :now,
				task.failureKind = null,
				task.lastError = null
			where task.id = :taskId
				and task.claimToken = :claimToken
				and task.status in (
					com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus.CLAIMED,
					com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus.ACCEPTED)
			""")
	int markRunning(@Param("taskId") UUID taskId, @Param("claimToken") UUID claimToken, @Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaPipelineTaskEntity task
			set task.status = com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus.DONE,
				task.claimToken = null,
				task.claimedBy = null,
				task.leaseUntil = null,
				task.updatedAt = :now,
				task.doneAt = :now,
				task.failureKind = null,
				task.lastError = null
			where task.id = :taskId
				and task.claimToken = :claimToken
			""")
	int markDone(@Param("taskId") UUID taskId, @Param("claimToken") UUID claimToken, @Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaPipelineTaskEntity task
			set task.status = com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus.FAILED,
				task.claimToken = null,
				task.claimedBy = null,
				task.leaseUntil = null,
				task.updatedAt = :now,
				task.failedAt = :now,
				task.failureKind = :failureKind,
				task.lastError = :error
			where task.id = :taskId
				and task.claimToken = :claimToken
			""")
	int markFailed(
			@Param("taskId") UUID taskId,
			@Param("claimToken") UUID claimToken,
			@Param("failureKind") String failureKind,
			@Param("error") String error,
			@Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaPipelineTaskEntity task
			set task.status = com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus.PENDING,
				task.claimToken = null,
				task.claimedBy = null,
				task.leaseUntil = null,
				task.updatedAt = :now
			where task.id = :taskId
				and task.claimToken = :claimToken
			""")
	int release(@Param("taskId") UUID taskId, @Param("claimToken") UUID claimToken, @Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaPipelineTaskEntity task
			set task.leaseUntil = :leaseUntil,
				task.updatedAt = :now
			where task.id = :taskId
				and task.claimToken = :claimToken
				and task.status in (
					com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus.CLAIMED,
					com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus.ACCEPTED,
					com.kartaguez.pocoma.infra.persistence.jpa.entity.pipeline.JpaPipelineTaskStatus.RUNNING)
			""")
	int heartbeat(
			@Param("taskId") UUID taskId,
			@Param("claimToken") UUID claimToken,
			@Param("leaseUntil") Instant leaseUntil,
			@Param("now") Instant now);

	@Query("""
			select count(task)
			from JpaPipelineTaskEntity task
			where task.pipelineId = :pipelineId
				and task.pipelineVersion = :pipelineVersion
				and mod(task.partitionHash, :segmentCount) = :segmentIndex
				and (:matchEveryTaskType = true or task.taskType in :taskTypes)
				and task.status in :statuses
			""")
	long countByBindingAndStatuses(
			@Param("pipelineId") String pipelineId,
			@Param("pipelineVersion") int pipelineVersion,
			@Param("segmentIndex") int segmentIndex,
			@Param("segmentCount") int segmentCount,
			@Param("matchEveryTaskType") boolean matchEveryTaskType,
			@Param("taskTypes") List<String> taskTypes,
			@Param("statuses") List<JpaPipelineTaskStatus> statuses);
}
