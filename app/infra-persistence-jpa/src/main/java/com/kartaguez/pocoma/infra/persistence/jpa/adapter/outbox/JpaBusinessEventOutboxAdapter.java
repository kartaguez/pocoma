package com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.event.EventTraceMetadata;
import com.kartaguez.pocoma.engine.event.RecordedEvent;
import com.kartaguez.pocoma.domain.pot.event.BusinessEvent;
import com.kartaguez.pocoma.engine.port.out.event.BusinessEventAppendPort;
import com.kartaguez.pocoma.engine.model.BusinessEventClaim;
import com.kartaguez.pocoma.engine.legacy.processing.segmentation.ProjectionPartition;
import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxPort;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox.JpaBusinessEventOutboxEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;
import com.kartaguez.pocoma.observability.trace.TraceContextHolder;

@Component("jpaBusinessEventOutboxAdapter")
public class JpaBusinessEventOutboxAdapter implements BusinessEventAppendPort, BusinessEventOutboxPort {

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
	@Transactional
	public boolean heartbeat(UUID eventId, UUID claimToken, Duration leaseDuration) {
		Objects.requireNonNull(eventId, "eventId must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
		Objects.requireNonNull(leaseDuration, "leaseDuration must not be null");
		if (leaseDuration.isNegative() || leaseDuration.isZero()) {
			throw new IllegalArgumentException("leaseDuration must be positive");
		}
		return repository.heartbeat(eventId, claimToken, Instant.now().plus(leaseDuration)) == 1;
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

	private record TraceContext(String traceId, Long commandCommittedAtNanos) {

		private static TraceContext fromCurrent() {
			return TraceContextHolder.current()
					.map(context -> new TraceContext(context.traceId(), context.commandCommittedAtNanos()))
					.orElseGet(() -> new TraceContext(null, null));
		}
	}
}
