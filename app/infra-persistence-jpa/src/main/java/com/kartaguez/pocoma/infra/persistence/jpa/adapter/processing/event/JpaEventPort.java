package com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.processing.event.ordering.EventOrderingKey;
import com.kartaguez.pocoma.engine.processing.segmentation.PartitionHash;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.BusinessEventRecordMapper;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaBusinessEventOutboxEntity;

import jakarta.persistence.EntityManager;

/** Best-effort cursor read. It deliberately ignores the legacy global Event processing status. */
@Component
public class JpaEventPort implements EventPort {
	private static final int PAGE_SIZE = 100;
	private final EntityManager entityManager;
	private final BusinessEventRecordMapper mapper;

	public JpaEventPort(EntityManager entityManager, ObjectMapper objectMapper) {
		this.entityManager = requireNonNull(entityManager, "entityManager must not be null");
		this.mapper = new BusinessEventRecordMapper(requireNonNull(objectMapper, "objectMapper must not be null"));
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<RecordedEvent<? extends BusinessEvent>> findNextCandidate(
			PipelineDefinition pipeline, WorkerSegment segment, Optional<EventOrderingKey> afterExclusive) {
		requireNonNull(pipeline, "pipeline must not be null");
		requireNonNull(segment, "segment must not be null");
		Optional<EventOrderingKey> cursor = requireNonNull(afterExclusive, "afterExclusive must not be null");
		while (true) {
			List<JpaBusinessEventOutboxEntity> page = page(cursor);
			if (page.isEmpty()) return Optional.empty();
			for (JpaBusinessEventOutboxEntity entity : page) {
				var envelope = entity.toEnvelope();
				cursor = Optional.of(new EventOrderingKey(envelope.version(), envelope.createdAt(), envelope.id()));
				if (segment.owns(PartitionHash.forPipelinePot(
						pipeline.pipelineId().value(), envelope.potId().value()))) {
					return Optional.of(mapper.toRecordedEvent(envelope));
				}
			}
		}
	}

	private List<JpaBusinessEventOutboxEntity> page(Optional<EventOrderingKey> cursor) {
		String cursorPredicate = cursor.isPresent() ? """
				where (version > :version)
				   or (version = :version and created_at > :createdAt)
				   or (version = :version and created_at = :createdAt and cast(id as varchar) > :eventId)
				""" : "";
		var query = entityManager.createNativeQuery("""
				select * from business_event_outbox
				%s
				order by version, created_at, cast(id as varchar)
				limit %d
				""".formatted(cursorPredicate, PAGE_SIZE), JpaBusinessEventOutboxEntity.class);
		cursor.ifPresent(value -> {
			query.setParameter("version", value.appliesAtVersion());
			query.setParameter("createdAt", value.createdAt());
			query.setParameter("eventId", value.eventId().toString());
		});
		@SuppressWarnings("unchecked")
		List<JpaBusinessEventOutboxEntity> result = query.getResultList();
		return result;
	}
}
