package com.kartaguez.pocoma.infra.persistence.jpa.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.model.PotGlobalVersion;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.JpaExpenseHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaExpenseHeaderRepository;

@DataJpaTest
@Import(JpaExpenseHeaderAdapter.class)
class JpaExpenseHeaderAdapterTest {

	@Autowired
	private JpaExpenseHeaderAdapter adapter;

	@Autowired
	private JpaExpenseHeaderRepository repository;

	@Test
	void savesExpenseHeader() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId payerId = ShareholderId.of(UUID.randomUUID());

		adapter.saveNew(expenseHeader(expenseId, potId, payerId, "Dinner", false), 2);

		ExpenseHeader loaded = adapter.loadActiveAtVersion(expenseId, 2);
		assertEquals(expenseId, loaded.id());
		assertEquals(potId, loaded.potId());
		assertEquals(payerId, loaded.payerId());
		assertEquals(Label.of("Dinner"), loaded.label());
		assertFalse(loaded.deleted());
		assertEquals(2, repository.findActiveAtVersion(expenseId.value(), 2).orElseThrow().startedAtVersion());
		assertNull(repository.findActiveAtVersion(expenseId.value(), 2).orElseThrow().endedAtVersion());
	}

	@Test
	void loadsExpenseHeaderActiveAtRequestedVersion() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		repository.save(JpaExpenseHeaderEntity.from(expenseHeader(expenseId, potId, payerId, "Initial", false), 2, 4L));
		adapter.saveNew(expenseHeader(expenseId, potId, payerId, "Renamed", true), 4);

		ExpenseHeader loaded = adapter.loadActiveAtVersion(expenseId, 5);

		assertEquals(Label.of("Renamed"), loaded.label());
		assertTrue(loaded.deleted());
		assertEquals(4, repository.findActiveAtVersion(expenseId.value(), 5).orElseThrow().startedAtVersion());
	}

	@Test
	void rejectsLoadingUnknownActiveExpenseHeader() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());

		BusinessEntityNotFoundException exception = assertThrows(
				BusinessEntityNotFoundException.class,
				() -> adapter.loadActiveAtVersion(expenseId, 1));

		assertEquals("EXPENSE_HEADER", exception.entityCode());
	}

	@Test
	void replacesExpenseHeaderThatStartedBeforeCurrentGlobalVersion() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		adapter.saveNew(expenseHeader(expenseId, potId, payerId, "Initial", false), 2);

		ExpenseHeader next = expenseHeader(expenseId, potId, payerId, "Renamed", false);
		adapter.save(next, new PotGlobalVersion(potId, 3), new PotGlobalVersion(potId, 4));

		ExpenseHeader stillActiveAtCurrentVersion = adapter.loadActiveAtVersion(expenseId, 3);
		ExpenseHeader newVersion = adapter.loadActiveAtVersion(expenseId, 4);
		assertEquals(Label.of("Initial"), stillActiveAtCurrentVersion.label());
		assertEquals(4L, repository.findActiveAtVersion(expenseId.value(), 3).orElseThrow().endedAtVersion());
		assertEquals(Label.of("Renamed"), newVersion.label());
		assertNull(repository.findActiveAtVersion(expenseId.value(), 4).orElseThrow().endedAtVersion());
	}

	@Test
	void rejectsSaveWhenNoActiveExpenseHeaderIsClosed() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		repository.save(JpaExpenseHeaderEntity.from(expenseHeader(expenseId, potId, payerId, "Initial", false), 1, 2L));

		VersionConflictException exception = assertThrows(
				VersionConflictException.class,
				() -> adapter.save(
						expenseHeader(expenseId, potId, payerId, "Renamed", false),
						new PotGlobalVersion(potId, 2),
						new PotGlobalVersion(potId, 3)));

		assertEquals("POT_VERSION_CONFLICT", exception.conflictCode());
	}

	@Test
	void rejectsSaveWithDifferentPotIds() {
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		PotId expensePotId = PotId.of(UUID.randomUUID());
		PotId versionPotId = PotId.of(UUID.randomUUID());

		assertThrows(
				IllegalArgumentException.class,
				() -> adapter.save(
						expenseHeader(expenseId, expensePotId, ShareholderId.of(UUID.randomUUID()), "Dinner", false),
						new PotGlobalVersion(versionPotId, 1),
						new PotGlobalVersion(versionPotId, 2)));
	}

	private static ExpenseHeader expenseHeader(
			ExpenseId expenseId,
			PotId potId,
			ShareholderId payerId,
			String label,
			boolean deleted) {
		return ExpenseHeader.reconstitute(
				expenseId,
				potId,
				payerId,
				Amount.of(Fraction.of(42, 1)),
				Label.of(label),
				deleted);
	}

	@SpringBootApplication
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	static class TestApplication {
	}
}
