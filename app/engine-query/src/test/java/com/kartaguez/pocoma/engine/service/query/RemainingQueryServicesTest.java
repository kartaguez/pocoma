package com.kartaguez.pocoma.engine.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.pot.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.pot.association.ExpenseShare;
import com.kartaguez.pocoma.domain.pot.entity.Shareholder;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;
import com.kartaguez.pocoma.domain.projection.balance.Balance;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;
import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.pot.version.PotGlobalVersion;
import com.kartaguez.pocoma.engine.port.in.query.intent.GetExpenseQuery;
import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotBalancesQuery;
import com.kartaguez.pocoma.engine.port.in.query.intent.ListPotExpensesQuery;
import com.kartaguez.pocoma.engine.port.out.query.PotBalancesQueryPort;
import com.kartaguez.pocoma.engine.port.out.query.ExpenseQueryPort;
import com.kartaguez.pocoma.engine.port.out.query.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

class RemainingQueryServicesTest {

	private static final UserId CREATOR_ID = UserId.of(UUID.randomUUID());
	private static final PotId POT_ID = PotId.of(UUID.randomUUID());
	private static final ShareholderId SHAREHOLDER_ID = ShareholderId.of(UUID.randomUUID());
	private static final ExpenseId EXPENSE_ID = ExpenseId.of(UUID.randomUUID());
	private static final Scope READ_SCOPE = new Scope(Scope.Resource.POT, null, Scope.Action.READ);

	@Test
	void listsPotExpensesAtResolvedVersionWithoutInfrastructure() {
		Fixture fixture = new Fixture();
		var service = new ListPotExpensesService(fixture.pots, fixture.expenses, new ReadPotAuthorizationPolicy());

		var result = service.listPotExpenses(creator(), new ListPotExpensesQuery(POT_ID.value()));

		assertEquals(1, result.size());
		assertEquals(EXPENSE_ID, result.getFirst().id());
		assertEquals(4, result.getFirst().version());
	}

	@Test
	void getsExpenseAndItsSharesAtResolvedVersionWithoutInfrastructure() {
		Fixture fixture = new Fixture();
		var service = new GetExpenseService(fixture.pots, fixture.expenses, new ReadPotAuthorizationPolicy());

		var result = service.getExpense(creator(), new GetExpenseQuery(EXPENSE_ID.value()));

		assertEquals(EXPENSE_ID, result.header().id());
		assertEquals(4, result.header().version());
		assertEquals(1, result.shares().shares().size());
	}

	@Test
	void getsBalancesAtResolvedVersionWithoutInfrastructure() {
		Fixture fixture = new Fixture();
		var service = new GetPotBalancesService(fixture.pots, fixture.balances, new ReadPotAuthorizationPolicy());

		var result = service.getPotBalances(creator(), new GetPotBalancesQuery(POT_ID.value()));

		assertEquals(POT_ID, result.potId());
		assertEquals(4, result.version());
		assertEquals(1, result.balances().size());
	}

	@Test
	void allPotScopedQueriesRejectAnUnauthorizedUserBeforeLoadingTheirResult() {
		Fixture fixture = new Fixture();
		UserContext unauthorized = new UserContext(UserId.of(UUID.randomUUID()), Set.of(READ_SCOPE));

		assertThrows(BusinessRuleViolationException.class,
				() -> new ListPotExpensesService(fixture.pots, fixture.expenses, new ReadPotAuthorizationPolicy())
						.listPotExpenses(unauthorized, new ListPotExpensesQuery(POT_ID.value())));
		assertThrows(BusinessRuleViolationException.class,
				() -> new GetExpenseService(fixture.pots, fixture.expenses, new ReadPotAuthorizationPolicy())
						.getExpense(unauthorized, new GetExpenseQuery(EXPENSE_ID.value())));
		assertThrows(BusinessRuleViolationException.class,
				() -> new GetPotBalancesService(fixture.pots, fixture.balances, new ReadPotAuthorizationPolicy())
						.getPotBalances(unauthorized, new GetPotBalancesQuery(POT_ID.value())));
	}

	@Test
	void missingEntityFromAReadPortIsPropagated() {
		Fixture fixture = new Fixture();
		fixture.pots.failure = new BusinessEntityNotFoundException("POT", "missing pot");

		BusinessEntityNotFoundException exception = assertThrows(BusinessEntityNotFoundException.class,
				() -> new GetPotBalancesService(fixture.pots, fixture.balances, new ReadPotAuthorizationPolicy())
						.getPotBalances(creator(), new GetPotBalancesQuery(POT_ID.value())));

		assertEquals("POT", exception.entityCode());
	}

	private static UserContext creator() {
		return new UserContext(CREATOR_ID, Set.of(READ_SCOPE));
	}

	private static final class Fixture {
		private final InMemoryPotQueryPort pots = new InMemoryPotQueryPort();
		private final InMemoryExpenseQueryPort expenses = new InMemoryExpenseQueryPort();
		private final InMemoryPotBalancesQueryPort balances = new InMemoryPotBalancesQueryPort();
	}

	private static final class InMemoryPotQueryPort implements PotQueryPort {

		private RuntimeException failure;

		@Override
		public PotGlobalVersion currentVersion(PotId potId) {
			if (failure != null) {
				throw failure;
			}
			return new PotGlobalVersion(potId, 4);
		}

		@Override
		public PotHeader loadPotHeaderAtVersion(PotId potId, long version) {
			if (failure != null) {
				throw failure;
			}
			return PotHeader.reconstitute(potId, Label.of("Trip"), CREATOR_ID, false);
		}

		@Override
		public PotShareholders loadPotShareholdersAtVersion(PotId potId, long version) {
			return PotShareholders.reconstitute(potId, Set.of(shareholder()));
		}

		@Override
		public Optional<Shareholder> findLinkedShareholderAtVersion(UserId userId, PotId potId, long version) {
			return Optional.of(shareholder());
		}
	}

	private static final class InMemoryExpenseQueryPort implements ExpenseQueryPort {

		@Override
		public ExpenseHeader loadCurrentExpenseHeader(ExpenseId expenseId) {
			return expenseHeader();
		}

		@Override
		public ExpenseHeader loadExpenseHeaderAtVersion(ExpenseId expenseId, long version) {
			return expenseHeader();
		}

		@Override
		public ExpenseShares loadExpenseSharesAtVersion(ExpenseId expenseId, long version) {
			return ExpenseShares.reconstitute(POT_ID, Set.of(
					new ExpenseShare(EXPENSE_ID, SHAREHOLDER_ID, Weight.of(Fraction.of(1, 1)))));
		}

		@Override
		public List<VersionedExpenseHeader> listExpenseHeadersByPotAtVersion(PotId potId, long version) {
			return List.of(new VersionedExpenseHeader(expenseHeader(), version));
		}
	}

	private static final class InMemoryPotBalancesQueryPort implements PotBalancesQueryPort {

		@Override
		public PotBalances loadAtVersion(PotId potId, long version) {
			return new PotBalances(potId, version,
					Map.of(SHAREHOLDER_ID, new Balance(SHAREHOLDER_ID, Fraction.of(12, 1))));
		}
	}

	private static Shareholder shareholder() {
		return Shareholder.reconstitute(
				SHAREHOLDER_ID,
				POT_ID,
				Name.of("Alice"),
				Weight.of(Fraction.of(1, 1)),
				CREATOR_ID,
				false);
	}

	private static ExpenseHeader expenseHeader() {
		return ExpenseHeader.reconstitute(
				EXPENSE_ID,
				POT_ID,
				SHAREHOLDER_ID,
				Amount.of(Fraction.of(42, 1)),
				Label.of("Dinner"),
				false);
	}
}
