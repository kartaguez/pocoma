package com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption.JpaConsumptionSlotEntity;

public interface JpaConsumptionSlotRepository extends JpaRepository<JpaConsumptionSlotEntity, UUID> {

	@Modifying
	@Query(value = """
			insert into consumption_slots (
				slot_id, consumable_type, consumable_components, consumer_type, consumer_components,
				revision, last_attempt_number, status, next_claim_at, created_at
			) values (
				:slotId, :consumableType, cast(:consumableComponents as jsonb),
				:consumerType, cast(:consumerComponents as jsonb), 0, 0, 'PENDING', :now, :now
			)
			on conflict (consumable_type, consumable_components, consumer_type, consumer_components)
			do nothing
			""", nativeQuery = true)
	int insertInitial(
			@Param("slotId") UUID slotId,
			@Param("consumableType") String consumableType,
			@Param("consumableComponents") String consumableComponents,
			@Param("consumerType") String consumerType,
			@Param("consumerComponents") String consumerComponents,
			@Param("now") Instant now);

	@Query(value = """
			select * from consumption_slots
			where consumable_type = :consumableType
			  and consumable_components = cast(:consumableComponents as jsonb)
			  and consumer_type = :consumerType
			  and consumer_components = cast(:consumerComponents as jsonb)
			for update
			""", nativeQuery = true)
	Optional<JpaConsumptionSlotEntity> findByKeyForUpdate(
			@Param("consumableType") String consumableType,
			@Param("consumableComponents") String consumableComponents,
			@Param("consumerType") String consumerType,
			@Param("consumerComponents") String consumerComponents);

	@Query(value = "select * from consumption_slots where slot_id = :slotId for update", nativeQuery = true)
	Optional<JpaConsumptionSlotEntity> findByIdForUpdate(@Param("slotId") UUID slotId);

	@Query(value = """
			select * from consumption_slots
			where consumable_type = :consumableType
			  and consumable_components = cast(:consumableComponents as jsonb)
			  and consumer_type = :consumerType
			  and consumer_components = cast(:consumerComponents as jsonb)
			""", nativeQuery = true)
	Optional<JpaConsumptionSlotEntity> findByKey(
			@Param("consumableType") String consumableType,
			@Param("consumableComponents") String consumableComponents,
			@Param("consumerType") String consumerType,
			@Param("consumerComponents") String consumerComponents);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			update consumption_slots
			set current_claim_id = :claimId,
			    last_attempt_number = :attemptNumber,
			    revision = revision + 1
			where slot_id = :slotId and status = 'PENDING'
			""", nativeQuery = true)
	int installClaim(
			@Param("slotId") UUID slotId,
			@Param("claimId") UUID claimId,
			@Param("attemptNumber") int attemptNumber);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			update consumption_slots
			set status = 'DONE', terminal_outcome = :outcome, terminal_reason = :reason,
			    current_claim_id = null,
			    done_at = :doneAt, revision = revision + 1
			where slot_id = :slotId and status = 'PENDING' and current_claim_id = :claimId
			""", nativeQuery = true)
	int terminalize(
			@Param("slotId") UUID slotId,
			@Param("claimId") UUID claimId,
			@Param("outcome") String outcome,
			@Param("reason") String reason,
			@Param("doneAt") Instant doneAt);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			update consumption_slots
			set current_claim_id = null, next_claim_at = :nextClaimAt, revision = revision + 1
			where slot_id = :slotId and status = 'PENDING' and current_claim_id = :claimId
			""", nativeQuery = true)
	int scheduleRetry(
			@Param("slotId") UUID slotId,
			@Param("claimId") UUID claimId,
			@Param("nextClaimAt") Instant nextClaimAt);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query(value = """
			update consumption_slots
			set status = 'DONE', terminal_outcome = 'ABANDONED', terminal_reason = :reason,
			    current_claim_id = null,
			    done_at = :doneAt, revision = revision + 1
			where slot_id = :slotId and status = 'PENDING'
			""", nativeQuery = true)
	int abandon(
			@Param("slotId") UUID slotId,
			@Param("reason") String reason,
			@Param("doneAt") Instant doneAt);
}
