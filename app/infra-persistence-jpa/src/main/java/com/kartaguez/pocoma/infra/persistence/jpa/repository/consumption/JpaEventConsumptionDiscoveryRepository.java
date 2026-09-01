package com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaBusinessEventOutboxEntity;

import jakarta.persistence.EntityManager;

@Repository
public class JpaEventConsumptionDiscoveryRepository {
	private static final int PAGE_SIZE = 128;
	private final EntityManager entityManager;

	public JpaEventConsumptionDiscoveryRepository(EntityManager entityManager) {
		this.entityManager = entityManager;
	}

	public List<JpaBusinessEventOutboxEntity> findNextEligible(PipelineDefinition pipeline, Instant now,
			Optional<EventOrderingKey> afterExclusive) {
		String cursor = afterExclusive.isPresent() ? """
				and ((event.version > :version)
				 or (event.version = :version and event.created_at > :createdAt)
				 or (event.version = :version and event.created_at = :createdAt
				     and cast(event.id as varchar) > :eventId))
				""" : "";
		var query = entityManager.createNativeQuery("""
				select event.*
				from business_event_outbox event
				left join consumption_slots slot
				  on slot.consumable_type = 'EVENT'
				 and slot.consumable_components = jsonb_build_array(event.id::text)
				 and slot.consumer_type = 'PIPELINE'
				 and slot.consumer_components = jsonb_build_array(
				     cast(:pipelineId as text), cast(:pipelineVersion as text))
				left join consumption_claims claim
				  on claim.slot_id = slot.slot_id and claim.claim_id = slot.current_claim_id
				where (slot.slot_id is null or (
				       slot.status = 'PENDING' and slot.next_claim_at <= :now
				       and (slot.current_claim_id is null or claim.lease_until <= :now)))
				%s
				order by event.version, event.created_at, cast(event.id as varchar)
				limit %d
				""".formatted(cursor, PAGE_SIZE), JpaBusinessEventOutboxEntity.class);
		query.setParameter("pipelineId", pipeline.pipelineId().value());
		query.setParameter("pipelineVersion", Integer.toString(pipeline.pipelineVersion()));
		query.setParameter("now", Timestamp.from(now));
		afterExclusive.ifPresent(value -> {
			query.setParameter("version", value.appliesAtVersion());
			query.setParameter("createdAt", Timestamp.from(value.createdAt()));
			query.setParameter("eventId", value.eventId().toString());
		});
		@SuppressWarnings("unchecked")
		List<JpaBusinessEventOutboxEntity> rows = query.getResultList();
		return rows;
	}
}
