package com.kartaguez.pocoma.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class ShareholderTest {

	@Test
	void createsShareholder() {
		ShareholderId id = ShareholderId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		Name name = Name.of("Alice");
		Weight weight = Weight.of(new Fraction(1, 2));
		UserId userId = UserId.of(UUID.randomUUID());

		Shareholder shareholder = new Shareholder(id, potId, name, weight, userId, false);

		assertEquals(id, shareholder.id());
		assertEquals(potId, shareholder.potId());
		assertEquals(name, shareholder.name());
		assertEquals(weight, shareholder.weight());
		assertEquals(userId, shareholder.userId());
		assertFalse(shareholder.deleted());
	}

	@Test
	void createsShareholderWithoutUserId() {
		ShareholderFixture fixture = new ShareholderFixture();

		Shareholder shareholder = new Shareholder(
				fixture.id,
				fixture.potId,
				fixture.name,
				fixture.weight,
				null,
				true);

		assertNull(shareholder.userId());
		assertTrue(shareholder.deleted());
	}

	@Test
	void reconstitutesShareholder() {
		ShareholderFixture fixture = new ShareholderFixture();

		Shareholder shareholder = Shareholder.reconstitute(
				fixture.id,
				fixture.potId,
				fixture.name,
				fixture.weight,
				fixture.userId,
				true);

		assertEquals(fixture.id, shareholder.id());
		assertEquals(fixture.potId, shareholder.potId());
		assertEquals(fixture.name, shareholder.name());
		assertEquals(fixture.weight, shareholder.weight());
		assertEquals(fixture.userId, shareholder.userId());
		assertTrue(shareholder.deleted());
	}

	@Test
	void rejectsNullId() {
		ShareholderFixture fixture = new ShareholderFixture();

		assertThrows(NullPointerException.class, () -> new Shareholder(
				null,
				fixture.potId,
				fixture.name,
				fixture.weight,
				fixture.userId,
				false));
	}

	@Test
	void rejectsNullPotId() {
		ShareholderFixture fixture = new ShareholderFixture();

		assertThrows(NullPointerException.class, () -> new Shareholder(
				fixture.id,
				null,
				fixture.name,
				fixture.weight,
				fixture.userId,
				false));
	}

	@Test
	void rejectsNullName() {
		ShareholderFixture fixture = new ShareholderFixture();

		assertThrows(NullPointerException.class, () -> new Shareholder(
				fixture.id,
				fixture.potId,
				null,
				fixture.weight,
				fixture.userId,
				false));
	}

	@Test
	void rejectsNullWeight() {
		ShareholderFixture fixture = new ShareholderFixture();

		assertThrows(NullPointerException.class, () -> new Shareholder(
				fixture.id,
				fixture.potId,
				fixture.name,
				null,
				fixture.userId,
				false));
	}

	private static final class ShareholderFixture {
		private final ShareholderId id = ShareholderId.of(UUID.randomUUID());
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final Name name = Name.of("Alice");
		private final Weight weight = Weight.of(new Fraction(1, 2));
		private final UserId userId = UserId.of(UUID.randomUUID());
	}
}
