package com.kartaguez.pocoma.domain.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class BalanceTest {

	@Test
	void acceptsSignedValues() {
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());

		assertEquals(Fraction.of(-1, 2), new Balance(shareholderId, Fraction.of(-1, 2)).value());
		assertEquals(Fraction.ZERO, new Balance(shareholderId, Fraction.ZERO).value());
		assertEquals(Fraction.of(3, 2), new Balance(shareholderId, Fraction.of(3, 2)).value());
	}

	@Test
	void rejectsNullShareholderId() {
		assertThrows(NullPointerException.class, () -> new Balance(null, Fraction.ZERO));
	}

	@Test
	void rejectsNullValue() {
		assertThrows(NullPointerException.class, () -> new Balance(ShareholderId.of(UUID.randomUUID()), null));
	}
}
