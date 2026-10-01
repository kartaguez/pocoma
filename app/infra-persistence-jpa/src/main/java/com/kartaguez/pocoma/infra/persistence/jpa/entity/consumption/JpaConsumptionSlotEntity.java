package com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "consumption_slots")
public class JpaConsumptionSlotEntity {

	@Id
	@Column(name = "slot_id", nullable = false, updatable = false)
	private UUID slotId;

	@Column(name = "consumable_type", nullable = false)
	private String consumableType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "consumable_components", nullable = false)
	private List<String> consumableComponents = new ArrayList<>();

	@Column(name = "consumer_type", nullable = false)
	private String consumerType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "consumer_components", nullable = false)
	private List<String> consumerComponents = new ArrayList<>();

	@Version
	@Column(name = "revision", nullable = false)
	private long revision;

	@Column(name = "last_attempt_number", nullable = false)
	private int lastAttemptNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false)
	private ConsumptionStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "terminal_outcome")
	private TerminalOutcome terminalOutcome;

	@Column(name = "terminal_reason")
	private String terminalReason;

	@Column(name = "current_claim_id")
	private UUID currentClaimId;

	@Column(name = "next_claim_at", nullable = false)
	private Instant nextClaimAt;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "done_at")
	private Instant doneAt;

	protected JpaConsumptionSlotEntity() {
	}

	public UUID slotId() { return slotId; }
	public String consumableType() { return consumableType; }
	public List<String> consumableComponents() { return List.copyOf(consumableComponents); }
	public String consumerType() { return consumerType; }
	public List<String> consumerComponents() { return List.copyOf(consumerComponents); }
	public long revision() { return revision; }
	public int lastAttemptNumber() { return lastAttemptNumber; }
	public ConsumptionStatus status() { return status; }
	public TerminalOutcome terminalOutcome() { return terminalOutcome; }
	public String terminalReason() { return terminalReason; }
	public UUID currentClaimId() { return currentClaimId; }
	public Instant nextClaimAt() { return nextClaimAt; }
	public Instant createdAt() { return createdAt; }
	public Instant doneAt() { return doneAt; }
}
