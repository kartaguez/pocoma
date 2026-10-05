package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox;

import com.kartaguez.pocoma.contracts.observability.trace.TraceContext;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pot.event.EventTraceMetadata;
import com.kartaguez.pocoma.domain.pot.event.RecordedEvent;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.write.pot.port.event.BusinessEventAppendPort;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.outbox.JpaBusinessEventOutboxRepository;
import com.kartaguez.pocoma.contracts.observability.trace.TraceContextHolder;

@Component("jpaBusinessEventOutboxAdapter")
public class JpaBusinessEventOutboxAdapter implements BusinessEventAppendPort {

	private final JpaBusinessEventOutboxRepository repository;
	private final BusinessEventRecordMapper eventRecordMapper;

	public JpaBusinessEventOutboxAdapter(JpaBusinessEventOutboxRepository repository, ObjectMapper objectMapper) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
		this.eventRecordMapper = new BusinessEventRecordMapper(
				Objects.requireNonNull(objectMapper, "objectMapper must not be null"));
	}

	@Override
	@Transactional
	public void append(BusinessEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		TraceContext traceContext = TraceContext.fromCurrent();
		RecordedEvent<BusinessEvent> recordedEvent = new RecordedEvent<>(
				UUID.randomUUID(),
				event,
				Instant.now(),
				EventTraceMetadata.of(traceContext.traceId(), traceContext.commandCommittedAtNanos()));
		repository.save(new JpaBusinessEventOutboxEntity(eventRecordMapper.toEnvelope(recordedEvent)));
	}

	private record TraceContext(String traceId, Long commandCommittedAtNanos) {

		private static TraceContext fromCurrent() {
			return TraceContextHolder.current()
					.map(context -> new TraceContext(context.traceId(), context.commandCommittedAtNanos()))
					.orElseGet(() -> new TraceContext(null, null));
		}
	}
}
