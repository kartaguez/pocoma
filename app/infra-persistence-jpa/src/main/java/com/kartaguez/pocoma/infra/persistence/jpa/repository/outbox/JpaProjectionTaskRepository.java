package com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.engine.model.ProjectionTaskStatus;
import com.kartaguez.pocoma.engine.model.ProjectionTaskType;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaProjectionTaskEntity;

public interface JpaProjectionTaskRepository extends JpaRepository<JpaProjectionTaskEntity, UUID> {

	@Query("""
			select task
			from JpaProjectionTaskEntity task
			where task.potId = :potId
				and task.taskType = :taskType
				and task.status in :activeStatuses
			""")
	Optional<JpaProjectionTaskEntity> findActive(
			@Param("potId") UUID potId,
			@Param("taskType") ProjectionTaskType taskType,
			@Param("activeStatuses") List<ProjectionTaskStatus> activeStatuses);

	@Query(
			value = """
					select *
					from projection_tasks
					where status = 'PENDING'
						or (status in ('CLAIMED', 'ACCEPTED', 'RUNNING') and lease_until < :now)
					order by updated_at, created_at
					limit :limit
					for update skip locked
					""",
			nativeQuery = true)
	List<JpaProjectionTaskEntity> findClaimable(@Param("now") Instant now, @Param("limit") int limit);

	@Query(
			value = """
					select *
					from projection_tasks
					where mod(pot_partition_hash, :segmentCount) = :segmentIndex
						and (
							status = 'PENDING'
							or (status in ('CLAIMED', 'ACCEPTED', 'RUNNING') and lease_until < :now)
						)
					order by updated_at, created_at
					limit :limit
					for update skip locked
					""",
			nativeQuery = true)
	List<JpaProjectionTaskEntity> findClaimable(
			@Param("now") Instant now,
			@Param("limit") int limit,
			@Param("segmentIndex") int segmentIndex,
			@Param("segmentCount") int segmentCount);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaProjectionTaskEntity task
			set task.status = com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.ACCEPTED,
				task.updatedAt = :now,
				task.acceptedAt = :now,
				task.lastError = null
			where task.id = :taskId
				and task.claimToken = :claimToken
				and task.status = com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.CLAIMED
			""")
	int markAccepted(
			@Param("taskId") UUID taskId,
			@Param("claimToken") UUID claimToken,
			@Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaProjectionTaskEntity task
			set task.status = com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.RUNNING,
				task.updatedAt = :now,
				task.startedAt = :now,
				task.lastError = null
			where task.id = :taskId
				and task.claimToken = :claimToken
				and task.status in (
					com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.CLAIMED,
					com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.ACCEPTED)
			""")
	int markRunning(
			@Param("taskId") UUID taskId,
			@Param("claimToken") UUID claimToken,
			@Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaProjectionTaskEntity task
			set task.status = com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.DONE,
				task.claimToken = null,
				task.claimedBy = null,
				task.leaseUntil = null,
				task.updatedAt = :now,
				task.doneAt = :now,
				task.lastError = null
			where task.id = :taskId
				and task.claimToken = :claimToken
			""")
	int markDone(
			@Param("taskId") UUID taskId,
			@Param("claimToken") UUID claimToken,
			@Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaProjectionTaskEntity task
			set task.status = com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.FAILED,
				task.claimToken = null,
				task.claimedBy = null,
				task.leaseUntil = null,
				task.updatedAt = :now,
				task.failedAt = :now,
				task.lastError = :error
			where task.id = :taskId
				and task.claimToken = :claimToken
			""")
	int markFailed(
			@Param("taskId") UUID taskId,
			@Param("claimToken") UUID claimToken,
			@Param("error") String error,
			@Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaProjectionTaskEntity task
			set task.status = com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.PENDING,
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
			update JpaProjectionTaskEntity task
			set task.leaseUntil = :leaseUntil,
				task.updatedAt = :now
			where task.id = :taskId
				and task.claimToken = :claimToken
				and task.status in (
					com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.CLAIMED,
					com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.ACCEPTED,
					com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.RUNNING)
			""")
	int heartbeat(
			@Param("taskId") UUID taskId,
			@Param("claimToken") UUID claimToken,
			@Param("leaseUntil") Instant leaseUntil,
			@Param("now") Instant now);

	@Query("""
			select count(task)
			from JpaProjectionTaskEntity task
			where task.status in (
				com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.PENDING,
				com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.CLAIMED,
				com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.ACCEPTED,
				com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.RUNNING)
			""")
	long countPendingOrInProgress();

	@Query("""
			select count(task)
			from JpaProjectionTaskEntity task
			where task.potId = :potId
				and task.status in (
					com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.PENDING,
					com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.CLAIMED,
					com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.ACCEPTED,
					com.kartaguez.pocoma.engine.model.ProjectionTaskStatus.RUNNING)
			""")
	long countPendingOrInProgress(@Param("potId") UUID potId);
}
