package com.kartaguez.pocoma.domain.consumption.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

class ClaimTest {

	private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");
	private static final ConsumptionKey KEY = new ConsumptionKey("work", List.of("42"));

	@Test
	void leaseExpiryDoesNotCloseOrInvalidateTheClaim() {
		Claim claim = claim();
		assertFalse(claim.isLeaseExpiredAt(NOW.plusSeconds(29)));
		assertTrue(claim.isLeaseExpiredAt(NOW.plusSeconds(30)));
		assertTrue(claim.isOpen());
		assertTrue(claim.invalidatedAt().isEmpty());
		assertTrue(claim.endedAt().isEmpty());
	}

	@Test
	void recordsAllClosureHistories() {
		ProcessingFailure failure = new ProcessingFailure("technical", "failed", NOW.plusSeconds(2));
		assertEquals(ClaimEndReason.SUCCESS, claim().succeedAt(NOW.plusSeconds(2)).endReason().orElseThrow());
		assertEquals(ClaimEndReason.REJECTED, claim().rejectAt(NOW.plusSeconds(2)).endReason().orElseThrow());
		assertEquals(ClaimEndReason.RELEASED, claim().releaseAt(NOW.plusSeconds(2)).endReason().orElseThrow());
		Claim failed = claim().failAt(NOW.plusSeconds(2), failure);
		assertEquals(ClaimEndReason.PROCESSING_FAILURE, failed.endReason().orElseThrow());
		assertEquals(failure, failed.failure().orElseThrow());
		assertEquals(ClaimEndReason.TAKEN_OVER,
				claim().invalidateForTakeoverAt(NOW.plusSeconds(30)).endReason().orElseThrow());
		assertEquals(ClaimEndReason.ABANDONED,
				claim().invalidateForAbandonAt(NOW.plusSeconds(2)).endReason().orElseThrow());
	}

	@Test
	@SuppressWarnings("removal")
	void compatibilityTokenIsDerivedFromClaimId() {
		Claim claim = claim();
		assertEquals(claim.claimId(), claim.token().toClaimId());
		assertTrue(claim.isOwnedBy(claim.token(), NOW.plusSeconds(1)));
	}

	@Test
	void validatesAttemptAndPreventsASecondClosure() {
		assertThrows(IllegalArgumentException.class,
				() -> Claim.active(ClaimId.generate(), KEY, new WorkerId("worker"), 0, NOW,
						new ClaimLease(Duration.ofSeconds(30))));
		Claim ended = claim().succeedAt(NOW.plusSeconds(1));
		assertThrows(IllegalStateException.class, () -> ended.releaseAt(NOW.plusSeconds(2)));
	}

	private static Claim claim() {
		return Claim.active(ClaimId.generate(), KEY, new WorkerId("worker-1"), 3, NOW,
				new ClaimLease(Duration.ofSeconds(30)));
	}
}
