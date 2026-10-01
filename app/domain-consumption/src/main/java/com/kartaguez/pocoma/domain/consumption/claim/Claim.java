package com.kartaguez.pocoma.domain.consumption.claim;

import static java.util.Objects.requireNonNull;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

/** One immutable snapshot in the claim history of a consumption. */
public record Claim(
		ClaimId claimId,
		UUID slotId,
		WorkerId claimedBy,
		int attemptNumber,
		Instant claimedAt,
		Instant leaseUntil,
		Optional<Instant> invalidatedAt,
		Optional<Instant> endedAt,
		Optional<ProcessingFailure> failure,
		Optional<ClaimEndReason> endReason,
		Optional<ConsumptionKey> compatibilityKey) {

	public Claim {
		requireNonNull(claimId, "claimId must not be null");
		requireNonNull(slotId, "slotId must not be null");
		requireNonNull(claimedBy, "claimedBy must not be null");
		if (attemptNumber < 1) {
			throw new IllegalArgumentException("attemptNumber must be greater than or equal to 1");
		}
		requireNonNull(claimedAt, "claimedAt must not be null");
		requireNonNull(leaseUntil, "leaseUntil must not be null");
		invalidatedAt = requireNonNull(invalidatedAt, "invalidatedAt must not be null");
		endedAt = requireNonNull(endedAt, "endedAt must not be null");
		failure = requireNonNull(failure, "failure must not be null");
		endReason = requireNonNull(endReason, "endReason must not be null");
		compatibilityKey = requireNonNull(compatibilityKey, "compatibilityKey must not be null");
		if (!leaseUntil.isAfter(claimedAt)) {
			throw new IllegalArgumentException("leaseUntil must be after claimedAt");
		}
		invalidatedAt.ifPresent(value -> requireNotBeforeClaimedAt(value, "invalidatedAt", claimedAt));
		endedAt.ifPresent(value -> requireNotBeforeClaimedAt(value, "endedAt", claimedAt));
		if (invalidatedAt.isPresent() && endedAt.isPresent()) {
			throw new IllegalArgumentException("a claim cannot be both ended and invalidated");
		}
		boolean closed = invalidatedAt.isPresent() || endedAt.isPresent();
		if (closed != endReason.isPresent()) {
			throw new IllegalArgumentException("a closed claim requires an endReason and an open claim forbids one");
		}
		if (failure.isPresent() != endReason.filter(reason -> reason == ClaimEndReason.PROCESSING_FAILURE).isPresent()) {
			throw new IllegalArgumentException("failure is only valid and required for PROCESSING_FAILURE");
		}
		if (endReason.isPresent()) {
			validateClosure(endReason.orElseThrow(), invalidatedAt, endedAt);
		}
	}

	public static Claim active(
			ClaimId claimId,
			UUID slotId,
			WorkerId workerId,
			int attemptNumber,
			Instant claimedAt,
			ClaimLease lease) {
		requireNonNull(lease, "lease must not be null");
		return new Claim(claimId, slotId, workerId, attemptNumber, claimedAt, claimedAt.plus(lease.duration()),
				Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
	}

	/** Compatibility factory for callers that still identify a Claim by its ConsumptionKey. */
	@Deprecated(forRemoval = true)
	public static Claim active(
			ClaimId claimId,
			ConsumptionKey key,
			WorkerId workerId,
			int attemptNumber,
			Instant claimedAt,
			ClaimLease lease) {
		Claim claim = active(claimId, ConsumptionSlot.legacySlotIdFor(key), workerId, attemptNumber, claimedAt, lease);
		return claim.withCompatibilityKey(key);
	}

	/** Compatibility overload. The distinct token is deliberately not retained. */
	@Deprecated(forRemoval = true)
	public static Claim active(
			ClaimId claimId,
			ConsumptionKey key,
			ClaimToken ignoredToken,
			WorkerId workerId,
			Instant claimedAt,
			ClaimLease lease) {
		requireNonNull(ignoredToken, "token must not be null");
		return active(claimId, key, workerId, 1, claimedAt, lease);
	}

	@Deprecated(forRemoval = true)
	public ClaimToken token() {
		return ClaimToken.from(claimId);
	}

	/** Legacy context only. New code must navigate through {@link #slotId()}. */
	@Deprecated(forRemoval = true)
	public ConsumptionKey consumptionKey() {
		return compatibilityKey.orElseThrow(() ->
				new IllegalStateException("ConsumptionKey is not retained by target Claims; load the slot by slotId"));
	}

	/** Attaches non-persisted context for legacy processing adapters. */
	@Deprecated(forRemoval = true)
	public Claim withCompatibilityKey(ConsumptionKey key) {
		return new Claim(claimId, slotId, claimedBy, attemptNumber, claimedAt, leaseUntil, invalidatedAt,
				endedAt, failure, endReason, Optional.of(requireNonNull(key, "key must not be null")));
	}

	public boolean isOpen() {
		return invalidatedAt.isEmpty() && endedAt.isEmpty();
	}

	public boolean isActiveAt(Instant instant) {
		requireNonNull(instant, "instant must not be null");
		return isOpen() && !isLeaseExpiredAt(instant);
	}

	public boolean isLeaseExpiredAt(Instant instant) {
		requireNonNull(instant, "instant must not be null");
		return !leaseUntil.isAfter(instant);
	}

	@Deprecated(forRemoval = true)
	public boolean isOwnedBy(ClaimToken candidateToken, Instant instant) {
		return token().equals(requireNonNull(candidateToken, "candidateToken must not be null")) && isActiveAt(instant);
	}

	public Claim succeedAt(Instant instant) {
		return endAt(instant, ClaimEndReason.SUCCESS, Optional.empty());
	}

	public Claim rejectAt(Instant instant) {
		return endAt(instant, ClaimEndReason.REJECTED, Optional.empty());
	}

	public Claim releaseAt(Instant instant) {
		return endAt(instant, ClaimEndReason.RELEASED, Optional.empty());
	}

	public Claim invalidateForTakeoverAt(Instant instant) {
		return invalidateAt(instant, ClaimEndReason.TAKEN_OVER);
	}

	public Claim invalidateForAbandonAt(Instant instant) {
		return invalidateAt(instant, ClaimEndReason.ABANDONED);
	}

	@Deprecated(forRemoval = true)
	public Claim endAt(Instant instant) {
		return succeedAt(instant);
	}

	public Claim failAt(Instant instant, ProcessingFailure processingFailure) {
		return endAt(instant, ClaimEndReason.PROCESSING_FAILURE,
				Optional.of(requireNonNull(processingFailure, "processingFailure must not be null")));
	}

	@Deprecated(forRemoval = true)
	public Claim invalidateAt(Instant instant) {
		return invalidateForTakeoverAt(instant);
	}

	private Claim endAt(Instant instant, ClaimEndReason reason, Optional<ProcessingFailure> processingFailure) {
		requireOpen();
		requireNonNull(instant, "instant must not be null");
		return new Claim(claimId, slotId, claimedBy, attemptNumber, claimedAt, leaseUntil,
				Optional.empty(), Optional.of(instant), processingFailure, Optional.of(reason), compatibilityKey);
	}

	private Claim invalidateAt(Instant instant, ClaimEndReason reason) {
		requireOpen();
		requireNonNull(instant, "instant must not be null");
		return new Claim(claimId, slotId, claimedBy, attemptNumber, claimedAt, leaseUntil,
				Optional.of(instant), Optional.empty(), Optional.empty(), Optional.of(reason), compatibilityKey);
	}

	private void requireOpen() {
		if (!isOpen()) {
			throw new IllegalStateException("a closed claim cannot transition");
		}
	}

	private static void requireNotBeforeClaimedAt(Instant value, String field, Instant claimedAt) {
		if (value.isBefore(claimedAt)) {
			throw new IllegalArgumentException(field + " must not be before claimedAt");
		}
	}

	private static void validateClosure(
			ClaimEndReason reason, Optional<Instant> invalidatedAt, Optional<Instant> endedAt) {
		boolean invalidation = reason == ClaimEndReason.TAKEN_OVER || reason == ClaimEndReason.ABANDONED;
		if (invalidation != invalidatedAt.isPresent() || invalidation == endedAt.isPresent()) {
			throw new IllegalArgumentException("endReason is inconsistent with claim closure timestamps");
		}
	}
}
