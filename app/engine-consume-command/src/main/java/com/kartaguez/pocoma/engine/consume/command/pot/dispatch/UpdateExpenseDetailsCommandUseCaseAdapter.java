package com.kartaguez.pocoma.engine.consume.command.pot.dispatch;

import com.kartaguez.pocoma.engine.write.pot.service.PotAuthorizationGuard;

import com.kartaguez.pocoma.engine.write.pot.service.PotBusinessUseCaseFactory;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.consume.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.consume.command.model.CommandExecutionAuthorization;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.UpdateExpenseDetailsCommand;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotGlobalVersionPort;

public final class UpdateExpenseDetailsCommandUseCaseAdapter
		extends AbstractPotCommandUseCaseAdapter<UpdateExpenseDetailsCommand> {

	private final ExpenseContextPort expenseContextPort;
	private final ExpenseHeaderPort expenseHeaderPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final PotAuthorizationGuard authorizationPolicy;

	public UpdateExpenseDetailsCommandUseCaseAdapter(ExpenseContextPort expenseContextPort,
			ExpenseHeaderPort expenseHeaderPort, PotGlobalVersionPort potGlobalVersionPort,
			PotAuthorizationGuard authorizationPolicy) {
		this.expenseContextPort = requireNonNull(expenseContextPort, "expenseContextPort must not be null");
		this.expenseHeaderPort = requireNonNull(expenseHeaderPort, "expenseHeaderPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<UpdateExpenseDetailsCommand> commandClass() { return UpdateExpenseDetailsCommand.class; }

	@Override
	public CommandUseCaseResult execute(CommandExecutionAuthorization authorization, UpdateExpenseDetailsCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				PotBusinessUseCaseFactory.updateExpenseDetails(invocation.recording(expenseContextPort), expenseHeaderPort,
						potGlobalVersionPort, invocation, authorizationPolicy)
						.updateExpenseDetails(userContext, PotCommandInputMapper.toInput(command)));
	}
}
