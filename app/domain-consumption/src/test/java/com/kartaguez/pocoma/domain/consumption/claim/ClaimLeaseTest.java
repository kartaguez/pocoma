package com.kartaguez.pocoma.domain.consumption.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ClaimLeaseTest {

	@Test
	void wrapsAPositiveDuration() {
		Duration duration = Duration.ofSeconds(30);

		assertEquals(duration, new ClaimLease(duration).duration());
	}

	@Test
	void rejectsNullZeroOrNegativeDuration() {
		assertThrows(NullPointerException.class, () -> new ClaimLease(null));
		assertThrows(IllegalArgumentException.class, () -> new ClaimLease(Duration.ZERO));
		assertThrows(IllegalArgumentException.class, () -> new ClaimLease(Duration.ofSeconds(-1)));
	}
}
