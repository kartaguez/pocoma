package com.kartaguez.pocoma.engine.consume.command.dispatch;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.engine.consume.command.model.CommandExecutionAuthorization;
import com.kartaguez.pocoma.engine.consume.command.model.Command;

/** Explicit immutable dispatcher from decoded Command classes to specialized use cases. */
public final class CommandDispatcher {

	private final Map<Class<? extends Command>, CommandUseCase<?>> useCases;

	public CommandDispatcher(Collection<? extends CommandUseCase<?>> useCases) {
		requireNonNull(useCases, "useCases must not be null");
		this.useCases = useCases.stream()
				.map(useCase -> requireUseCase(useCase))
				.collect(Collectors.toUnmodifiableMap(
						CommandUseCase::commandClass,
						Function.identity(),
						(left, right) -> {
							throw new IllegalArgumentException(
									"Duplicate Command use case for " + left.commandClass().getName());
						}));
	}

	public CommandUseCaseResult dispatch(CommandExecutionAuthorization authorization, Command command) {
		requireNonNull(authorization, "authorization must not be null");
		requireNonNull(command, "command must not be null");
		CommandUseCase<?> useCase = useCases.get(command.getClass());
		if (useCase == null) throw new MissingCommandUseCaseException(command.getClass());
		return execute(useCase, authorization, command);
	}

	private static <C extends Command> CommandUseCaseResult execute(
			CommandUseCase<C> useCase,
			CommandExecutionAuthorization authorization,
			Command command) {
		return requireNonNull(useCase.execute(authorization, useCase.commandClass().cast(command)),
				"Command use case result must not be null");
	}

	private static CommandUseCase<?> requireUseCase(CommandUseCase<?> useCase) {
		requireNonNull(useCase, "useCase must not be null");
		requireNonNull(useCase.commandClass(), "useCase.commandClass must not be null");
		return useCase;
	}
}
