package com.kartaguez.pocoma.supra.http.rest.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.kartaguez.pocoma.domain.association.ExpenseShare;
import com.kartaguez.pocoma.domain.entity.Shareholder;
import com.kartaguez.pocoma.domain.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.value.Amount;
import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.Label;
import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.AddPotShareholdersUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreateExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreatePotUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeleteExpenseUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeletePotUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseSharesUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersDetailsUseCase;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdatePotShareholdersWeightsUseCase;
import com.kartaguez.pocoma.engine.security.UserContext;
import com.kartaguez.pocoma.supra.http.rest.spring.controller.ExpensesCommandController;
import com.kartaguez.pocoma.supra.http.rest.spring.controller.PotsCommandController;
import com.kartaguez.pocoma.supra.http.rest.spring.error.RestExceptionHandler;
import com.kartaguez.pocoma.supra.http.rest.spring.security.UserContextFactory;

class CommandControllerTest {

	private final CreatePotUseCase createPotUseCase = mock(CreatePotUseCase.class);
	private final UpdatePotDetailsUseCase updatePotDetailsUseCase = mock(UpdatePotDetailsUseCase.class);
	private final DeletePotUseCase deletePotUseCase = mock(DeletePotUseCase.class);
	private final AddPotShareholdersUseCase addPotShareholdersUseCase = mock(AddPotShareholdersUseCase.class);
	private final UpdatePotShareholdersDetailsUseCase updatePotShareholdersDetailsUseCase =
			mock(UpdatePotShareholdersDetailsUseCase.class);
	private final UpdatePotShareholdersWeightsUseCase updatePotShareholdersWeightsUseCase =
			mock(UpdatePotShareholdersWeightsUseCase.class);
	private final CreateExpenseUseCase createExpenseUseCase = mock(CreateExpenseUseCase.class);
	private final UpdateExpenseDetailsUseCase updateExpenseDetailsUseCase = mock(UpdateExpenseDetailsUseCase.class);
	private final UpdateExpenseSharesUseCase updateExpenseSharesUseCase = mock(UpdateExpenseSharesUseCase.class);
	private final DeleteExpenseUseCase deleteExpenseUseCase = mock(DeleteExpenseUseCase.class);

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		PotsCommandController potsController = new PotsCommandController(
				createPotUseCase,
				updatePotDetailsUseCase,
				deletePotUseCase,
				addPotShareholdersUseCase,
				updatePotShareholdersDetailsUseCase,
				updatePotShareholdersWeightsUseCase,
				createExpenseUseCase);
		ExpensesCommandController expensesController = new ExpensesCommandController(
				updateExpenseDetailsUseCase,
				updateExpenseSharesUseCase,
				deleteExpenseUseCase);
		mockMvc = MockMvcBuilders.standaloneSetup(potsController, expensesController)
				.setControllerAdvice(new RestExceptionHandler())
				.build();
	}

	@Test
	void createPotUsesHeaderAsUserContextAndCreatorId() throws Exception {
		UUID userId = UUID.randomUUID();
		PotId potId = PotId.of(UUID.randomUUID());
		when(createPotUseCase.createPot(any(), any())).thenReturn(new PotHeaderSnapshot(
				potId,
				Label.of("Trip"),
				UserId.of(userId),
				false,
				1));

		mockMvc.perform(post("/api/pots")
						.header(UserContextFactory.USER_ID_HEADER, userId.toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"label":"Trip","creatorId":"00000000-0000-0000-0000-000000000000"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(potId.value().toString()))
				.andExpect(jsonPath("$.label").value("Trip"))
				.andExpect(jsonPath("$.creatorId").value(userId.toString()))
				.andExpect(jsonPath("$.version").value(1));

		ArgumentCaptor<UserContext> userContextCaptor = ArgumentCaptor.forClass(UserContext.class);
		ArgumentCaptor<CreatePotCommand> commandCaptor = ArgumentCaptor.forClass(CreatePotCommand.class);
		verify(createPotUseCase).createPot(userContextCaptor.capture(), commandCaptor.capture());
		assertEquals(userId.toString(), userContextCaptor.getValue().userId());
		assertEquals(userId, commandCaptor.getValue().creatorId());
		assertEquals("Trip", commandCaptor.getValue().label());
	}

	@Test
	void updatePotDetailsUsesPathIdAndExpectedVersion() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();
		when(updatePotDetailsUseCase.updatePotDetails(any(), any())).thenReturn(new PotHeaderSnapshot(
				PotId.of(potId),
				Label.of("Updated"),
				UserId.of(userId),
				false,
				3));

		mockMvc.perform(patch("/api/pots/{potId}/details", potId)
						.header(UserContextFactory.USER_ID_HEADER, userId.toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"label":"Updated","expectedVersion":2}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(potId.toString()))
				.andExpect(jsonPath("$.version").value(3));

		ArgumentCaptor<UpdatePotDetailsCommand> commandCaptor = ArgumentCaptor.forClass(UpdatePotDetailsCommand.class);
		verify(updatePotDetailsUseCase).updatePotDetails(any(), commandCaptor.capture());
		assertEquals(potId, commandCaptor.getValue().potId());
		assertEquals(2, commandCaptor.getValue().expectedVersion());
	}

	@Test
	void createExpenseMapsResponseFractions() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();
		UUID expenseId = UUID.randomUUID();
		UUID payerId = UUID.randomUUID();
		UUID shareholderId = UUID.randomUUID();
		ExpenseShare share = new ExpenseShare(
				ExpenseId.of(expenseId),
				ShareholderId.of(shareholderId),
				Weight.of(Fraction.of(1, 3)));
		when(createExpenseUseCase.createExpense(any(), any())).thenReturn(new ExpenseSharesSnapshot(
				ExpenseId.of(expenseId),
				PotId.of(potId),
				Map.of(ShareholderId.of(shareholderId), share),
				4));

		mockMvc.perform(post("/api/pots/{potId}/expenses", potId)
						.header(UserContextFactory.USER_ID_HEADER, userId.toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "payerId":"%s",
								  "amountNumerator":42,
								  "amountDenominator":1,
								  "label":"Dinner",
								  "expectedVersion":3,
								  "shares":[{"shareholderId":"%s","weightNumerator":1,"weightDenominator":3}]
								}
								""".formatted(payerId, shareholderId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.expenseId").value(expenseId.toString()))
				.andExpect(jsonPath("$.potId").value(potId.toString()))
				.andExpect(jsonPath("$.shares[0].shareholderId").value(shareholderId.toString()))
				.andExpect(jsonPath("$.shares[0].weight.numerator").value(1))
				.andExpect(jsonPath("$.shares[0].weight.denominator").value(3));

		ArgumentCaptor<CreateExpenseCommand> commandCaptor = ArgumentCaptor.forClass(CreateExpenseCommand.class);
		verify(createExpenseUseCase).createExpense(any(), commandCaptor.capture());
		assertEquals(potId, commandCaptor.getValue().potId());
		assertEquals(payerId, commandCaptor.getValue().payerId());
		assertEquals(3, commandCaptor.getValue().expectedVersion());
	}

	@Test
	void updateShareholdersDetailsMapsShareholderResponse() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();
		UUID shareholderId = UUID.randomUUID();
		Shareholder shareholder = Shareholder.reconstitute(
				ShareholderId.of(shareholderId),
				PotId.of(potId),
				Name.of("Alice"),
				Weight.of(Fraction.of(2, 5)),
				UserId.of(userId),
				false);
		when(updatePotShareholdersDetailsUseCase.updatePotShareholdersDetails(any(), any()))
				.thenReturn(new PotShareholdersSnapshot(PotId.of(potId), Set.of(shareholder), 7));

		mockMvc.perform(patch("/api/pots/{potId}/shareholders/details", potId)
						.header(UserContextFactory.USER_ID_HEADER, userId.toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "expectedVersion":6,
								  "shareholders":[{"shareholderId":"%s","name":"Alice","userId":"%s"}]
								}
								""".formatted(shareholderId, userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.shareholders[0].id").value(shareholderId.toString()))
				.andExpect(jsonPath("$.shareholders[0].weight.numerator").value(2))
				.andExpect(jsonPath("$.shareholders[0].weight.denominator").value(5));
	}

	@Test
	void missingHeaderReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/pots")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"label":"Trip"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("MISSING_HEADER"));
	}

	@Test
	void invalidUserIdHeaderReturnsBadRequest() throws Exception {
		mockMvc.perform(post("/api/pots")
						.header(UserContextFactory.USER_ID_HEADER, "not-a-uuid")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"label":"Trip"}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_USER_ID"));
	}

	@Test
	void versionConflictReturnsConflict() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();
		when(deletePotUseCase.deletePot(any(), any())).thenThrow(new VersionConflictException("conflict"));

		mockMvc.perform(delete("/api/pots/{potId}", potId)
						.header(UserContextFactory.USER_ID_HEADER, userId.toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"expectedVersion":4}
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("POT_VERSION_CONFLICT"));
	}

	@Test
	void notFoundReturnsNotFound() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID expenseId = UUID.randomUUID();
		when(deleteExpenseUseCase.deleteExpense(any(), any()))
				.thenThrow(new BusinessEntityNotFoundException("EXPENSE", "not found"));

		mockMvc.perform(delete("/api/expenses/{expenseId}", expenseId)
						.header(UserContextFactory.USER_ID_HEADER, userId.toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"expectedVersion":4}
								"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("EXPENSE"));
	}

	@Test
	void businessRuleViolationReturnsForbidden() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID expenseId = UUID.randomUUID();
		when(updateExpenseDetailsUseCase.updateExpenseDetails(any(), any()))
				.thenThrow(new BusinessRuleViolationException("FORBIDDEN_RULE", "forbidden"));

		mockMvc.perform(patch("/api/expenses/{expenseId}/details", expenseId)
						.header(UserContextFactory.USER_ID_HEADER, userId.toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{
								  "payerId":"%s",
								  "amountNumerator":10,
								  "amountDenominator":1,
								  "label":"Dinner",
								  "expectedVersion":4
								}
								""".formatted(UUID.randomUUID())))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("FORBIDDEN_RULE"));
	}

	@Test
	void deleteExpenseMapsHeaderAndResponse() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID expenseId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();
		UUID payerId = UUID.randomUUID();
		when(deleteExpenseUseCase.deleteExpense(any(), any())).thenReturn(new ExpenseHeaderSnapshot(
				ExpenseId.of(expenseId),
				PotId.of(potId),
				ShareholderId.of(payerId),
				Amount.of(Fraction.of(12, 5)),
				Label.of("Dinner"),
				true,
				9));

		mockMvc.perform(delete("/api/expenses/{expenseId}", expenseId)
						.header(UserContextFactory.USER_ID_HEADER, userId.toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"expectedVersion":8}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(expenseId.toString()))
				.andExpect(jsonPath("$.amount.numerator").value(12))
				.andExpect(jsonPath("$.amount.denominator").value(5))
				.andExpect(jsonPath("$.deleted").value(true));
	}
}
