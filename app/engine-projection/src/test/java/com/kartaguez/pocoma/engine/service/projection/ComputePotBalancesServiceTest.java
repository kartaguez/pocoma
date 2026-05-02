package com.kartaguez.pocoma.engine.service.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.projection.Balance;
import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.projection.PotBalancesCalculator;
import com.kartaguez.pocoma.domain.projection.ProjectedExpense;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.model.PotBalanceProjectionState;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectedExpensePort;

class ComputePotBalancesServiceTest {

	@Test
	void savesInitialBalancesWhenNoProjectionStateExists() {
		PotId potId = PotId.of(UUID.randomUUID());
		FakePotBalancesPort potBalancesPort = new FakePotBalancesPort();
		FakeProjectedExpensePort projectedExpensePort = new FakeProjectedExpensePort();
		ComputePotBalancesService service = service(potBalancesPort, projectedExpensePort);

		PotBalances result = service.computePotBalances(potId, 1);

		assertEquals(new PotBalances(potId, 1, Map.of()), result);
		assertEquals(result, potBalancesPort.savedInitial);
		assertFalse(projectedExpensePort.called);
	}

	@Test
	void returnsAlreadyProjectedBalancesWhenTargetVersionIsNotNewer() {
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		PotBalances currentBalances = new PotBalances(
				potId,
				4,
				Map.of(shareholderId, new Balance(shareholderId, Fraction.ONE)));
		FakePotBalancesPort potBalancesPort = new FakePotBalancesPort();
		potBalancesPort.state = Optional.of(new PotBalanceProjectionState(potId, 4));
		potBalancesPort.balances = currentBalances;
		FakeProjectedExpensePort projectedExpensePort = new FakeProjectedExpensePort();
		ComputePotBalancesService service = service(potBalancesPort, projectedExpensePort);

		PotBalances result = service.computePotBalances(potId, 3);

		assertEquals(currentBalances, result);
		assertFalse(projectedExpensePort.called);
		assertEquals(0, potBalancesPort.saveCalls);
	}

	@Test
	void computesAndSavesNextBalancesFromPreviousProjectionState() {
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		PotBalances previousBalances = new PotBalances(
				potId,
				2,
				Map.of(shareholderId, new Balance(shareholderId, Fraction.of(5, 1))));
		FakePotBalancesPort potBalancesPort = new FakePotBalancesPort();
		potBalancesPort.state = Optional.of(new PotBalanceProjectionState(potId, 2));
		potBalancesPort.balances = previousBalances;
		FakeProjectedExpensePort projectedExpensePort = new FakeProjectedExpensePort();
		ComputePotBalancesService service = service(potBalancesPort, projectedExpensePort);

		PotBalances result = service.computePotBalances(potId, 3);

		assertEquals(new PotBalances(potId, 3, previousBalances.balances()), result);
		assertEquals(result, potBalancesPort.saved);
		assertEquals(2, potBalancesPort.expectedProjectedVersion);
		assertTrue(projectedExpensePort.called);
		assertEquals(2, projectedExpensePort.calls);
	}

	@Test
	void rejectsTargetVersionBelowOne() {
		assertThrows(
				IllegalArgumentException.class,
				() -> service(new FakePotBalancesPort(), new FakeProjectedExpensePort())
						.computePotBalances(PotId.of(UUID.randomUUID()), 0));
	}

	@Test
	void fullProjectionSavesZeroBalanceForActiveShareholderWithoutExpenses() {
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId shareholderId = ShareholderId.of(UUID.randomUUID());
		FakePotBalancesPort potBalancesPort = new FakePotBalancesPort();
		FakeProjectedExpensePort projectedExpensePort = new FakeProjectedExpensePort();
		FakePotShareholdersPort potShareholdersPort = new FakePotShareholdersPort();
		potShareholdersPort.shareholders = PotShareholders.reconstitute(
				potId,
				Set.of(shareholder(potId, shareholderId)));
		ComputePotBalancesService service = service(potBalancesPort, projectedExpensePort, potShareholdersPort);

		PotBalances result = service.computePotBalancesFull(potId, 1);

		assertEquals(new PotBalances(
				potId,
				1,
				Map.of(shareholderId, new Balance(shareholderId, Fraction.ZERO))), result);
		assertEquals(result, potBalancesPort.savedFull);
	}

	@Test
	void fullProjectionSavesEmptyVersionWhenNoShareholdersExist() {
		PotId potId = PotId.of(UUID.randomUUID());
		FakePotBalancesPort potBalancesPort = new FakePotBalancesPort();
		ComputePotBalancesService service = service(potBalancesPort, new FakeProjectedExpensePort());

		PotBalances result = service.computePotBalancesFull(potId, 1);

		assertEquals(new PotBalances(potId, 1, Map.of()), result);
		assertEquals(result, potBalancesPort.savedFull);
	}

	@Test
	void fullProjectionComputesBalancesFromZeroBaseAndActiveExpenses() {
		PotId potId = PotId.of(UUID.randomUUID());
		ShareholderId payerId = ShareholderId.of(UUID.randomUUID());
		ShareholderId participantId = ShareholderId.of(UUID.randomUUID());
		ExpenseId expenseId = ExpenseId.of(UUID.randomUUID());
		FakePotBalancesPort potBalancesPort = new FakePotBalancesPort();
		FakeProjectedExpensePort projectedExpensePort = new FakeProjectedExpensePort();
		projectedExpensePort.activeAtVersion = java.util.List.of(new ProjectedExpense(
				ExpenseHeader.reconstitute(
						expenseId,
						potId,
						payerId,
						Amount.of(Fraction.of(10, 1)),
						Label.of("Lunch"),
						false),
				ExpenseShares.reconstitute(potId, Set.of(
						new ExpenseShare(expenseId, payerId, Weight.of(Fraction.ONE)),
						new ExpenseShare(expenseId, participantId, Weight.of(Fraction.ONE))))));
		FakePotShareholdersPort potShareholdersPort = new FakePotShareholdersPort();
		potShareholdersPort.shareholders = PotShareholders.reconstitute(
				potId,
				Set.of(shareholder(potId, payerId), shareholder(potId, participantId)));
		ComputePotBalancesService service = service(potBalancesPort, projectedExpensePort, potShareholdersPort);

		PotBalances result = service.computePotBalancesFull(potId, 4);

		assertEquals(new PotBalances(
				potId,
				4,
				Map.of(
						payerId, new Balance(payerId, Fraction.of(5, 1)),
						participantId, new Balance(participantId, Fraction.of(-5, 1)))),
				result);
		assertEquals(result, potBalancesPort.savedFull);
	}

	@Test
	void fullProjectionRejectsTargetVersionBelowOne() {
		assertThrows(
				IllegalArgumentException.class,
				() -> service(new FakePotBalancesPort(), new FakeProjectedExpensePort())
						.computePotBalancesFull(PotId.of(UUID.randomUUID()), 0));
	}

	private static ComputePotBalancesService service(
			FakePotBalancesPort potBalancesPort,
			FakeProjectedExpensePort projectedExpensePort) {
		return service(potBalancesPort, projectedExpensePort, new FakePotShareholdersPort());
	}

	private static ComputePotBalancesService service(
			FakePotBalancesPort potBalancesPort,
			FakeProjectedExpensePort projectedExpensePort,
			FakePotShareholdersPort potShareholdersPort) {
		return new ComputePotBalancesService(
				potBalancesPort,
				projectedExpensePort,
				potShareholdersPort,
				new PotBalancesCalculator());
	}

	private static Shareholder shareholder(PotId potId, ShareholderId shareholderId) {
		return Shareholder.reconstitute(
				shareholderId,
				potId,
				Name.of("Shareholder"),
				Weight.of(Fraction.ONE),
				null,
				false);
	}

	private static final class FakePotBalancesPort implements PotBalancesPort {
		private Optional<PotBalanceProjectionState> state = Optional.empty();
		private PotBalances balances;
		private PotBalances savedInitial;
		private PotBalances saved;
		private PotBalances savedFull;
		private long expectedProjectedVersion;
		private int saveCalls;

		@Override
		public Optional<PotBalanceProjectionState> loadProjectionState(PotId potId) {
			return state;
		}

		@Override
		public PotBalances loadAtVersion(PotId potId, long version) {
			return balances;
		}

		@Override
		public void saveInitial(PotBalances potBalances) {
			savedInitial = potBalances;
			saveCalls++;
		}

		@Override
		public void save(PotBalances potBalances, long expectedProjectedVersion) {
			saved = potBalances;
			this.expectedProjectedVersion = expectedProjectedVersion;
			saveCalls++;
		}

		@Override
		public void saveFull(PotBalances potBalances) {
			savedFull = potBalances;
			saveCalls++;
		}
	}

	private static final class FakeProjectedExpensePort implements ProjectedExpensePort {
		private boolean called;
		private int calls;
		private Collection<ProjectedExpense> activeAtVersion = java.util.List.of();

		@Override
		public Collection<ProjectedExpense> loadActiveAtVersion(PotId potId, long version) {
			called = true;
			calls++;
			return activeAtVersion;
		}

		@Override
		public Collection<ProjectedExpense> loadActiveAtSourceOnly(
				PotId potId,
				long sourceVersion,
				long comparedVersion) {
			called = true;
			calls++;
			return java.util.List.of();
		}
	}

	private static final class FakePotShareholdersPort implements PotShareholdersPort {
		private PotShareholders shareholders = PotShareholders.reconstitute(PotId.of(UUID.randomUUID()), Set.of());

		@Override
		public PotShareholders loadActiveAtVersion(PotId potId, long version) {
			return shareholders.potId().equals(potId)
					? shareholders
					: PotShareholders.reconstitute(potId, Set.of());
		}
	}
}
