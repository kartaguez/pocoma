package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.policy.UpdateExpenseSharesAuthorizationPolicy;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseSharesCommand;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;

public final class UpdateExpenseSharesCommandUseCaseAdapter
		extends AbstractPotCommandUseCaseAdapter<UpdateExpenseSharesCommand> {

	private final ExpenseContextPort expenseContextPort;
	private final ExpenseSharesPort expenseSharesPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final UpdateExpenseSharesAuthorizationPolicy authorizationPolicy;

	public UpdateExpenseSharesCommandUseCaseAdapter(ExpenseContextPort expenseContextPort,
			ExpenseSharesPort expenseSharesPort, PotGlobalVersionPort potGlobalVersionPort,
			UpdateExpenseSharesAuthorizationPolicy authorizationPolicy) {
		this.expenseContextPort = requireNonNull(expenseContextPort, "expenseContextPort must not be null");
		this.expenseSharesPort = requireNonNull(expenseSharesPort, "expenseSharesPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<UpdateExpenseSharesCommand> commandClass() { return UpdateExpenseSharesCommand.class; }

	@Override
	public CommandUseCaseResult execute(AuthorizationSnapshot authorization, UpdateExpenseSharesCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				new UpdateExpenseSharesService(invocation.recording(expenseContextPort), expenseSharesPort,
						potGlobalVersionPort, expenseSharesPort, invocation, authorizationPolicy)
						.updateExpenseShares(userContext, command));
	}
}
