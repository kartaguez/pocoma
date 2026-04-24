package com.kartaguez.pocoma.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.id.PotId;

class PotHeaderTest {

	@Test
	void createsPotHeader() {
		PotId id = PotId.of(UUID.randomUUID());
		Label label = Label.of("Trip");
		UserId creatorId = UserId.of(UUID.randomUUID());

		PotHeader potHeader = PotHeader.reconstitute(id, label, creatorId, false);

		assertEquals(id, potHeader.id());
		assertEquals(label, potHeader.label());
		assertEquals(creatorId, potHeader.creatorId());
		assertFalse(potHeader.deleted());
	}

	@Test
	void createsDeletedPotHeader() {
		PotHeaderFixture fixture = new PotHeaderFixture();

		PotHeader potHeader = PotHeader.reconstitute(fixture.id, fixture.label, fixture.creatorId, true);

		assertTrue(potHeader.deleted());
	}

	@Test
	void reconstitutesPotHeader() {
		PotHeaderFixture fixture = new PotHeaderFixture();

		PotHeader potHeader = PotHeader.reconstitute(fixture.id, fixture.label, fixture.creatorId, true);

		assertEquals(fixture.id, potHeader.id());
		assertEquals(fixture.label, potHeader.label());
		assertEquals(fixture.creatorId, potHeader.creatorId());
		assertTrue(potHeader.deleted());
	}

	@Test
	void marksAsDeleted() {
		PotHeaderFixture fixture = new PotHeaderFixture();
		PotHeader potHeader = PotHeader.reconstitute(fixture.id, fixture.label, fixture.creatorId, false);

		potHeader.markAsDeleted();

		assertTrue(potHeader.deleted());
	}

	@Test
	void updatesDetails() {
		PotHeaderFixture fixture = new PotHeaderFixture();
		PotHeader potHeader = PotHeader.reconstitute(fixture.id, fixture.label, fixture.creatorId, false);
		Label label = Label.of("Updated trip");

		potHeader.updateDetails(label);

		assertEquals(label, potHeader.label());
	}

	@Test
	void rejectsNullLabelWhenUpdatingDetails() {
		PotHeaderFixture fixture = new PotHeaderFixture();
		PotHeader potHeader = PotHeader.reconstitute(fixture.id, fixture.label, fixture.creatorId, false);

		assertThrows(NullPointerException.class, () -> potHeader.updateDetails(null));
	}

	@Test
	void rejectsMarkAsDeletedWhenDeleted() {
		PotHeaderFixture fixture = new PotHeaderFixture();
		PotHeader potHeader = PotHeader.reconstitute(fixture.id, fixture.label, fixture.creatorId, true);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				potHeader::markAsDeleted);

		assertEquals("POT_DELETED", exception.ruleCode());
	}

	@Test
	void rejectsUpdateDetailsWhenDeleted() {
		PotHeaderFixture fixture = new PotHeaderFixture();
		PotHeader potHeader = PotHeader.reconstitute(fixture.id, fixture.label, fixture.creatorId, true);

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> potHeader.updateDetails(Label.of("Updated trip")));

		assertEquals("POT_DELETED", exception.ruleCode());
	}

	@Test
	void rejectsNullId() {
		PotHeaderFixture fixture = new PotHeaderFixture();

		assertThrows(NullPointerException.class, () -> PotHeader.reconstitute(null, fixture.label, fixture.creatorId, false));
	}

	@Test
	void rejectsNullLabel() {
		PotHeaderFixture fixture = new PotHeaderFixture();

		assertThrows(NullPointerException.class, () -> PotHeader.reconstitute(fixture.id, null, fixture.creatorId, false));
	}

	@Test
	void rejectsNullCreatorId() {
		PotHeaderFixture fixture = new PotHeaderFixture();

		assertThrows(NullPointerException.class, () -> PotHeader.reconstitute(fixture.id, fixture.label, null, false));
	}

	private static final class PotHeaderFixture {
		private final PotId id = PotId.of(UUID.randomUUID());
		private final Label label = Label.of("Trip");
		private final UserId creatorId = UserId.of(UUID.randomUUID());
	}
}
