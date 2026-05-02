package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.projection.ProjectedExpense;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaExpenseHeaderEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.core.JpaExpenseShareEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaExpenseHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaExpenseShareRepository;

@DataJpaTest
@Import(JpaProjectedExpenseAdapter.class)
class JpaProjectedExpenseAdapterTest {

	@Autowired
	private JpaProjectedExpenseAdapter adapter;

	@Autowired
	private JpaExpenseHeaderRepository expenseHeaderRepository;

	@Autowired
	private JpaExpenseShareRepository expenseShareRepository;

	@Test
	void loadsExpensesCreatedBetweenComparedAndSourceVersions() {
		Fixture fixture = new Fixture();
		saveHeader(fixture.header("Dinner", false), 3, null);
		saveShares(fixture.potId, Set.of(fixture.share(fixture.aliceId, Fraction.ONE)), 3, null);

		Collection<ProjectedExpense> sourceOnly = adapter.loadActiveAtSourceOnly(fixture.potId, 3, 2);
		Collection<ProjectedExpense> comparedOnly = adapter.loadActiveAtSourceOnly(fixture.potId, 2, 3);

		assertEquals(1, sourceOnly.size());
		assertEquals(Label.of("Dinner"), sourceOnly.iterator().next().header().label());
		assertTrue(comparedOnly.isEmpty());
	}

	@Test
	void loadsPreviousAndTargetExpenseStatesWhenHeaderChanged() {
		Fixture fixture = new Fixture();
		saveHeader(fixture.header("Initial", false), 2, 4L);
		saveHeader(fixture.header("Updated", false), 4, null);
		saveShares(fixture.potId, Set.of(fixture.share(fixture.aliceId, Fraction.ONE)), 2, null);

		ProjectedExpense previousOnly = adapter.loadActiveAtSourceOnly(fixture.potId, 3, 4).iterator().next();
		ProjectedExpense targetOnly = adapter.loadActiveAtSourceOnly(fixture.potId, 4, 3).iterator().next();

		assertEquals(Label.of("Initial"), previousOnly.header().label());
		assertEquals(Label.of("Updated"), targetOnly.header().label());
	}

	@Test
	void loadsPreviousAndTargetExpenseStatesWhenSharesChanged() {
		Fixture fixture = new Fixture();
		saveHeader(fixture.header("Dinner", false), 2, null);
		saveShares(fixture.potId, Set.of(fixture.share(fixture.aliceId, Fraction.ONE)), 2, 4L);
		saveShares(fixture.potId, Set.of(fixture.share(fixture.bobId, Fraction.ONE)), 4, null);

		ProjectedExpense previousOnly = adapter.loadActiveAtSourceOnly(fixture.potId, 3, 4).iterator().next();
		ProjectedExpense targetOnly = adapter.loadActiveAtSourceOnly(fixture.potId, 4, 3).iterator().next();

		assertEquals(Set.of(fixture.aliceId), previousOnly.shares().shares().keySet());
		assertEquals(Set.of(fixture.bobId), targetOnly.shares().shares().keySet());
	}

	@Test
	void doesNotLoadDeletedExpenseAsTargetState() {
		Fixture fixture = new Fixture();
		saveHeader(fixture.header("Dinner", false), 2, 4L);
		saveHeader(fixture.header("Dinner", true), 4, null);
		saveShares(fixture.potId, Set.of(fixture.share(fixture.aliceId, Fraction.ONE)), 2, null);

		Collection<ProjectedExpense> targetOnly = adapter.loadActiveAtSourceOnly(fixture.potId, 4, 3);

		assertTrue(targetOnly.isEmpty());
	}

	@Test
	void loadsActiveExpensesAtVersion() {
		Fixture fixture = new Fixture();
		saveHeader(fixture.header("Old", false), 2, 4L);
		saveHeader(fixture.header("Current", false), 4, null);
		saveShares(fixture.potId, Set.of(fixture.share(fixture.aliceId, Fraction.ONE)), 2, 4L);
		saveShares(fixture.potId, Set.of(fixture.share(fixture.bobId, Fraction.ONE)), 4, null);

		ProjectedExpense projectedExpense = adapter.loadActiveAtVersion(fixture.potId, 4).iterator().next();

		assertEquals(Label.of("Current"), projectedExpense.header().label());
		assertEquals(Set.of(fixture.bobId), projectedExpense.shares().shares().keySet());
	}

	private void saveHeader(ExpenseHeader header, long startedAtVersion, Long endedAtVersion) {
		expenseHeaderRepository.save(JpaExpenseHeaderEntity.from(header, startedAtVersion, endedAtVersion));
	}

	private void saveShares(PotId potId, Set<ExpenseShare> shares, long startedAtVersion, Long endedAtVersion) {
		expenseShareRepository.saveAll(shares.stream()
				.map(share -> JpaExpenseShareEntity.from(potId, share, startedAtVersion, endedAtVersion))
				.toList());
	}

	private static final class Fixture {
		private final PotId potId = PotId.of(UUID.randomUUID());
		private final ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		private final ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		private final ShareholderId aliceId = ShareholderId.of(UUID.randomUUID());
		private final ShareholderId bobId = ShareholderId.of(UUID.randomUUID());

		private ExpenseHeader header(String label, boolean deleted) {
			return ExpenseHeader.reconstitute(
					expenseId,
					potId,
					payerId,
					Amount.of(Fraction.of(42, 1)),
					Label.of(label),
					deleted);
		}

		private ExpenseShare share(ShareholderId shareholderId, Fraction weight) {
			return new ExpenseShare(
					expenseId,
					shareholderId,
					Weight.of(weight));
		}
	}

	@SpringBootApplication
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	static class TestApplication {
	}
}
