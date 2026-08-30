package com.kartaguez.pocoma.domain.consumption.claim;

import static java.util.Objects.requireNonNull;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;

/** Versioned optimistic-concurrency pivot for one consumption. */
public record ConsumptionSlot(
		UUID slotId,
		ConsumptionKey consumptionKey,
		long revision,
		ConsumptionStatus status,
		Optional<TerminalOutcome> terminalOutcome,
		Optional<ClaimId> currentClaimId,
		Instant nextClaimAt,
		Instant createdAt,
		Optional<Instant> doneAt) {

	public ConsumptionSlot {
		requireNonNull(slotId, "slotId must not be null");
		requireNonNull(consumptionKey, "consumptionKey must not be null");
		if (revision < 0) {
			throw new IllegalArgumentException("revision must not be negative");
		}
		requireNonNull(status, "status must not be null");
		terminalOutcome = requireNonNull(terminalOutcome, "terminalOutcome must not be null");
		currentClaimId = requireNonNull(currentClaimId, "currentClaimId must not be null");
		requireNonNull(nextClaimAt, "nextClaimAt must not be null");
		requireNonNull(createdAt, "createdAt must not be null");
		doneAt = requireNonNull(doneAt, "doneAt must not be null");
		if (nextClaimAt.isBefore(createdAt)) {
			throw new IllegalArgumentException("nextClaimAt must not be before createdAt");
		}
		if (status == ConsumptionStatus.PENDING && (terminalOutcome.isPresent() || doneAt.isPresent())) {
			throw new IllegalArgumentException("a PENDING slot cannot have a terminal outcome or doneAt");
		}
		if (status == ConsumptionStatus.DONE
				&& (terminalOutcome.isEmpty() || doneAt.isEmpty() || currentClaimId.isPresent())) {
			throw new IllegalArgumentException("a DONE slot requires an outcome and doneAt and cannot have a claim");
		}
		doneAt.ifPresent(value -> {
			if (value.isBefore(createdAt)) {
				throw new IllegalArgumentException("doneAt must not be before createdAt");
			}
		});
	}

	public static ConsumptionSlot initial(UUID slotId, ConsumptionKey key, Instant createdAt) {
		return new ConsumptionSlot(slotId, key, 0, ConsumptionStatus.PENDING, Optional.empty(), Optional.empty(),
				createdAt, createdAt, Optional.empty());
	}

	/** Compatibility factory for the persistence-free legacy engine. */
	@Deprecated(forRemoval = true)
	public static ConsumptionSlot initial(ConsumptionKey key) {
		requireNonNull(key, "key must not be null");
		return initial(UUID.nameUUIDFromBytes(key.toString().getBytes(StandardCharsets.UTF_8)), key, Instant.EPOCH);
	}

	public ConsumptionSlot withCurrentClaim(ClaimId claimId) {
		requirePending();
		return pending(revision + 1, Optional.of(requireNonNull(claimId, "claimId must not be null")), nextClaimAt);
	}

	public ConsumptionSlot releaseCurrentClaim(ClaimId expectedClaimId, Instant claimableAt) {
		requireCurrentClaim(expectedClaimId);
		requireNonNull(claimableAt, "claimableAt must not be null");
		if (claimableAt.isBefore(createdAt)) {
			throw new IllegalArgumentException("claimableAt must not be before createdAt");
		}
		return pending(revision + 1, Optional.empty(), claimableAt);
	}

	public ConsumptionSlot complete(ClaimId expectedClaimId, TerminalOutcome outcome, Instant completedAt) {
		requireCurrentClaim(expectedClaimId);
		requireNonNull(outcome, "outcome must not be null");
		if (outcome == TerminalOutcome.ABANDONED) {
			throw new IllegalArgumentException("ABANDONED is only produced by abandon");
		}
		return done(revision + 1, outcome, completedAt);
	}

	public ConsumptionSlot abandon(Instant abandonedAt) {
		requirePending();
		return done(revision + 1, TerminalOutcome.ABANDONED, abandonedAt);
	}

	@Deprecated(forRemoval = true)
	public ConsumptionSlot acquired() {
		requirePending();
		return pending(revision + 1, currentClaimId, nextClaimAt);
	}

	@Deprecated(forRemoval = true)
	public ConsumptionSlot completed() {
		requirePending();
		return done(revision + 1, TerminalOutcome.SUCCESS, createdAt);
	}

	@Deprecated(forRemoval = true)
	public ConsumptionSlot failed() {
		requirePending();
		return done(revision + 1, TerminalOutcome.FAILED, createdAt);
	}

	@Deprecated(forRemoval = true)
	public ConsumptionSlot released() {
		requirePending();
		return pending(revision + 1, Optional.empty(), nextClaimAt);
	}

	private ConsumptionSlot pending(long targetRevision, Optional<ClaimId> claimId, Instant claimableAt) {
		return new ConsumptionSlot(slotId, consumptionKey, targetRevision, ConsumptionStatus.PENDING,
				Optional.empty(), claimId, claimableAt, createdAt, Optional.empty());
	}

	private ConsumptionSlot done(long targetRevision, TerminalOutcome outcome, Instant completedAt) {
		requireNonNull(completedAt, "completedAt must not be null");
		return new ConsumptionSlot(slotId, consumptionKey, targetRevision, ConsumptionStatus.DONE,
				Optional.of(outcome), Optional.empty(), nextClaimAt, createdAt, Optional.of(completedAt));
	}

	private void requirePending() {
		if (status != ConsumptionStatus.PENDING) {
			throw new IllegalStateException("a terminal consumption slot cannot transition");
		}
	}

	private void requireCurrentClaim(ClaimId expectedClaimId) {
		requirePending();
		if (!currentClaimId.equals(Optional.of(requireNonNull(expectedClaimId, "expectedClaimId must not be null")))) {
			throw new IllegalStateException("claim is not current");
		}
	}
}
