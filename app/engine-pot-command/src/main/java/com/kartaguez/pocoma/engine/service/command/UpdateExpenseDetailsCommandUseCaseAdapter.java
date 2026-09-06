package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.policy.UpdateExpenseDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseDetailsCommand;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;

public final class UpdateExpenseDetailsCommandUseCaseAdapter
		extends AbstractPotCommandUseCaseAdapter<UpdateExpenseDetailsCommand> {

	private final ExpenseContextPort expenseContextPort;
	private final ExpenseHeaderPort expenseHeaderPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final UpdateExpenseDetailsAuthorizationPolicy authorizationPolicy;

	public UpdateExpenseDetailsCommandUseCaseAdapter(ExpenseContextPort expenseContextPort,
			ExpenseHeaderPort expenseHeaderPort, PotGlobalVersionPort potGlobalVersionPort,
			UpdateExpenseDetailsAuthorizationPolicy authorizationPolicy) {
		this.expenseContextPort = requireNonNull(expenseContextPort, "expenseContextPort must not be null");
		this.expenseHeaderPort = requireNonNull(expenseHeaderPort, "expenseHeaderPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<UpdateExpenseDetailsCommand> commandClass() { return UpdateExpenseDetailsCommand.class; }

	@Override
	public CommandUseCaseResult execute(AuthorizationSnapshot authorization, UpdateExpenseDetailsCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				PotBusinessUseCaseFactory.updateExpenseDetails(invocation.recording(expenseContextPort), expenseHeaderPort,
						potGlobalVersionPort, invocation, authorizationPolicy)
						.updateExpenseDetails(userContext, command));
	}
}
