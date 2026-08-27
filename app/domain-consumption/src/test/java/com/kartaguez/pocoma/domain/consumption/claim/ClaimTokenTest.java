package com.kartaguez.pocoma.domain.consumption.claim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class ClaimTokenTest {

	@Test
	void wrapsAClaimTokenValue() {
		UUID value = UUID.randomUUID();

		assertEquals(value, new ClaimToken(value).value());
	}

	@Test
	void generatesFreshTokens() {
		assertNotEquals(ClaimToken.generate(), ClaimToken.generate());
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> new ClaimToken(null));
	}
}
