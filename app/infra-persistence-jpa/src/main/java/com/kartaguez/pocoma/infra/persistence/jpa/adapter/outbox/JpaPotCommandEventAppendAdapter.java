package com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox;

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
import com.kartaguez.pocoma.engine.command.execution.CommandExecutionInvariantViolationException;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionArtifact;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionInput;
import com.kartaguez.pocoma.engine.command.port.out.EventAppendPort;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.engine.legacy.event.BusinessEventEnvelope;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;
import com.kartaguez.pocoma.observability.trace.TraceContextHolder;

/** Appends typed Pot Events inside the winning Command consumption transaction. */
@Component
public class JpaPotCommandEventAppendAdapter implements EventAppendPort {

	private final JpaBusinessEventOutboxRepository repository;
	private final BusinessEventRecordMapper mapper;
	private final Clock clock;

	@Autowired
	public JpaPotCommandEventAppendAdapter(
			JpaBusinessEventOutboxRepository repository,
			ObjectMapper objectMapper) {
		this(repository, new BusinessEventRecordMapper(objectMapper), Clock.systemUTC());
	}

	JpaPotCommandEventAppendAdapter(
			JpaBusinessEventOutboxRepository repository,
			BusinessEventRecordMapper mapper,
			Clock clock) {
		this.repository = requireNonNull(repository, "repository must not be null");
		this.mapper = requireNonNull(mapper, "mapper must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	@Transactional(propagation = MANDATORY)
	public List<CommandExecutionArtifact> appendAll(List<BusinessEvent> events) {
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

		repository.saveAllAndFlush(envelopes.stream().map(JpaBusinessEventOutboxEntity::new).toList());
		return envelopes.stream().map(JpaPotCommandEventAppendAdapter::artifact).toList();
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
