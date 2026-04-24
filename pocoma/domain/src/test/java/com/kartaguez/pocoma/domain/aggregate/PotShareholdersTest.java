package com.kartaguez.pocoma.domain.aggregate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

class PotShareholdersTest {

	@Test
	void createsPotShareholders() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder shareholder = shareholder(potId);
		Set<Shareholder> shareholders = Set.of(shareholder);

		PotShareholders potShareholders = new PotShareholders(potId, shareholders);

		assertEquals(potId, potShareholders.potId());
		assertEquals(Map.of(shareholder.id(), shareholder), potShareholders.shareholders());
		assertTrue(potShareholders.addedShareholderIds().isEmpty());
		assertTrue(potShareholders.updatedShareholderIds().isEmpty());
	}

	@Test
	void reconstitutesPotShareholders() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder shareholder = shareholder(potId);
		Set<Shareholder> shareholders = Set.of(shareholder);

		PotShareholders potShareholders = PotShareholders.reconstitute(potId, shareholders);

		assertEquals(potId, potShareholders.potId());
		assertEquals(Map.of(shareholder.id(), shareholder), potShareholders.shareholders());
		assertTrue(potShareholders.addedShareholderIds().isEmpty());
		assertTrue(potShareholders.updatedShareholderIds().isEmpty());
	}

	@Test
	void addsShareholder() {
		PotId potId = PotId.of(UUID.randomUUID());
		PotShareholders potShareholders = new PotShareholders(potId, Set.of());
		Name name = Name.of("Alice");
		Weight weight = Weight.of(new Fraction(1, 2));
		UserId userId = UserId.of(UUID.randomUUID());

		Shareholder shareholder = potShareholders.addShareholder(name, weight, userId);

		assertEquals(potId, shareholder.potId());
		assertEquals(name, shareholder.name());
		assertEquals(weight, shareholder.weight());
		assertEquals(userId, shareholder.userId());
		assertFalse(shareholder.deleted());
		assertEquals(Map.of(shareholder.id(), shareholder), potShareholders.shareholders());
		assertEquals(Set.of(shareholder.id()), potShareholders.addedShareholderIds());
		assertTrue(potShareholders.updatedShareholderIds().isEmpty());
	}

	@Test
	void addsShareholderWithoutUserId() {
		PotId potId = PotId.of(UUID.randomUUID());
		PotShareholders potShareholders = new PotShareholders(potId, Set.of());
		Name name = Name.of("Alice");
		Weight weight = Weight.of(new Fraction(1, 2));

		Shareholder shareholder = potShareholders.addShareholder(name, weight, null);

		assertNull(shareholder.userId());
		assertEquals(Map.of(shareholder.id(), shareholder), potShareholders.shareholders());
		assertEquals(Set.of(shareholder.id()), potShareholders.addedShareholderIds());
	}

	@Test
	void rejectsNullNameWhenAddingShareholder() {
		PotShareholders potShareholders = new PotShareholders(PotId.of(UUID.randomUUID()), Set.of());
		Weight weight = Weight.of(new Fraction(1, 2));

		assertThrows(NullPointerException.class, () -> potShareholders.addShareholder(null, weight, null));
	}

	@Test
	void rejectsNullWeightWhenAddingShareholder() {
		PotShareholders potShareholders = new PotShareholders(PotId.of(UUID.randomUUID()), Set.of());
		Name name = Name.of("Alice");

		assertThrows(NullPointerException.class, () -> potShareholders.addShareholder(name, null, null));
	}

	@Test
	void updatesShareholderDetails() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder shareholder = shareholder(potId);
		PotShareholders potShareholders = new PotShareholders(potId, Set.of(shareholder));
		Name name = Name.of("Bob");
		UserId userId = UserId.of(UUID.randomUUID());

		potShareholders.updateShareholderDetails(shareholder.id(), name, userId);

		Shareholder updatedShareholder = potShareholders.shareholders().get(shareholder.id());
		assertEquals(shareholder.id(), updatedShareholder.id());
		assertEquals(potId, updatedShareholder.potId());
		assertEquals(name, updatedShareholder.name());
		assertEquals(shareholder.weight(), updatedShareholder.weight());
		assertEquals(userId, updatedShareholder.userId());
		assertFalse(updatedShareholder.deleted());
		assertEquals(Set.of(shareholder.id()), potShareholders.updatedShareholderIds());
		assertTrue(potShareholders.addedShareholderIds().isEmpty());
	}

	@Test
	void updatesShareholderDetailsWithoutUserId() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder shareholder = shareholder(potId);
		PotShareholders potShareholders = new PotShareholders(potId, Set.of(shareholder));
		Name name = Name.of("Bob");

		potShareholders.updateShareholderDetails(shareholder.id(), name, null);

		Shareholder updatedShareholder = potShareholders.shareholders().get(shareholder.id());
		assertEquals(name, updatedShareholder.name());
		assertNull(updatedShareholder.userId());
		assertEquals(Set.of(shareholder.id()), potShareholders.updatedShareholderIds());
	}

	@Test
	void updatesShareholderWeight() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder shareholder = shareholder(potId);
		PotShareholders potShareholders = new PotShareholders(potId, Set.of(shareholder));
		Weight weight = Weight.of(new Fraction(3, 4));

		potShareholders.updateShareholderWeight(shareholder.id(), weight);

		Shareholder updatedShareholder = potShareholders.shareholders().get(shareholder.id());
		assertEquals(shareholder.id(), updatedShareholder.id());
		assertEquals(potId, updatedShareholder.potId());
		assertEquals(shareholder.name(), updatedShareholder.name());
		assertEquals(weight, updatedShareholder.weight());
		assertEquals(shareholder.userId(), updatedShareholder.userId());
		assertFalse(updatedShareholder.deleted());
		assertEquals(Set.of(shareholder.id()), potShareholders.updatedShareholderIds());
		assertTrue(potShareholders.addedShareholderIds().isEmpty());
	}

	@Test
	void doesNotMarkAddedShareholderAsUpdated() {
		PotShareholders potShareholders = new PotShareholders(PotId.of(UUID.randomUUID()), Set.of());
		Shareholder shareholder = potShareholders.addShareholder(
				Name.of("Alice"),
				Weight.of(new Fraction(1, 2)),
				null);

		potShareholders.updateShareholderDetails(shareholder.id(), Name.of("Bob"), null);
		potShareholders.updateShareholderWeight(shareholder.id(), Weight.of(new Fraction(3, 4)));

		assertEquals(Set.of(shareholder.id()), potShareholders.addedShareholderIds());
		assertTrue(potShareholders.updatedShareholderIds().isEmpty());
	}

	@Test
	void rejectsNullShareholderIdWhenUpdatingDetails() {
		PotShareholders potShareholders = new PotShareholders(PotId.of(UUID.randomUUID()), Set.of());

		assertThrows(
				NullPointerException.class,
				() -> potShareholders.updateShareholderDetails(null, Name.of("Bob"), null));
	}

	@Test
	void rejectsNullNameWhenUpdatingDetails() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder shareholder = shareholder(potId);
		PotShareholders potShareholders = new PotShareholders(potId, Set.of(shareholder));

		assertThrows(NullPointerException.class, () -> potShareholders.updateShareholderDetails(shareholder.id(), null, null));
	}

	@Test
	void rejectsUnknownShareholderIdWhenUpdatingDetails() {
		PotShareholders potShareholders = new PotShareholders(PotId.of(UUID.randomUUID()), Set.of());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> potShareholders.updateShareholderDetails(
						ShareholderId.of(UUID.randomUUID()),
						Name.of("Bob"),
						null));

		assertEquals("SHAREHOLDER_NOT_PRESENT", exception.ruleCode());
	}

	@Test
	void rejectsDeletedShareholderWhenUpdatingDetails() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder shareholder = shareholder(potId, true);
		PotShareholders potShareholders = new PotShareholders(potId, Set.of(shareholder));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> potShareholders.updateShareholderDetails(
						shareholder.id(),
						Name.of("Bob"),
						null));

		assertEquals("SHAREHOLDER_DELETED", exception.ruleCode());
	}

	@Test
	void rejectsNullShareholderIdWhenUpdatingWeight() {
		PotShareholders potShareholders = new PotShareholders(PotId.of(UUID.randomUUID()), Set.of());

		assertThrows(
				NullPointerException.class,
				() -> potShareholders.updateShareholderWeight(null, Weight.of(new Fraction(1, 2))));
	}

	@Test
	void rejectsNullWeightWhenUpdatingWeight() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder shareholder = shareholder(potId);
		PotShareholders potShareholders = new PotShareholders(potId, Set.of(shareholder));

		assertThrows(NullPointerException.class, () -> potShareholders.updateShareholderWeight(shareholder.id(), null));
	}

	@Test
	void rejectsUnknownShareholderIdWhenUpdatingWeight() {
		PotShareholders potShareholders = new PotShareholders(PotId.of(UUID.randomUUID()), Set.of());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> potShareholders.updateShareholderWeight(
						ShareholderId.of(UUID.randomUUID()),
						Weight.of(new Fraction(1, 2))));

		assertEquals("SHAREHOLDER_NOT_PRESENT", exception.ruleCode());
	}

	@Test
	void rejectsDeletedShareholderWhenUpdatingWeight() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder shareholder = shareholder(potId, true);
		PotShareholders potShareholders = new PotShareholders(potId, Set.of(shareholder));

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> potShareholders.updateShareholderWeight(
						shareholder.id(),
						Weight.of(new Fraction(1, 2))));

		assertEquals("SHAREHOLDER_DELETED", exception.ruleCode());
	}

	@Test
	void rejectsNullPotId() {
		Set<Shareholder> shareholders = Set.of();

		assertThrows(NullPointerException.class, () -> new PotShareholders(null, shareholders));
	}

	@Test
	void rejectsNullShareholders() {
		PotId potId = PotId.of(UUID.randomUUID());

		assertThrows(NullPointerException.class, () -> new PotShareholders(potId, null));
	}

	@Test
	void rejectsNullShareholder() {
		PotId potId = PotId.of(UUID.randomUUID());
		Set<Shareholder> shareholders = new HashSet<>();
		shareholders.add(null);

		assertThrows(NullPointerException.class, () -> new PotShareholders(potId, shareholders));
	}

	@Test
	void copiesShareholders() {
		PotId potId = PotId.of(UUID.randomUUID());
		Shareholder shareholder = shareholder(potId);
		Set<Shareholder> shareholders = new HashSet<>();
		shareholders.add(shareholder);

		PotShareholders potShareholders = new PotShareholders(potId, shareholders);
		shareholders.clear();

		assertEquals(Map.of(shareholder.id(), shareholder), potShareholders.shareholders());
	}

	private static Shareholder shareholder(PotId potId) {
		return shareholder(potId, false);
	}

	private static Shareholder shareholder(PotId potId, boolean deleted) {
		return new Shareholder(
				ShareholderId.of(UUID.randomUUID()),
				potId,
				Name.of("Alice"),
				Weight.of(new Fraction(1, 2)),
				UserId.of(UUID.randomUUID()),
				deleted);
	}
}
