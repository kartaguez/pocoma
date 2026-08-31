package com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption.JpaConsumptionClaimEntity;

public interface JpaConsumptionClaimRepository extends JpaRepository<JpaConsumptionClaimEntity, UUID> {

	List<JpaConsumptionClaimEntity> findBySlotIdOrderByAttemptNumber(UUID slotId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			update consumption_claims
			set invalidated_at = :now, end_reason = 'TAKEN_OVER'
			where claim_id = :claimId and slot_id = :slotId
			  and ended_at is null and invalidated_at is null and lease_until <= :now
			""", nativeQuery = true)
	int invalidateForTakeover(
			@Param("slotId") UUID slotId, @Param("claimId") UUID claimId, @Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			update consumption_claims
			set invalidated_at = :now, end_reason = 'ABANDONED'
			where claim_id = :claimId and slot_id = :slotId
			  and ended_at is null and invalidated_at is null
			""", nativeQuery = true)
	int invalidateForAbandon(
			@Param("slotId") UUID slotId, @Param("claimId") UUID claimId, @Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			update consumption_claims
			set ended_at = :now, end_reason = :reason
			where claim_id = :claimId and slot_id = :slotId
			  and ended_at is null and invalidated_at is null
			""", nativeQuery = true)
	int end(
			@Param("slotId") UUID slotId,
			@Param("claimId") UUID claimId,
			@Param("reason") String reason,
			@Param("now") Instant now);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			update consumption_claims
			set ended_at = :now, end_reason = 'PROCESSING_FAILURE',
			    failure_category = :category, failure_message = :message,
			    failure_occurred_at = :occurredAt
			where claim_id = :claimId and slot_id = :slotId
			  and ended_at is null and invalidated_at is null
			""", nativeQuery = true)
	int fail(
			@Param("slotId") UUID slotId,
			@Param("claimId") UUID claimId,
			@Param("category") String category,
			@Param("message") String message,
			@Param("occurredAt") Instant occurredAt,
			@Param("now") Instant now);
}
