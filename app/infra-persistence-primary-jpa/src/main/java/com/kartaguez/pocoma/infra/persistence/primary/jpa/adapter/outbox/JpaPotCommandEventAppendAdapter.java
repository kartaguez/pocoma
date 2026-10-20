package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox;

import com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventTransactionRunner;

import com.kartaguez.pocoma.port.transaction.EventRef;
import com.kartaguez.pocoma.port.transaction.EventFamily;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import com.kartaguez.pocoma.engine.consume.command.model.BusinessEventsAccepted;

import static java.util.Objects.requireNonNull;
import static org.springframework.transaction.annotation.Propagation.MANDATORY;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.event.BusinessEvent;
import com.kartaguez.pocoma.engine.consume.command.execution.CommandExecutionInvariantViolationException;
import com.kartaguez.pocoma.engine.consume.command.model.CommandExecutionArtifact;
import com.kartaguez.pocoma.engine.consume.command.model.CommandExecutionInput;
import com.kartaguez.pocoma.engine.consume.command.port.out.EventAppendPort;
import com.kartaguez.pocoma.domain.pot.event.EventTraceMetadata;
import com.kartaguez.pocoma.domain.pot.event.RecordedEvent;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.BusinessEventEnvelope;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.contracts.observability.trace.TraceContextHolder;

/** Appends typed Pot Events inside the winning Command consumption transaction. */
@Component
public class JpaPotCommandEventAppendAdapter implements EventAppendPort {

	private final EntityManagerFactory emf;
	private final BusinessEventRecordMapper mapper;
	private final Clock clock;

	@Autowired
	public JpaPotCommandEventAppendAdapter(
			EntityManagerFactory emf,
			ObjectMapper objectMapper) {
		this(emf, new BusinessEventRecordMapper(objectMapper), Clock.systemUTC());
	}

	JpaPotCommandEventAppendAdapter(
			EntityManagerFactory emf,
			BusinessEventRecordMapper mapper,
			Clock clock) {
		this.emf = requireNonNull(emf, "emf must not be null");
		this.mapper = requireNonNull(mapper, "mapper must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	@Transactional(propagation = MANDATORY)
	public BusinessEventsAccepted appendAll(List<BusinessEvent> events) {
        return PrimaryEventTransactionRunner.guardedWrite(emf, () -> {
		requireNonNull(events, "events must not be null");
		TraceMetadata trace = TraceMetadata.current();
		Instant recordedAt = clock.instant();
		List<BusinessEventEnvelope> envelopes = new ArrayList<>(events.size());
		for (BusinessEvent event : events) {
			if (!(requireNonNull(event, "events must not contain null")
					instanceof com.kartaguez.pocoma.domain.pot.event.BusinessEvent potEvent)) {
				throw new CommandExecutionInvariantViolationException(
						"Unsupported Command business event family: " + event.getClass().getName());
			}
			try {
				envelopes.add(mapper.toEnvelope(new RecordedEvent<>(UUID.randomUUID(), potEvent, recordedAt,
						EventTraceMetadata.of(trace.traceId(), trace.commandCommittedAtNanos()))));
			}
			catch (IllegalArgumentException exception) {
				throw new CommandExecutionInvariantViolationException(
						"Could not adapt Command business event " + event.getClass().getName(), exception);
			}
		}

		var em = java.util.Objects.requireNonNull(EntityManagerFactoryUtils.getTransactionalEntityManager(emf));
        envelopes.forEach(envelope -> com.kartaguez.pocoma.infra.persistence.primary.jpa.transaction.event.PrimaryEventInsert.persistNew(emf, new JpaBusinessEventOutboxEntity(envelope)));
        em.flush(); // CURRENT barrier before B is preserved.
        return new BusinessEventsAccepted(envelopes.stream().map(JpaPotCommandEventAppendAdapter::artifact).toList(),
                envelopes.stream().map(e -> new EventRef(EventFamily.BUSINESS_EVENT, e.id())).toList());

        });
    }

	private static CommandExecutionArtifact artifact(BusinessEventEnvelope envelope) {
		return new CommandExecutionArtifact(
				"EVENT",
				envelope.eventType(),
				envelope.id().toString(),
				OptionalLong.empty(),
				Optional.of(new CommandExecutionInput(
						"POT", envelope.potId().value().toString(), envelope.version())),
				envelope.createdAt());
	}

	private record TraceMetadata(String traceId, Long commandCommittedAtNanos) {
		private static TraceMetadata current() {
			return TraceContextHolder.current()
					.map(context -> new TraceMetadata(context.traceId(), context.commandCommittedAtNanos()))
					.orElseGet(() -> new TraceMetadata(null, null));
		}
	}
}
