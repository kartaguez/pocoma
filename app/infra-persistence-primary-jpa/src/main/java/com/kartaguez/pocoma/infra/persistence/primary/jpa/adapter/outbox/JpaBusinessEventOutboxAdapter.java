package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventTransactionRunner;

import jakarta.persistence.EntityManagerFactory;

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
import com.kartaguez.pocoma.contracts.observability.trace.TraceContextHolder;

@Component("jpaBusinessEventOutboxAdapter")
public class JpaBusinessEventOutboxAdapter implements BusinessEventAppendPort {

	private final EntityManagerFactory emf;
	private final BusinessEventRecordMapper eventRecordMapper;

	public JpaBusinessEventOutboxAdapter(EntityManagerFactory emf, ObjectMapper objectMapper) {
		this.emf = Objects.requireNonNull(emf, "emf must not be null");
		this.eventRecordMapper = new BusinessEventRecordMapper(
				Objects.requireNonNull(objectMapper, "objectMapper must not be null"));
	}

	@Override
	@Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
	public UUID append(BusinessEvent event) {
        return PrimaryEventTransactionRunner.guardedWrite(emf, () -> {
		Objects.requireNonNull(event, "event must not be null");
		TraceContext traceContext = TraceContext.fromCurrent();
		RecordedEvent<BusinessEvent> recordedEvent = new RecordedEvent<>(
				UUID.randomUUID(),
				event,
				Instant.now(),
				EventTraceMetadata.of(traceContext.traceId(), traceContext.commandCommittedAtNanos()));
		com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventInsert.persistNew(emf,
                new JpaBusinessEventOutboxEntity(eventRecordMapper.toEnvelope(recordedEvent)));
        return recordedEvent.eventId();

        });
    }

	private record TraceContext(String traceId, Long commandCommittedAtNanos) {

		private static TraceContext fromCurrent() {
			return TraceContextHolder.current()
					.map(context -> new TraceContext(context.traceId(), context.commandCommittedAtNanos()))
					.orElseGet(() -> new TraceContext(null, null));
		}
	}
}
