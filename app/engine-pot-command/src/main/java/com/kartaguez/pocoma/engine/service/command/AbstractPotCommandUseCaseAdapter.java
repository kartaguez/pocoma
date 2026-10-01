package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.pot.exception.BusinessRuleViolationException;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCase;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.execution.CommandExecutionInvariantViolationException;
import com.kartaguez.pocoma.engine.command.model.CommandAppliedResult;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionAuthorization;
import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.exception.BusinessEntityNotFoundException;
import com.kartaguez.pocoma.engine.exception.VersionConflictException;
import com.kartaguez.pocoma.engine.security.UserContext;
import com.kartaguez.pocoma.engine.snapshot.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.snapshot.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.snapshot.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.snapshot.PotShareholdersSnapshot;

abstract class AbstractPotCommandUseCaseAdapter<C extends Command> implements CommandUseCase<C> {

	protected final CommandUseCaseResult executeAdapted(
			CommandExecutionAuthorization authorization,
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
			Object snapshot = requireNonNull(execution.execute(invocation, userContext),
					"Command business use case must return a versioned snapshot");
			return new CommandUseCaseResult.Succeeded(
					invocation.inputs(), appliedResult(snapshot), invocation.events());
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

	private static CommandAppliedResult appliedResult(Object snapshot) {
		return switch (snapshot) {
			case PotHeaderSnapshot value -> new CommandAppliedResult(value.id().value(), value.version());
			case PotShareholdersSnapshot value -> new CommandAppliedResult(value.potId().value(), value.version());
			case ExpenseHeaderSnapshot value -> new CommandAppliedResult(value.potId().value(), value.version());
			case ExpenseSharesSnapshot value -> new CommandAppliedResult(value.potId().value(), value.version());
			default -> throw new CommandExecutionInvariantViolationException(
					"Unsupported Command result snapshot: " + snapshot.getClass().getName());
		};
	}

	private static CommandUseCaseResult rejected(
			PotCommandInvocation invocation,
			String reasonCode,
			RuntimeException cause) {
		if (!invocation.events().isEmpty()) {
			throw new CommandExecutionInvariantViolationException(
					"A Pot Command cannot be rejected after producing an Event", cause);
		}
		return new CommandUseCaseResult.Rejected(new TerminalReason(reasonCode), invocation.inputs());
	}

	@FunctionalInterface
	protected interface AdaptedExecution {
		Object execute(PotCommandInvocation invocation, UserContext userContext);
	}
}
