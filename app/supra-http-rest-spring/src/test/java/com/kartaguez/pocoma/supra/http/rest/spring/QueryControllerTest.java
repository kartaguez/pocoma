package com.kartaguez.pocoma.supra.http.rest.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kartaguez.pocoma.domain.pot.association.ExpenseShare;
import com.kartaguez.pocoma.domain.pot.entity.Shareholder;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.policy.scope.Scope;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.snapshot.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.snapshot.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.snapshot.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.snapshot.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.PotViewSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListPotExpensesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotsUseCase;
import com.kartaguez.pocoma.engine.security.UserContext;
import com.kartaguez.pocoma.supra.http.rest.spring.controller.ExpensesQueryController;
import com.kartaguez.pocoma.supra.http.rest.spring.controller.PotsQueryController;
import com.kartaguez.pocoma.supra.http.rest.spring.error.RestExceptionHandler;
import com.kartaguez.pocoma.supra.http.rest.spring.security.UserContextFactory;

class QueryControllerTest {

	private final ListUserPotsUseCase listUserPotsUseCase = mock(ListUserPotsUseCase.class);
	private final GetPotUseCase getPotUseCase = mock(GetPotUseCase.class);
	private final ListPotExpensesUseCase listPotExpensesUseCase = mock(ListPotExpensesUseCase.class);
	private final GetPotBalancesUseCase getPotBalancesUseCase = mock(GetPotBalancesUseCase.class);
	private final ListUserPotBalancesUseCase listUserPotBalancesUseCase = mock(ListUserPotBalancesUseCase.class);
	private final GetExpenseUseCase getExpenseUseCase = mock(GetExpenseUseCase.class);

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(
						new PotsQueryController(
								listUserPotsUseCase,
								getPotUseCase,
								listPotExpensesUseCase,
								getPotBalancesUseCase,
								listUserPotBalancesUseCase),
						new ExpensesQueryController(getExpenseUseCase))
				.setControllerAdvice(new RestExceptionHandler())
				.build();
	}

	@Test
	void getPotPassesVersionAndMapsView() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();
		Shareholder shareholder = Shareholder.reconstitute(
				ShareholderId.of(UUID.randomUUID()),
				PotId.of(potId),
				Name.of("Alice"),
				Weight.of(Fraction.of(1, 1)),
				UserId.of(userId),
				false);
		when(getPotUseCase.getPot(any(), any())).thenReturn(new PotViewSnapshot(
				new PotHeaderSnapshot(PotId.of(potId), Label.of("Trip"), UserId.of(userId), false, 4),
				new PotShareholdersSnapshot(PotId.of(potId), Set.of(shareholder), 4)));

		mockMvc.perform(get("/api/pots/{potId}?version=4", potId)
						.header(UserContextFactory.USER_ID_HEADER, userId.toString())
						.header(UserContextFactory.USER_SCOPES_HEADER, "pot:read"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.header.id").value(potId.toString()))
				.andExpect(jsonPath("$.header.version").value(4))
				.andExpect(jsonPath("$.shareholders.shareholders[0].name").value("Alice"));

		ArgumentCaptor<GetPotQuery> queryCaptor = ArgumentCaptor.forClass(GetPotQuery.class);
		verify(getPotUseCase).getPot(any(), queryCaptor.capture());
		assertEquals(potId, queryCaptor.getValue().potId());
		assertEquals(4, queryCaptor.getValue().version().getAsLong());
	}

	@Test
	void getExpenseMapsNestedShares() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();
		UUID expenseId = UUID.randomUUID();
		UUID shareholderId = UUID.randomUUID();
		ExpenseShare share = new ExpenseShare(
				ExpenseId.of(expenseId),
				ShareholderId.of(shareholderId),
				Weight.of(Fraction.of(1, 2)));
		when(getExpenseUseCase.getExpense(any(), any())).thenReturn(new com.kartaguez.pocoma.engine.port.in.query.result.ExpenseViewSnapshot(
				new ExpenseHeaderSnapshot(
						ExpenseId.of(expenseId),
						PotId.of(potId),
						ShareholderId.of(shareholderId),
						com.kartaguez.pocoma.domain.pot.value.Amount.of(Fraction.of(10, 1)),
						Label.of("Lunch"),
						false,
						2),
				new ExpenseSharesSnapshot(
						ExpenseId.of(expenseId),
						PotId.of(potId),
						Map.of(ShareholderId.of(shareholderId), share),
						2)));

		mockMvc.perform(get("/api/expenses/{expenseId}", expenseId)
						.header(UserContextFactory.USER_ID_HEADER, userId.toString())
						.header(UserContextFactory.USER_SCOPES_HEADER, "expense:read"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.header.id").value(expenseId.toString()))
				.andExpect(jsonPath("$.shares.shares[0].weight.numerator").value(1));
	}

	@Test
	void rejectsInvalidVersion() throws Exception {
		mockMvc.perform(get("/api/pots/{potId}?version=0", UUID.randomUUID())
						.header(UserContextFactory.USER_ID_HEADER, UUID.randomUUID().toString())
						.header(UserContextFactory.USER_SCOPES_HEADER, "pot:read"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void missingUserScopesHeaderReturnsDedicatedBadRequest() throws Exception {
		mockMvc.perform(get("/api/pots")
						.header(UserContextFactory.USER_ID_HEADER, UUID.randomUUID().toString()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_USER_SCOPES"));
	}

	@Test
	void invalidUserScopesHeaderReturnsDedicatedBadRequest() throws Exception {
		mockMvc.perform(get("/api/pots")
						.header(UserContextFactory.USER_ID_HEADER, UUID.randomUUID().toString())
						.header(UserContextFactory.USER_SCOPES_HEADER, "not-a-scope"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_USER_SCOPES"));
	}

	@Test
	void listUserPotsForwardsNonReadScopeAndMapsForbidden() throws Exception {
		when(listUserPotsUseCase.listUserPots(any())).thenAnswer(invocation -> {
			UserContext userContext = invocation.getArgument(0);
			if (!userContext.scopes().contains(new Scope(Scope.Resource.POT, null, Scope.Action.READ))) {
				throw new BusinessRuleViolationException("MISSING_SCOPE", "missing read scope");
			}
			return java.util.List.of();
		});

		mockMvc.perform(get("/api/pots")
						.header(UserContextFactory.USER_ID_HEADER, UUID.randomUUID().toString())
						.header(UserContextFactory.USER_SCOPES_HEADER, "pot:create"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("MISSING_SCOPE"));
	}
}
