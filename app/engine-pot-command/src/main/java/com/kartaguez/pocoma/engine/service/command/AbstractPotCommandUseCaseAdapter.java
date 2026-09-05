package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCase;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.security.UserContext;

abstract class AbstractPotCommandUseCaseAdapter<C extends Command> implements CommandUseCase<C> {

	protected final CommandUseCaseResult executeAdapted(
			AuthorizationSnapshot authorization,
			C command,
			AdaptedExecution execution) {
		requireNonNull(command, "command must not be null");
		requireNonNull(execution, "execution must not be null");
		requireNonNull(authorization, "authorization must not be null");
		UserContext userContext = new UserContext(
				UserId.of(authorization.userId().value()),
				authorization.permissions());
		PotCommandInvocation invocation = new PotCommandInvocation();
		try {
			execution.execute(invocation, userContext);
			return new CommandUseCaseResult.Succeeded(invocation.inputs(), invocation.events());
		}
		catch (BusinessRuleViolationException exception) {
			return rejected(invocation, exception.ruleCode(), exception);
		}
		catch (VersionConflictException exception) {
			return rejected(invocation, exception.conflictCode(), exception);
		}
		catch (BusinessEntityNotFoundException exception) {
			return rejected(invocation, exception.entityCode(), exception);
		}
	}

	private static CommandUseCaseResult rejected(
			PotCommandInvocation invocation,
			String reasonCode,
			RuntimeException cause) {
		if (!invocation.events().isEmpty()) {
			throw new IllegalStateException("A Pot Command cannot be rejected after producing an Event", cause);
		}
		return new CommandUseCaseResult.Rejected(new TerminalReason(reasonCode), invocation.inputs());
	}

	@FunctionalInterface
	protected interface AdaptedExecution {
		void execute(PotCommandInvocation invocation, UserContext userContext);
	}
}
