package com.kartaguez.pocoma.infra.persistence.jpa.entity.outbox;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;
import com.kartaguez.pocoma.engine.model.BusinessEventStatus;
import com.kartaguez.pocoma.engine.model.PotPartitioner;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "business_event_outbox")
public class JpaBusinessEventOutboxEntity {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "event_type", nullable = false, updatable = false)
	private String eventType;

	@Column(name = "pot_id", nullable = false, updatable = false)
	private UUID potId;

	@Column(name = "pot_partition_hash", nullable = false, updatable = false)
	private int potPartitionHash;

	@Column(name = "aggregate_id", nullable = false, updatable = false)
	private UUID aggregateId;

	@Column(name = "version", nullable = false, updatable = false)
	private long version;

	@Column(name = "payload_json", nullable = false, updatable = false)
	private String payloadJson;

	@Column(name = "trace_id", updatable = false)
	private String traceId;

	@Column(name = "command_committed_at_nanos", updatable = false)
	private Long commandCommittedAtNanos;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private BusinessEventStatus status;

	@Column(name = "claim_token")
	private UUID claimToken;

	@Column(name = "claimed_by")
	private String claimedBy;

	@Column(name = "lease_until")
	private Instant leaseUntil;

	@Column(name = "attempt_count", nullable = false)
	private int attemptCount;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "claimed_at")
	private Instant claimedAt;

	@Column(name = "accepted_at")
	private Instant acceptedAt;

	@Column(name = "started_at")
	private Instant startedAt;

	@Column(name = "processed_at")
	private Instant processedAt;

	@Column(name = "failed_at")
	private Instant failedAt;

	@Column(name = "last_error")
	private String lastError;

	protected JpaBusinessEventOutboxEntity() {
	}

	public JpaBusinessEventOutboxEntity(
			String eventType,
			UUID potId,
			UUID aggregateId,
			long version,
			String payloadJson,
			String traceId,
			Long commandCommittedAtNanos,
			Instant createdAt) {
		this.id = UUID.randomUUID();
		this.eventType = requireText(eventType, "eventType");
		this.potId = Objects.requireNonNull(potId, "potId must not be null");
		this.potPartitionHash = PotPartitioner.partitionHash(potId);
		this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId must not be null");
		this.version = version;
		this.payloadJson = requireText(payloadJson, "payloadJson");
		this.traceId = traceId;
		this.commandCommittedAtNanos = commandCommittedAtNanos;
		this.status = BusinessEventStatus.PENDING;
		this.attemptCount = 0;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
	}

	public BusinessEventEnvelope toEnvelope() {
		return new BusinessEventEnvelope(
				id,
				eventType,
				PotId.of(potId),
				aggregateId,
				version,
				payloadJson,
				traceId,
				commandCommittedAtNanos,
				createdAt);
	}

	public void claim(UUID claimToken, String workerId, Instant now, Instant leaseUntil) {
		this.status = BusinessEventStatus.CLAIMED;
		this.claimToken = Objects.requireNonNull(claimToken, "claimToken must not be null");
		this.claimedBy = requireText(workerId, "workerId");
		this.leaseUntil = Objects.requireNonNull(leaseUntil, "leaseUntil must not be null");
		this.claimedAt = Objects.requireNonNull(now, "now must not be null");
		this.acceptedAt = null;
		this.startedAt = null;
		this.attemptCount++;
		this.lastError = null;
	}

	public UUID id() {
		return id;
	}

	private static String requireText(String value, String name) {
		Objects.requireNonNull(value, name + " must not be null");
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
