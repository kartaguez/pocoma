package com.kartaguez.pocoma.engine.command.dispatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.event.BusinessEvent;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.command.model.PocomaUserId;

class CommandDispatcherTest {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
	private static final AuthorizationSnapshot AUTHORIZATION = new AuthorizationSnapshot(
			new PocomaUserId(UUID.randomUUID()), Set.of(), NOW, NOW, NOW.plusSeconds(60), "issuer");

	@Test
	void dispatchesTheExactCommandClassAndPassesAuthorizationUnchanged() {
		AtomicReference<AuthorizationSnapshot> received = new AtomicReference<>();
		TestBusinessEvent first = new TestBusinessEvent("created");
		TestBusinessEvent second = new TestBusinessEvent("updated");
		CommandUseCase<TestCommand> useCase = useCase((authorization, command) -> {
			received.set(authorization);
			return new CommandUseCaseResult.Succeeded(List.of(), List.of(first, second));
		});

		CommandUseCaseResult.Succeeded result = assertInstanceOf(CommandUseCaseResult.Succeeded.class,
				new CommandDispatcher(List.of(useCase)).dispatch(AUTHORIZATION, new TestCommand("value")));

		assertSame(AUTHORIZATION, received.get());
		assertEquals(List.of(first, second), result.events());
	}

	@Test
	void rejectsUnknownDuplicateAndNullUseCases() {
		CommandUseCase<TestCommand> useCase = useCase((authorization, command) ->
				new CommandUseCaseResult.Succeeded(List.of(), List.of()));
		assertThrows(MissingCommandUseCaseException.class,
				() -> new CommandDispatcher(List.of()).dispatch(AUTHORIZATION, new TestCommand("value")));
		assertThrows(IllegalArgumentException.class, () -> new CommandDispatcher(List.of(useCase, useCase)));
		assertThrows(NullPointerException.class, () -> new CommandDispatcher(null));
		assertThrows(NullPointerException.class,
				() -> new CommandDispatcher(Collections.singletonList(null)));
	}

	@Test
	void propagatesTechnicalFailuresWithoutTurningThemIntoRejections() {
		TechnicalFailure expected = new TechnicalFailure();
		CommandDispatcher dispatcher = new CommandDispatcher(List.of(useCase((authorization, command) -> {
			throw expected;
		})));

		TechnicalFailure actual = assertThrows(TechnicalFailure.class,
				() -> dispatcher.dispatch(AUTHORIZATION, new TestCommand("value")));

		assertSame(expected, actual);
	}

	@Test
	void preservesBusinessRejectionReason() {
		TerminalReason reason = new TerminalReason("BUSINESS_CONFLICT");
		CommandDispatcher dispatcher = new CommandDispatcher(List.of(useCase((authorization, command) ->
				new CommandUseCaseResult.Rejected(reason, List.of()))));

		CommandUseCaseResult.Rejected rejected = assertInstanceOf(CommandUseCaseResult.Rejected.class,
				dispatcher.dispatch(AUTHORIZATION, new TestCommand("value")));

		assertEquals(reason, rejected.reason());
	}

	private static CommandUseCase<TestCommand> useCase(Executor executor) {
		return new CommandUseCase<>() {
			@Override public Class<TestCommand> commandClass() { return TestCommand.class; }
			@Override public CommandUseCaseResult execute(AuthorizationSnapshot authorization, TestCommand command) {
				return executor.execute(authorization, command);
			}
		};
	}

	@FunctionalInterface
	private interface Executor {
		CommandUseCaseResult execute(AuthorizationSnapshot authorization, TestCommand command);
	}

	private record TestCommand(String value) implements Command {}
	private record TestBusinessEvent(String change) implements BusinessEvent {}
	private static final class TechnicalFailure extends RuntimeException {}
}
