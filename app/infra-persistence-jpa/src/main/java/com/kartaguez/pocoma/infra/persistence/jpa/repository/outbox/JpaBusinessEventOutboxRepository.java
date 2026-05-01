package com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaBusinessEventOutboxEntity;

public interface JpaBusinessEventOutboxRepository extends JpaRepository<JpaBusinessEventOutboxEntity, UUID> {

	@Query(
			value = """
					select *
					from business_event_outbox
					where status = 'PENDING'
						or (status in ('CLAIMED', 'ACCEPTED', 'RUNNING') and lease_until < :now)
					order by created_at
					limit :limit
					for update skip locked
					""",
			nativeQuery = true)
	List<JpaBusinessEventOutboxEntity> findClaimable(@Param("now") Instant now, @Param("limit") int limit);

	@Query(
			value = """
					select *
					from business_event_outbox
					where mod(pot_partition_hash, :segmentCount) = :segmentIndex
						and (
							status = 'PENDING'
							or (status in ('CLAIMED', 'ACCEPTED', 'RUNNING') and lease_until < :now)
						)
					order by created_at
					limit :limit
					for update skip locked
					""",
			nativeQuery = true)
	List<JpaBusinessEventOutboxEntity> findClaimable(
			@Param("now") Instant now,
			@Param("limit") int limit,
			@Param("segmentIndex") int segmentIndex,
			@Param("segmentCount") int segmentCount);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaBusinessEventOutboxEntity event
			set event.status = com.kartaguez.pocoma.engine.model.BusinessEventStatus.ACCEPTED,
				event.acceptedAt = :now
			where event.id = :eventId
				and event.claimToken = :claimToken
				and event.status = com.kartaguez.pocoma.engine.model.BusinessEventStatus.CLAIMED
			""")
	int markAccepted(
			@Param("eventId") UUID eventId,
			@Param("claimToken") UUID claimToken,
			@Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaBusinessEventOutboxEntity event
			set event.status = com.kartaguez.pocoma.engine.model.BusinessEventStatus.RUNNING,
				event.startedAt = :now
			where event.id = :eventId
				and event.claimToken = :claimToken
				and event.status in (
					com.kartaguez.pocoma.engine.model.BusinessEventStatus.CLAIMED,
					com.kartaguez.pocoma.engine.model.BusinessEventStatus.ACCEPTED)
			""")
	int markRunning(
			@Param("eventId") UUID eventId,
			@Param("claimToken") UUID claimToken,
			@Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaBusinessEventOutboxEntity event
			set event.status = com.kartaguez.pocoma.engine.model.BusinessEventStatus.PROCESSED,
				event.processedAt = :now,
				event.leaseUntil = null,
				event.claimToken = null,
				event.acceptedAt = null,
				event.startedAt = null,
				event.lastError = null
			where event.id = :eventId
				and event.claimToken = :claimToken
				and event.status in (
					com.kartaguez.pocoma.engine.model.BusinessEventStatus.CLAIMED,
					com.kartaguez.pocoma.engine.model.BusinessEventStatus.ACCEPTED,
					com.kartaguez.pocoma.engine.model.BusinessEventStatus.RUNNING)
			""")
	int markDone(
			@Param("eventId") UUID eventId,
			@Param("claimToken") UUID claimToken,
			@Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaBusinessEventOutboxEntity event
			set event.status = com.kartaguez.pocoma.engine.model.BusinessEventStatus.FAILED,
				event.failedAt = :now,
				event.leaseUntil = null,
				event.lastError = :error
			where event.id = :eventId
				and event.claimToken = :claimToken
				and event.status in (
					com.kartaguez.pocoma.engine.model.BusinessEventStatus.CLAIMED,
					com.kartaguez.pocoma.engine.model.BusinessEventStatus.ACCEPTED,
					com.kartaguez.pocoma.engine.model.BusinessEventStatus.RUNNING)
			""")
	int markFailed(
			@Param("eventId") UUID eventId,
			@Param("claimToken") UUID claimToken,
			@Param("error") String error,
			@Param("now") Instant now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update JpaBusinessEventOutboxEntity event
			set event.status = com.kartaguez.pocoma.engine.model.BusinessEventStatus.PENDING,
				event.leaseUntil = null,
				event.claimToken = null,
				event.lastError = null
			where event.id = :eventId
				and event.claimToken = :claimToken
				and event.status in (
					com.kartaguez.pocoma.engine.model.BusinessEventStatus.CLAIMED,
					com.kartaguez.pocoma.engine.model.BusinessEventStatus.ACCEPTED,
					com.kartaguez.pocoma.engine.model.BusinessEventStatus.RUNNING)
			""")
	int release(
			@Param("eventId") UUID eventId,
			@Param("claimToken") UUID claimToken);

	@Query("""
			select count(event)
			from JpaBusinessEventOutboxEntity event
			where event.status in (
				com.kartaguez.pocoma.engine.model.BusinessEventStatus.PENDING,
				com.kartaguez.pocoma.engine.model.BusinessEventStatus.CLAIMED,
				com.kartaguez.pocoma.engine.model.BusinessEventStatus.ACCEPTED,
				com.kartaguez.pocoma.engine.model.BusinessEventStatus.RUNNING)
			""")
	long countPendingOrClaimed();
}
