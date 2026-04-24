package com.kartaguez.pocoma.infra.persistence.jpa.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaExpenseHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaExpenseShareEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaExpenseHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaExpenseShareRepository;

@DataJpaTest
@Import(JpaExpenseSharesAdapter.class)
class JpaExpenseSharesAdapterTest {

	@Autowired
	private JpaExpenseSharesAdapter adapter;

	@Autowired
	private JpaExpenseShareRepository expenseShareRepository;

	@Autowired
	private JpaExpenseHeaderRepository expenseHeaderRepository;

	@Test
	void loadsEmptyExpenseSharesWhenHeaderExists() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		saveHeader(expenseId, potId, 1, null);

		ExpenseShares loaded = adapter.loadActiveAtVersion(expenseId, 1);

		assertEquals(potId, loaded.potId());
		assertTrue(loaded.shares().isEmpty());
	}

	@Test
	void rejectsLoadingUnknownExpenseShares() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());

		BusinessEntityNotFoundException exception = assertThrows(
				BusinessEntityNotFoundException.class,
				() -> adapter.loadActiveAtVersion(expenseId, 1));

		assertEquals("EXPENSE_SHARES", exception.entityCode());
	}

	@Test
	void savesExpenseShares() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		ExpenseShare share = expenseShare(expenseId, ShareholderId.of(UUID.randomUUID()), 1, 2);

		adapter.saveNew(expenseId, ExpenseShares.reconstitute(potId, Set.of(share)), 2);

		ExpenseShares loaded = adapter.loadActiveAtVersion(expenseId, 2);
		assertEquals(potId, loaded.potId());
		assertSingleShareEquals(share, loaded.shares().values());
	}

	@Test
	void replacesExpenseSharesThatStartedBeforeCurrentGlobalVersion() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		saveHeader(expenseId, potId, 2, null);
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		ExpenseShare initialShare = expenseShare(expenseId, shareholderId, 1, 1);
		ExpenseShare updatedShare = expenseShare(expenseId, shareholderId, 3, 4);
		expenseShareRepository.save(JpaExpenseShareEntity.from(potId, initialShare, 2, null));

		adapter.save(
				expenseId,
				ExpenseShares.reconstitute(potId, Set.of(updatedShare)),
				new PotGlobalVersion(potId, 3),
				new PotGlobalVersion(potId, 4));

		ExpenseShares oldVersion = adapter.loadActiveAtVersion(expenseId, 3);
		ExpenseShares newVersion = adapter.loadActiveAtVersion(expenseId, 4);
		assertSingleShareEquals(initialShare, oldVersion.shares().values());
		assertEquals(4L, expenseShareRepository.findActiveAtVersion(expenseId.value(), 3).getFirst().endedAtVersion());
		assertSingleShareEquals(updatedShare, newVersion.shares().values());
	}

	@Test
	void savesNewCollectionStateWhenNoRowsAreActiveAtCurrentVersion() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		saveHeader(expenseId, potId, 1, null);
		ExpenseShare share = expenseShare(expenseId, ShareholderId.of(UUID.randomUUID()), 1, 1);

		adapter.save(
				expenseId,
				ExpenseShares.reconstitute(potId, Set.of(share)),
				new PotGlobalVersion(potId, 1),
				new PotGlobalVersion(potId, 2));

		ExpenseShares loaded = adapter.loadActiveAtVersion(expenseId, 2);
		assertSingleShareEquals(share, loaded.shares().values());
	}

	@Test
	void rejectsSaveWithDifferentPotIds() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		PotId sharesPotId = PotId.of(UUID.randomUUID());
		PotId versionPotId = PotId.of(UUID.randomUUID());

		assertThrows(
				IllegalArgumentException.class,
				() -> adapter.save(
						expenseId,
						ExpenseShares.reconstitute(sharesPotId, Set.of()),
						new PotGlobalVersion(versionPotId, 1),
						new PotGlobalVersion(versionPotId, 2)));
	}

	@Test
	void rejectsSavingShareForAnotherExpense() {
		ExpenseId savedExpenseId = ExpenseId.of(UUID.randomUUID());
		ExpenseId shareExpenseId = ExpenseId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		ExpenseShare share = expenseShare(shareExpenseId, ShareholderId.of(UUID.randomUUID()), 1, 1);

		assertThrows(
				IllegalArgumentException.class,
				() -> adapter.saveNew(savedExpenseId, ExpenseShares.reconstitute(potId, Set.of(share)), 1));
	}

	private void saveHeader(ExpenseId expenseId, PotId potId, long startedAtVersion, Long endedAtVersion) {
		ExpenseHeader header = ExpenseHeader.reconstitute(
				expenseId,
				potId,
				ShareholderId.of(UUID.randomUUID()),
				Amount.of(Fraction.of(42, 1)),
				Label.of("Dinner"),
				false);
		expenseHeaderRepository.save(JpaExpenseHeaderEntity.from(header, startedAtVersion, endedAtVersion));
	}

	private static ExpenseShare expenseShare(
			ExpenseId expenseId,
			ShareholderId shareholderId,
			long numerator,
			long denominator) {
		return new ExpenseShare(expenseId, shareholderId, Weight.of(Fraction.of(numerator, denominator)));
	}

	private static void assertSingleShareEquals(ExpenseShare expected, Collection<ExpenseShare> actualShares) {
		assertEquals(1, actualShares.size());
		ExpenseShare actual = actualShares.iterator().next();
		assertEquals(expected.expenseId(), actual.expenseId());
		assertEquals(expected.shareholderId(), actual.shareholderId());
		assertEquals(expected.weight(), actual.weight());
	}

	@SpringBootApplication
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	static class TestApplication {
	}
}
