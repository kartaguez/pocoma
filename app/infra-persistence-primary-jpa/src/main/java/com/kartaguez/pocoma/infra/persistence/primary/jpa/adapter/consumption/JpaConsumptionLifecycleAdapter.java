package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ConsumptionSlot;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.Acquired;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.AlreadyDone;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.Busy;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.NotReady;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionQueryPort;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.consumption.JpaConsumptionClaimEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.consumption.JpaConsumptionSlotEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.JpaConsumptionClaimRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.engine.exception.consumption.ConsumptionClaimClosureException;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization;
import java.util.function.IntSupplier;

/** PostgreSQL implementation of the target consumption persistence contracts. */
public class JpaConsumptionLifecycleAdapter
		implements ConsumptionLifecyclePersistencePort, ConsumptionQueryPort {

	private final JpaConsumptionSlotRepository slots;
	private final JpaConsumptionClaimRepository claims;
	private final ObjectMapper objectMapper;

	public JpaConsumptionLifecycleAdapter(
			JpaConsumptionSlotRepository slots,
			JpaConsumptionClaimRepository claims,
			ObjectMapper objectMapper) {
		this.slots = requireNonNull(slots, "slots must not be null");
		this.claims = requireNonNull(claims, "claims must not be null");
		this.objectMapper = requireNonNull(objectMapper, "objectMapper must not be null");
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public AcquireResult acquire(
			ConsumptionKey key, ClaimId claimId, WorkerId workerId, ClaimLease lease, Instant now) {
		requireNonNull(key, "key must not be null");
		requireNonNull(claimId, "claimId must not be null");
		requireNonNull(workerId, "workerId must not be null");
		requireNonNull(lease, "lease must not be null");
		requireNonNull(now, "now must not be null");

		KeyParameters parameters = parameters(key);
		int inserted = slots.insertInitial(
				UUID.randomUUID(),
				parameters.consumableType(),
				parameters.consumableComponents(),
				parameters.consumerType(),
				parameters.consumerComponents(),
				now);
		if (inserted != 0 && inserted != 1) {
			throw new IllegalStateException("create initial slot expected zero or one affected row but got " + inserted);
		}
		boolean created = inserted == 1;
		JpaConsumptionSlotEntity slot = slots.findByKeyForUpdate(
				parameters.consumableType(),
				parameters.consumableComponents(),
				parameters.consumerType(),
				parameters.consumerComponents())
				.orElseThrow(() -> new IllegalStateException("Slot disappeared after idempotent creation"));

		if (slot.status() == ConsumptionStatus.DONE) {
			return new AlreadyDone(
					requireNonNull(slot.terminalOutcome(), "DONE slot has no terminal outcome"),
					Optional.ofNullable(slot.terminalReason()).map(TerminalReason::new));
		}
		if (!created && now.isBefore(slot.nextClaimAt())) {
			return new NotReady(slot.nextClaimAt());
		}
		if (slot.currentClaimId() != null) {
			JpaConsumptionClaimEntity current = claims.findById(slot.currentClaimId())
					.orElseThrow(() -> new IllegalStateException("Current Claim does not exist"));
			if (!current.slotId().equals(slot.slotId())) {
				throw new IllegalStateException("Current Claim belongs to another slot");
			}
			if (current.leaseUntil().isAfter(now)) {
				return new Busy(current.leaseUntil());
			}
			requireExactlyOne(
					claims.invalidateForTakeover(slot.slotId(), current.claimId(), now),
					"invalidate expired Claim for takeover");
		}

		int attemptNumber = Math.addExact(slot.lastAttemptNumber(), 1);
		Instant leaseUntil = now.plus(lease.duration());
		claims.saveAndFlush(new JpaConsumptionClaimEntity(
				claimId.value(), slot.slotId(), attemptNumber, workerId.value(), now, leaseUntil));
		requireExactlyOne(
				slots.installClaim(slot.slotId(), claimId.value(), attemptNumber),
				"install current Claim");
		return new Acquired(Claim.active(claimId, slot.slotId(), workerId, attemptNumber, now, lease));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void terminalize(UUID slotId, ClaimId claimId,
			ConsumptionFinalization finalization, Instant now) {
		requireNonNull(slotId); requireNonNull(claimId); requireNonNull(finalization); requireNonNull(now);
		var outcome = finalization instanceof ConsumptionFinalization.Success
				? TerminalOutcome.SUCCESS
				: finalization instanceof ConsumptionFinalization.Rejected
					? TerminalOutcome.REJECTED : TerminalOutcome.FAILED;
		String reason = finalization instanceof ConsumptionFinalization.Rejected rejected
				? rejected.rejectionCode()
				: finalization instanceof ConsumptionFinalization.TerminalFailure terminal
					? terminal.failure().code().value() : null;
		requireWinningCas(slots.terminalize(slotId, claimId.value(), outcome.name(), reason, now), slotId, claimId);
		if (finalization instanceof ConsumptionFinalization.TerminalFailure terminal) {
			closeClaim(slotId, claimId, () -> failClaim(slotId, claimId, terminal.failure(), now));
		} else {
			closeClaim(slotId, claimId, () -> claims.end(slotId, claimId.value(), outcome.name(), now));
		}
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void scheduleRetry(UUID slotId, ClaimId claimId, ProcessingFailure failure, Instant nextClaimAt, Instant now) {
		requireNonNull(slotId); requireNonNull(claimId); requireNonNull(failure); requireNonNull(nextClaimAt); requireNonNull(now);
		requireWinningCas(slots.scheduleRetry(slotId, claimId.value(), nextClaimAt), slotId, claimId);
		closeClaim(slotId, claimId, () -> failClaim(slotId, claimId, failure, now));
	}

	private int failClaim(UUID slotId, ClaimId claimId, ProcessingFailure failure, Instant now) {
		return claims.fail(slotId, claimId.value(), failure.code().value(), failure.category(),
				failure.message(), failure.occurredAt(), now);
	}

	private static void requireWinningCas(int updated, UUID slotId, ClaimId claimId) {
		if (updated == 0) throw new LostClaimException(slotId, claimId);
		requireExactlyOne(updated, "Consumption CAS");
	}

	private static void closeClaim(UUID slotId, ClaimId claimId, IntSupplier close) {
		int updated;
		try { updated = close.getAsInt(); }
		catch (RuntimeException failure) {
			throw new ConsumptionClaimClosureException("close Claim", slotId, claimId, null, failure);
		}
		if (updated != 1) throw new ConsumptionClaimClosureException("close Claim", slotId, claimId, updated, null);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ConsumptionSlot> findSlot(UUID slotId) {
		return slots.findById(requireNonNull(slotId, "slotId must not be null")).map(this::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<ConsumptionSlot> findSlot(ConsumptionKey key) {
		KeyParameters parameters = parameters(requireNonNull(key, "key must not be null"));
		return slots.findByKey(
				parameters.consumableType(),
				parameters.consumableComponents(),
				parameters.consumerType(),
				parameters.consumerComponents()).map(this::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Claim> findClaim(ClaimId claimId) {
		return claims.findById(requireNonNull(claimId, "claimId must not be null").value()).map(this::toDomain);
	}

	@Override
	@Transactional(readOnly = true)
	public List<Claim> findClaims(UUID slotId) {
		return claims.findBySlotIdOrderByAttemptNumber(requireNonNull(slotId, "slotId must not be null"))
				.stream().map(this::toDomain).toList();
	}

	private ConsumptionSlot toDomain(JpaConsumptionSlotEntity entity) {
		ConsumptionKey key = new ConsumptionKey(
				new ConsumableIdentity(entity.consumableType(), entity.consumableComponents()),
				new ConsumerIdentity(entity.consumerType(), entity.consumerComponents()));
		return new ConsumptionSlot(
				entity.slotId(),
				key,
				entity.revision(),
				entity.status(),
				Optional.ofNullable(entity.terminalOutcome()),
				Optional.ofNullable(entity.terminalReason()).map(TerminalReason::new),
				Optional.ofNullable(entity.currentClaimId()).map(ClaimId::new),
				entity.nextClaimAt(),
				entity.createdAt(),
				Optional.ofNullable(entity.doneAt()));
	}

	private Claim toDomain(JpaConsumptionClaimEntity entity) {
		Optional<ProcessingFailure> failure = entity.failureCategory() == null
				? Optional.empty()
				: Optional.of(new ProcessingFailure(
						new ProcessingFailureCode(entity.failureCode()), entity.failureCategory(),
						entity.failureMessage(), entity.failureOccurredAt()));
		return new Claim(
				new ClaimId(entity.claimId()),
				entity.slotId(),
				new WorkerId(entity.claimedBy()),
				entity.attemptNumber(),
				entity.claimedAt(),
				entity.leaseUntil(),
				Optional.ofNullable(entity.invalidatedAt()),
				Optional.ofNullable(entity.endedAt()),
				failure,
				Optional.ofNullable(entity.endReason()));
	}

	private KeyParameters parameters(ConsumptionKey key) {
		return new KeyParameters(
				key.consumable().type(),
				writeJson(key.consumable().components()),
				key.consumer().type(),
				writeJson(key.consumer().components()));
	}

	private String writeJson(List<String> values) {
		try {
			return objectMapper.writeValueAsString(values);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize structural consumption identity", exception);
		}
	}

	private static void requireExactlyOne(int updated, String operation) {
		if (updated != 1) {
			throw new IllegalStateException(operation + " expected one affected row but got " + updated);
		}
	}

	private record KeyParameters(
			String consumableType,
			String consumableComponents,
			String consumerType,
			String consumerComponents) {
	}
}
