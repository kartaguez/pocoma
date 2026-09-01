package com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;

@Repository
public class JpaEventConsumptionDiscoveryRepository {
	private final JdbcTemplate jdbc;

	public JpaEventConsumptionDiscoveryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<EventCandidateRow> findNextEligible(PipelineDefinition pipeline, Instant now,
			Optional<EventOrderingKey> afterExclusive, int limit) {
		String cursor = afterExclusive.isPresent() ? """
				and ((event.version > ?)
				 or (event.version = ? and event.created_at > ?)
				 or (event.version = ? and event.created_at = ?
				     and event.id::text > ?))
				""" : "";
		String sql = """
				select event.id, event.pot_id, event.version, event.created_at
				from business_event_outbox event
				left join consumption_slots slot
				  on slot.consumable_type = 'EVENT'
				 and slot.consumable_components = jsonb_build_array(event.id::text)
				 and slot.consumer_type = 'PIPELINE'
				 and slot.consumer_components = jsonb_build_array(
				     cast(? as text), cast(? as text))
				left join consumption_claims claim
				  on claim.slot_id = slot.slot_id and claim.claim_id = slot.current_claim_id
				where (slot.slot_id is null or (
				       slot.status = 'PENDING' and slot.next_claim_at <= ?
				       and (slot.current_claim_id is null or claim.lease_until <= ?)))
				%s
				order by event.version, event.created_at, cast(event.id as varchar)
				limit ?
				""".formatted(cursor);
		List<Object> parameters = new ArrayList<>();
		parameters.add(pipeline.pipelineId().value());
		parameters.add(Integer.toString(pipeline.pipelineVersion()));
		parameters.add(Timestamp.from(now));
		parameters.add(Timestamp.from(now));
		afterExclusive.ifPresent(value -> {
			parameters.add(value.appliesAtVersion());
			parameters.add(value.appliesAtVersion());
			parameters.add(Timestamp.from(value.createdAt()));
			parameters.add(value.appliesAtVersion());
			parameters.add(Timestamp.from(value.createdAt()));
			parameters.add(value.eventId().toString());
		});
		parameters.add(limit);
		return jdbc.query(sql, (result, rowNumber) -> new EventCandidateRow(
				result.getObject("id", UUID.class), result.getObject("pot_id", UUID.class),
				result.getLong("version"), result.getTimestamp("created_at").toInstant()), parameters.toArray());
	}

	public record EventCandidateRow(UUID eventId, UUID potId, long version, Instant createdAt) {}
}
