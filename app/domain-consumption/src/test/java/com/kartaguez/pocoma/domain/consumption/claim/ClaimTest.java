package com.kartaguez.pocoma.domain.consumption.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

class ClaimTest {

	private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");

	@Test
	void exposesNormalFailureAndInvalidationHistories() {
		ClaimToken token = ClaimToken.generate();
		Claim claim = Claim.active(ClaimId.generate(), new ConsumptionKey("work", List.of("42")), token,
				new WorkerId("worker-1"), NOW, new ClaimLease(Duration.ofSeconds(30)));
		ProcessingFailure failure = new ProcessingFailure("business", "failed", NOW.plusSeconds(2));

		assertTrue(claim.failure().isEmpty());
		assertTrue(claim.isOwnedBy(token, NOW.plusSeconds(29)));
		assertFalse(claim.isOwnedBy(ClaimToken.generate(), NOW.plusSeconds(1)));
		assertFalse(claim.isOwnedBy(token, NOW.plusSeconds(30)));
		Claim ended = claim.endAt(NOW.plusSeconds(2));
		Claim failed = claim.failAt(NOW.plusSeconds(2), failure);
		Claim invalidated = claim.invalidateAt(NOW.plusSeconds(30));
		assertTrue(ended.failure().isEmpty());
		assertFalse(ended.isActiveAt(NOW.plusSeconds(3)));
		assertEquals(failure, failed.failure().orElseThrow());
		assertFalse(failed.isActiveAt(NOW.plusSeconds(3)));
		assertFalse(invalidated.isActiveAt(NOW.plusSeconds(30)));
		assertEquals(claim.claimId(), invalidated.claimId());
		assertEquals(token, invalidated.token());
		assertEquals(claim.leaseUntil(), invalidated.leaseUntil());
	}
}
