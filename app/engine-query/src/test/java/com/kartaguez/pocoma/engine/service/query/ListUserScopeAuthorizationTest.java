package com.kartaguez.pocoma.engine.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.pot.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.pot.entity.Shareholder;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.policy.ReadPotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.policy.scope.Scope;
import com.kartaguez.pocoma.domain.projection.Balance;
import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.port.in.query.intent.ListUserPotBalancesQuery;
import com.kartaguez.pocoma.engine.port.out.persistence.PotBalancesPort;
import com.kartaguez.pocoma.engine.port.out.query.PotQueryPort;
import com.kartaguez.pocoma.engine.security.UserContext;

class ListUserScopeAuthorizationTest {

	private static final Scope POT_READ_SCOPE = new Scope(Scope.Resource.POT, null, Scope.Action.READ);
	private static final Scope POT_CREATE_SCOPE = new Scope(Scope.Resource.POT, null, Scope.Action.CREATE);

	@Test
	void listUserPotsRejectsUserWithoutReadScope() {
		ListUserPotsService service = new ListUserPotsService(new FakePotQueryPort(), new ReadPotAuthorizationPolicy());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.listUserPots(userContext(Set.of(POT_CREATE_SCOPE))));

		assertEquals("MISSING_SCOPE", exception.ruleCode());
	}

	@Test
	void listUserPotsAcceptsUserWithReadScope() {
		FakePotQueryPort potQueryPort = new FakePotQueryPort();
		ListUserPotsService service = new ListUserPotsService(potQueryPort, new ReadPotAuthorizationPolicy());

		assertEquals(1, service.listUserPots(userContext(Set.of(POT_READ_SCOPE))).size());
		assertEquals(1, potQueryPort.listAccessiblePotHeadersCount);
	}

	@Test
	void listUserPotBalancesRejectsUserWithoutReadScope() {
		ListUserPotBalancesService service = new ListUserPotBalancesService(
				new FakePotQueryPort(),
				new FakePotBalancesPort(),
				new ReadPotAuthorizationPolicy());

		BusinessRuleViolationException exception = assertThrows(
				BusinessRuleViolationException.class,
				() -> service.listUserPotBalances(userContext(Set.of(POT_CREATE_SCOPE)), new ListUserPotBalancesQuery()));

		assertEquals("MISSING_SCOPE", exception.ruleCode());
	}

	@Test
	void listUserPotBalancesAcceptsUserWithReadScope() {
		FakePotQueryPort potQueryPort = new FakePotQueryPort();
		ListUserPotBalancesService service = new ListUserPotBalancesService(
				potQueryPort,
				new FakePotBalancesPort(),
				new ReadPotAuthorizationPolicy());

		assertEquals(1, service.listUserPotBalances(userContext(Set.of(POT_READ_SCOPE)), new ListUserPotBalancesQuery()).size());
		assertEquals(1, potQueryPort.listAccessiblePotHeadersCount);
	}

	private static UserContext userContext(Set<Scope> scopes) {
		return new UserContext(FakePotQueryPort.USER_ID, scopes);
	}

	private static final class FakePotQueryPort implements PotQueryPort {

		private static final UserId USER_ID = UserId.of(UUID.randomUUID());
		private static final PotId POT_ID = PotId.of(UUID.randomUUID());
		private static final ShareholderId SHAREHOLDER_ID = ShareholderId.of(UUID.randomUUID());
		private static final Shareholder SHAREHOLDER = Shareholder.reconstitute(
				SHAREHOLDER_ID,
				POT_ID,
				Name.of("Alice"),
				Weight.of(Fraction.of(1, 1)),
				USER_ID,
				false);

		private int listAccessiblePotHeadersCount;

		@Override
		public List<VersionedPotHeader> listAccessiblePotHeaders(UserId userId) {
			listAccessiblePotHeadersCount++;
			return List.of(new VersionedPotHeader(
					PotHeader.reconstitute(POT_ID, Label.of("Trip"), USER_ID, false),
					1));
		}

		@Override
		public Optional<Shareholder> findLinkedShareholderAtVersion(UserId userId, PotId potId, long version) {
			return USER_ID.equals(userId) && POT_ID.equals(potId) ? Optional.of(SHAREHOLDER) : Optional.empty();
		}

		@Override
		public PotShareholders loadPotShareholdersAtVersion(PotId potId, long version) {
			return PotShareholders.reconstitute(potId, Set.of(SHAREHOLDER));
		}
	}

	private static final class FakePotBalancesPort implements PotBalancesPort {

		@Override
		public PotBalances loadAtVersion(PotId potId, long version) {
			return new PotBalances(
					potId,
					version,
					Map.of(FakePotQueryPort.SHAREHOLDER_ID, new Balance(
							FakePotQueryPort.SHAREHOLDER_ID,
							Fraction.of(12, 1))));
		}
	}
}
