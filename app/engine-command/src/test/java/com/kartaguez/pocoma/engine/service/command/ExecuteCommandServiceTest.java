package com.kartaguez.pocoma.engine.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.engine.exception.UnsupportedCommandIntentException;
import com.kartaguez.pocoma.engine.port.in.command.input.ExecuteCommandInput;
import com.kartaguez.pocoma.engine.port.in.command.intent.AddPotShareholdersCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.CommandIntent;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeleteExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeletePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseSharesCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersWeightsCommand;
import com.kartaguez.pocoma.engine.port.in.command.usecase.ExecuteCommandUseCase;
import com.kartaguez.pocoma.engine.security.UserContext;

class ExecuteCommandServiceTest {

	private static final UUID POT_ID = UUID.randomUUID();
	private static final UUID EXPENSE_ID = UUID.randomUUID();
	private static final UUID SHAREHOLDER_ID = UUID.randomUUID();
	private static final UserContext USER_CONTEXT = new UserContext(UserId.of(UUID.randomUUID()), Set.of());

	@Test
	void routesEverySupportedIntentToItsSpecializedUseCase() {
		AtomicReference<Route> calledRoute = new AtomicReference<>();
		AtomicReference<UserContext> receivedContext = new AtomicReference<>();
		AtomicReference<CommandIntent> receivedIntent = new AtomicReference<>();
		ExecuteCommandUseCase useCase = routedUseCase(calledRoute, receivedContext, receivedIntent);

		for (RoutingCase routingCase : routingCases()) {
			calledRoute.set(null);
			receivedContext.set(null);
			receivedIntent.set(null);

			useCase.execute(new ExecuteCommandInput(USER_CONTEXT, routingCase.intent()));

			assertEquals(routingCase.route(), calledRoute.get());
			assertSame(USER_CONTEXT, receivedContext.get());
			assertSame(routingCase.intent(), receivedIntent.get());
		}
	}

	@Test
	void rejectsAnUnsupportedIntentExplicitly() {
		ExecuteCommandUseCase useCase = routedUseCase(
				new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
		CommandIntent unsupported = new CommandIntent() { };

		UnsupportedCommandIntentException exception = assertThrows(UnsupportedCommandIntentException.class,
				() -> useCase.execute(new ExecuteCommandInput(USER_CONTEXT, unsupported)));

		assertEquals(unsupported.getClass(), exception.intentType());
	}

	@Test
	void rejectsIncompleteGenericInput() {
		assertThrows(NullPointerException.class, () -> new ExecuteCommandInput(null, createPot()));
		assertThrows(NullPointerException.class, () -> new ExecuteCommandInput(USER_CONTEXT, null));
	}

	private static ExecuteCommandUseCase routedUseCase(
			AtomicReference<Route> calledRoute,
			AtomicReference<UserContext> receivedContext,
			AtomicReference<CommandIntent> receivedIntent) {
		return CommandUseCaseFactory.executeCommandUseCase(
				(context, command) -> record(calledRoute, receivedContext, receivedIntent, Route.CREATE_POT, context, command),
				(context, command) -> record(calledRoute, receivedContext, receivedIntent, Route.CREATE_EXPENSE, context, command),
				(context, command) -> record(calledRoute, receivedContext, receivedIntent, Route.ADD_SHAREHOLDERS, context, command),
				(context, command) -> record(calledRoute, receivedContext, receivedIntent, Route.DELETE_POT, context, command),
				(context, command) -> record(calledRoute, receivedContext, receivedIntent, Route.DELETE_EXPENSE, context, command),
				(context, command) -> record(calledRoute, receivedContext, receivedIntent, Route.UPDATE_POT, context, command),
				(context, command) -> record(calledRoute, receivedContext, receivedIntent, Route.UPDATE_EXPENSE, context, command),
				(context, command) -> record(calledRoute, receivedContext, receivedIntent, Route.UPDATE_SHARES, context, command),
				(context, command) -> record(calledRoute, receivedContext, receivedIntent, Route.UPDATE_SHAREHOLDER_DETAILS, context, command),
				(context, command) -> record(calledRoute, receivedContext, receivedIntent, Route.UPDATE_SHAREHOLDER_WEIGHTS, context, command));
	}

	private static <T extends CommandIntent, R> R record(
			AtomicReference<Route> calledRoute,
			AtomicReference<UserContext> receivedContext,
			AtomicReference<CommandIntent> receivedIntent,
			Route route,
			UserContext context,
			T intent) {
		calledRoute.set(route);
		receivedContext.set(context);
		receivedIntent.set(intent);
		return null;
	}

	private static List<RoutingCase> routingCases() {
		return List.of(
				new RoutingCase(Route.CREATE_POT, createPot()),
				new RoutingCase(Route.CREATE_EXPENSE, new CreateExpenseCommand(POT_ID, SHAREHOLDER_ID, 10, 1,
						"Dinner", Set.of(new CreateExpenseCommand.ExpenseShareInput(SHAREHOLDER_ID, 1, 1)), 1)),
				new RoutingCase(Route.ADD_SHAREHOLDERS, new AddPotShareholdersCommand(POT_ID,
						Set.of(new AddPotShareholdersCommand.ShareholderInput("Alice", 1, 1)), 1)),
				new RoutingCase(Route.DELETE_POT, new DeletePotCommand(POT_ID, 1)),
				new RoutingCase(Route.DELETE_EXPENSE, new DeleteExpenseCommand(EXPENSE_ID, 1)),
				new RoutingCase(Route.UPDATE_POT, new UpdatePotDetailsCommand(POT_ID, "Trip", 1)),
				new RoutingCase(Route.UPDATE_EXPENSE,
						new UpdateExpenseDetailsCommand(EXPENSE_ID, SHAREHOLDER_ID, 10, 1, "Dinner", 1)),
				new RoutingCase(Route.UPDATE_SHARES, new UpdateExpenseSharesCommand(EXPENSE_ID,
						Set.of(new UpdateExpenseSharesCommand.ExpenseShareInput(SHAREHOLDER_ID, 1, 1)), 1)),
				new RoutingCase(Route.UPDATE_SHAREHOLDER_DETAILS, new UpdatePotShareholdersDetailsCommand(POT_ID,
						Set.of(new UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput(
								SHAREHOLDER_ID, "Alice", null)), 1)),
				new RoutingCase(Route.UPDATE_SHAREHOLDER_WEIGHTS, new UpdatePotShareholdersWeightsCommand(POT_ID,
						Set.of(new UpdatePotShareholdersWeightsCommand.ShareholderWeightInput(SHAREHOLDER_ID, 1, 1)), 1)));
	}

	private static CreatePotCommand createPot() {
		return new CreatePotCommand("Trip", UUID.randomUUID());
	}

	private record RoutingCase(Route route, CommandIntent intent) { }

	private enum Route {
		CREATE_POT,
		CREATE_EXPENSE,
		ADD_SHAREHOLDERS,
		DELETE_POT,
		DELETE_EXPENSE,
		UPDATE_POT,
		UPDATE_EXPENSE,
		UPDATE_SHARES,
		UPDATE_SHAREHOLDER_DETAILS,
		UPDATE_SHAREHOLDER_WEIGHTS
	}
}
