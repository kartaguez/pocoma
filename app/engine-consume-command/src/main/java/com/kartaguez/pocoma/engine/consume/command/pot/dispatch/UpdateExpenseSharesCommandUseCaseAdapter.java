package com.kartaguez.pocoma.engine.consume.command.pot.dispatch;

import com.kartaguez.pocoma.engine.write.pot.service.PotAuthorizationGuard;

import com.kartaguez.pocoma.engine.write.pot.service.PotBusinessUseCaseFactory;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.consume.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.consume.command.model.CommandExecutionAuthorization;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.UpdateExpenseSharesCommand;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotGlobalVersionPort;

public final class UpdateExpenseSharesCommandUseCaseAdapter
		extends AbstractPotCommandUseCaseAdapter<UpdateExpenseSharesCommand> {

	private final ExpenseContextPort expenseContextPort;
	private final ExpenseSharesPort expenseSharesPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final PotAuthorizationGuard authorizationPolicy;

	public UpdateExpenseSharesCommandUseCaseAdapter(ExpenseContextPort expenseContextPort,
			ExpenseSharesPort expenseSharesPort, PotGlobalVersionPort potGlobalVersionPort,
			PotAuthorizationGuard authorizationPolicy) {
		this.expenseContextPort = requireNonNull(expenseContextPort, "expenseContextPort must not be null");
		this.expenseSharesPort = requireNonNull(expenseSharesPort, "expenseSharesPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<UpdateExpenseSharesCommand> commandClass() { return UpdateExpenseSharesCommand.class; }

	@Override
	public CommandUseCaseResult execute(CommandExecutionAuthorization authorization, UpdateExpenseSharesCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				PotBusinessUseCaseFactory.updateExpenseShares(invocation.recording(expenseContextPort), expenseSharesPort,
						potGlobalVersionPort, invocation, authorizationPolicy)
						.updateExpenseShares(userContext, PotCommandInputMapper.toInput(command)));
	}
}
