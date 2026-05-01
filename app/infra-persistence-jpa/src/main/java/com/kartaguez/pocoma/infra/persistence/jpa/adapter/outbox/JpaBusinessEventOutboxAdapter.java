package com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseDeletedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.ExpenseSharesUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotCreatedEvent;
import com.kartaguez.pocoma.engine.event.PotDeletedEvent;
import com.kartaguez.pocoma.engine.event.PotDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersAddedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersDetailsUpdatedEvent;
import com.kartaguez.pocoma.engine.event.PotShareholdersWeightsUpdatedEvent;
import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;
import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;
import com.kartaguez.pocoma.observability.event.EventMetadata;
import com.kartaguez.pocoma.observability.trace.TraceContextHolder;

@Component("jpaBusinessEventOutboxAdapter")
public class JpaBusinessEventOutboxAdapter implements BusinessEventOutboxPort {

	private final JpaBusinessEventOutboxRepository repository;

	public JpaBusinessEventOutboxAdapter(JpaBusinessEventOutboxRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
	}

	@Override
	@Transactional
	public void append(Object event) {
		Objects.requireNonNull(event, "event must not be null");
		EventProjection eventProjection = EventProjection.from(event);
		TraceContext traceContext = TraceContext.fromCurrent();
		repository.save(new JpaBusinessEventOutboxEntity(
				EventMetadata.type(event),
				eventProjection.potId().value(),
				eventProjection.aggregateId(),
				eventProjection.version(),
				payloadJson(event, eventProjection),
				traceContext.traceId(),
				traceContext.commandCommittedAtNanos(),
				Instant.now()));
	}

	@Override
	@Transactional
	public List<BusinessEventClaim> claimPending(int limit, Duration leaseDuration, String workerId) {
		return claimPending(limit, leaseDuration, workerId, ProjectionPartition.single());
	}

	@Override
	@Transactional
	public List<BusinessEventClaim> claimPending(
			int limit,
			Duration leaseDuration,
			String workerId,
			ProjectionPartition partition) {
		requirePositive(limit, "limit");
		Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
		Objects.requireNonNull(partition, "partition must not be null");
		if (leaseDuration.isNegative() || leaseDuration.isZero()) {
			throw new IllegalArgumentException("leaseDuration must be positive");
		}
		requireText(workerId, "workerId");
		Instant now = Instant.now();
		return repository.findClaimable(now, limit, partition.segmentIndex(), partition.segmentCount()).stream()
				.map(entity -> claim(entity, workerId, now, now.plus(leaseDuration)))
				.toList();
	}

	@Override
	@Transactional
	public boolean markAccepted(UUID eventId, UUID claimToken) {
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		return repository.markAccepted(eventId, claimToken, Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean markRunning(UUID eventId, UUID claimToken) {
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		return repository.markRunning(eventId, claimToken, Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean markDone(UUID eventId, UUID claimToken) {
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		return repository.markDone(eventId, claimToken, Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean markFailed(UUID eventId, UUID claimToken, String error) {
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		return repository.markFailed(eventId, claimToken, truncateError(error), Instant.now()) == 1;
	}

	@Override
	@Transactional
	public boolean release(UUID eventId, UUID claimToken) {
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		return repository.release(eventId, claimToken) == 1;
	}

	@Override
	@Transactional(readOnly = true)
	public long countPendingOrClaimed() {
		return repository.countPendingOrClaimed();
	}

	private BusinessEventClaim claim(
			JpaBusinessEventOutboxEntity entity,
			String workerId,
			Instant now,
			Instant leaseUntil) {
		UUID claimToken = UUID.randomUUID();
		entity.claim(claimToken, workerId, now, leaseUntil);
		return new BusinessEventClaim(entity.toEnvelope(), claimToken);
	}

	private static String payloadJson(Object event, EventProjection eventProjection) {
		return "{"
				+ "\"eventType\":\"" + escape(EventMetadata.type(event)) + "\","
				+ "\"potId\":\"" + eventProjection.potId().value() + "\","
				+ "\"aggregateId\":\"" + eventProjection.aggregateId() + "\","
				+ "\"version\":" + eventProjection.version()
				+ "}";
	}

	private static String escape(String value) {
		return value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static void requirePositive(int value, String name) {
		if (value < 1) {
			throw new IllegalArgumentException(name + " must be greater than or equal to 1");
		}
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

	private static String truncateError(String error) {
		if (error == null) {
			return null;
		}
		return error.length() <= 4000 ? error : error.substring(0, 4000);
	}

	private record EventProjection(PotId potId, UUID aggregateId, long version) {

		private static EventProjection from(Object event) {
			return switch (event) {
				case ExpenseCreatedEvent typed -> new EventProjection(
						typed.potId(),
						typed.expenseId().value(),
						typed.version());
				case ExpenseDeletedEvent typed -> new EventProjection(
						typed.potId(),
						typed.expenseId().value(),
						typed.version());
				case ExpenseDetailsUpdatedEvent typed -> new EventProjection(
						typed.potId(),
						typed.expenseId().value(),
						typed.version());
				case ExpenseSharesUpdatedEvent typed -> new EventProjection(
						typed.potId(),
						typed.expenseId().value(),
						typed.version());
				case PotCreatedEvent typed -> new EventProjection(typed.potId(), typed.potId().value(), typed.version());
				case PotDeletedEvent typed -> new EventProjection(typed.potId(), typed.potId().value(), typed.version());
				case PotDetailsUpdatedEvent typed -> new EventProjection(
						typed.potId(),
						typed.potId().value(),
						typed.version());
				case PotShareholdersAddedEvent typed -> new EventProjection(
						typed.potId(),
						typed.potId().value(),
						typed.version());
				case PotShareholdersDetailsUpdatedEvent typed -> new EventProjection(
						typed.potId(),
						typed.potId().value(),
						typed.version());
				case PotShareholdersWeightsUpdatedEvent typed -> new EventProjection(
						typed.potId(),
						typed.potId().value(),
						typed.version());
				default -> throw new IllegalArgumentException("Unsupported business event: " + event.getClass().getName());
			};
		}
	}

	private record TraceContext(String traceId, Long commandCommittedAtNanos) {

		private static TraceContext fromCurrent() {
			return TraceContextHolder.current()
					.map(context -> new TraceContext(context.traceId(), context.commandCommittedAtNanos()))
					.orElseGet(() -> new TraceContext(null, null));
		}
	}
}
