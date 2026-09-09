package com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pipeline.PipelineVersionDefinition;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventSchedulingOrderingKey;

@Repository
public class JpaEventConsumptionDiscoveryRepository {
	private final JdbcTemplate jdbc;

	public JpaEventConsumptionDiscoveryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<EventSchedulingCandidateRow> findNextEligible(Collection<PipelineVersionDefinition> suppliedDefinitions,
			Instant now, Optional<EventSchedulingOrderingKey> afterExclusive, int limit) {
		List<PipelineVersionDefinition> definitions = suppliedDefinitions.stream()
				.sorted(Comparator.comparing((PipelineVersionDefinition value) -> value.identity().pipelineId().value())
						.thenComparingInt(value -> value.identity().pipelineVersion()))
				.toList();
		if (definitions.isEmpty()) return List.of();
		String values = String.join(",", java.util.Collections.nCopies(definitions.size(),
				"(cast(? as varchar),cast(? as integer),cast(? as bigint),cast(? as bigint))"));
		String cursor = afterExclusive.isPresent() ? """
				and (event.version, event.created_at, event.id::text,
				     definition.pipeline_id, definition.pipeline_version) > (?,?,?,?,?)
				""" : "";
		String sql = """
				with definition(pipeline_id, pipeline_version, from_version, through_version) as (
				  values %s
				)
				select event.id, event.pot_id, event.version, event.created_at,
				       definition.pipeline_id, definition.pipeline_version
				from business_event_outbox event
				join definition on event.version >= definition.from_version
				 and (definition.through_version is null or event.version <= definition.through_version)
				left join tasks_4_pipeline task
				  on task.event_id = event.id
				 and task.pipeline_id = definition.pipeline_id
				 and task.pipeline_version = definition.pipeline_version
				left join consumption_slots slot
				  on slot.consumable_type = 'EVENT'
				 and slot.consumable_components = jsonb_build_array(event.id::text)
				 and slot.consumer_type = 'PROJECTION_TASK_SCHEDULER'
				 and slot.consumer_components = jsonb_build_array(
				     definition.pipeline_id, cast(definition.pipeline_version as text))
				left join consumption_claims claim
				  on claim.slot_id = slot.slot_id and claim.claim_id = slot.current_claim_id
				where task.id is null
				  and (slot.slot_id is null or (
				       slot.status = 'PENDING' and slot.next_claim_at <= ?
				       and (slot.current_claim_id is null or claim.lease_until <= ?)))
				%s
				order by event.version, event.created_at, cast(event.id as varchar),
				         definition.pipeline_id, definition.pipeline_version
				limit ?
				""".formatted(values, cursor);
		List<Object> parameters = new ArrayList<>();
		for (PipelineVersionDefinition definition : definitions) {
			parameters.add(definition.identity().pipelineId().value());
			parameters.add(definition.identity().pipelineVersion());
			parameters.add(definition.applicability().applicableFromVersion());
			parameters.add(definition.applicability().applicableThroughVersion().isPresent()
					? definition.applicability().applicableThroughVersion().getAsLong() : null);
		}
		parameters.add(Timestamp.from(now));
		parameters.add(Timestamp.from(now));
		afterExclusive.ifPresent(value -> {
			parameters.add(value.potVersion());
			parameters.add(Timestamp.from(value.createdAt()));
			parameters.add(value.eventId().toString());
			parameters.add(value.trigger().pipelineId().value());
			parameters.add(value.trigger().pipelineVersion());
		});
		parameters.add(limit);
		return jdbc.query(sql, (result, rowNumber) -> new EventSchedulingCandidateRow(
				result.getObject("id", UUID.class), result.getObject("pot_id", UUID.class),
				result.getLong("version"), result.getTimestamp("created_at").toInstant(),
				PipelineId.of(result.getString("pipeline_id")), result.getInt("pipeline_version")),
				parameters.toArray());
	}

	public List<EventCandidateRow> findNextEligibleForSourceVersionWatermark(Instant now,
			Optional<EventOrderingKey> afterExclusive, int limit) {
		String cursor = afterExclusive.isPresent() ? """
				and ((event.version > ?)
				 or (event.version = ? and event.created_at > ?)
				 or (event.version = ? and event.created_at = ? and event.id::text > ?))
				""" : "";
		String sql = """
				select event.id, event.pot_id, event.version, event.created_at
				from business_event_outbox event
				left join consumption_slots slot
				  on slot.consumable_type = 'EVENT'
				 and slot.consumable_components = jsonb_build_array(event.id::text)
				 and slot.consumer_type = 'SOURCE_VERSION_WATERMARK'
				 and slot.consumer_components = '[]'::jsonb
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
		return rows(sql, parameters);
	}

	private List<EventCandidateRow> rows(String sql, List<Object> parameters) {
		return jdbc.query(sql, (result, rowNumber) -> new EventCandidateRow(
				result.getObject("id", UUID.class), result.getObject("pot_id", UUID.class),
				result.getLong("version"), result.getTimestamp("created_at").toInstant()), parameters.toArray());
	}

	public record EventCandidateRow(UUID eventId, UUID potId, long version, Instant createdAt) {}
	public record EventSchedulingCandidateRow(UUID eventId, UUID potId, long version, Instant createdAt,
			PipelineId pipelineId, int pipelineVersion) {}
}
