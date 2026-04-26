package com.kartaguez.pocoma.engine.service.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.projection.Balance;
import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.projection.PotBalancesCalculator;
import com.kartaguez.pocoma.domain.projection.ProjectedExpense;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.model.PotBalanceProjectionState;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
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

	private static ComputePotBalancesService service(
			FakePotBalancesPort potBalancesPort,
			FakeProjectedExpensePort projectedExpensePort) {
		return new ComputePotBalancesService(
				potBalancesPort,
				projectedExpensePort,
				new PotBalancesCalculator());
	}

	private static final class FakePotBalancesPort implements PotBalancesPort {
		private Optional<PotBalanceProjectionState> state = Optional.empty();
		private PotBalances balances;
		private PotBalances savedInitial;
		private PotBalances saved;
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
	}

	private static final class FakeProjectedExpensePort implements ProjectedExpensePort {
		private boolean called;
		private int calls;

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
}
