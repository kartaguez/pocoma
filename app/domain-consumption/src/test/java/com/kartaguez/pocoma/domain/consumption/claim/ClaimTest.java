package com.kartaguez.pocoma.domain.consumption.claim;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.key.CommandConsumptionKey;

class ClaimTest {

	private static final Instant NOW = Instant.parse("2026-08-27T10:00:00Z");

	@Test
	void ownershipRequiresMatchingTokenAndLiveLease() {
		ClaimToken token = ClaimToken.generate();
		Claim claim = Claim.active(ClaimId.generate(), new CommandConsumptionKey(UUID.randomUUID()), token,
				new WorkerId("worker-1"), NOW, new ClaimLease(Duration.ofSeconds(30)));

		assertTrue(claim.isOwnedBy(token, NOW.plusSeconds(29)));
		assertFalse(claim.isOwnedBy(ClaimToken.generate(), NOW.plusSeconds(1)));
		assertFalse(claim.isOwnedBy(token, NOW.plusSeconds(30)));
		assertFalse(claim.endAt(NOW.plusSeconds(2)).isActiveAt(NOW.plusSeconds(3)));
		assertFalse(claim.invalidateAt(NOW.plusSeconds(2)).isActiveAt(NOW.plusSeconds(3)));
	}
}
