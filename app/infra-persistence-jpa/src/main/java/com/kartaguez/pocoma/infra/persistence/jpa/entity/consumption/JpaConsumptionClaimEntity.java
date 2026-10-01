package com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption;

import java.time.Instant;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimEndReason;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "consumption_claims")
public class JpaConsumptionClaimEntity {

	@Id
	@Column(name = "claim_id", nullable = false, updatable = false)
	private UUID claimId;

	@Column(name = "slot_id", nullable = false, updatable = false)
	private UUID slotId;

	@Column(name = "attempt_number", nullable = false, updatable = false)
	private int attemptNumber;

	@Column(name = "claimed_by", nullable = false, updatable = false)
	private String claimedBy;

	@Column(name = "claimed_at", nullable = false, updatable = false)
	private Instant claimedAt;

	@Column(name = "lease_until", nullable = false, updatable = false)
	private Instant leaseUntil;

	@Column(name = "ended_at")
	private Instant endedAt;

	@Column(name = "invalidated_at")
	private Instant invalidatedAt;

	@Column(name = "failure_category")
	private String failureCategory;

	@Column(name = "failure_code")
	private String failureCode;

	@Column(name = "failure_message")
	private String failureMessage;

	@Column(name = "failure_occurred_at")
	private Instant failureOccurredAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "end_reason")
	private ClaimEndReason endReason;

	protected JpaConsumptionClaimEntity() {
	}

	public JpaConsumptionClaimEntity(
			UUID claimId,
			UUID slotId,
			int attemptNumber,
			String claimedBy,
			Instant claimedAt,
			Instant leaseUntil) {
		this.claimId = claimId;
		this.slotId = slotId;
		this.attemptNumber = attemptNumber;
		this.claimedBy = claimedBy;
		this.claimedAt = claimedAt;
		this.leaseUntil = leaseUntil;
	}

	public UUID claimId() { return claimId; }
	public UUID slotId() { return slotId; }
	public int attemptNumber() { return attemptNumber; }
	public String claimedBy() { return claimedBy; }
	public Instant claimedAt() { return claimedAt; }
	public Instant leaseUntil() { return leaseUntil; }
	public Instant endedAt() { return endedAt; }
	public Instant invalidatedAt() { return invalidatedAt; }
	public String failureCategory() { return failureCategory; }
	public String failureCode() { return failureCode; }
	public String failureMessage() { return failureMessage; }
	public Instant failureOccurredAt() { return failureOccurredAt; }
	public ClaimEndReason endReason() { return endReason; }
}
