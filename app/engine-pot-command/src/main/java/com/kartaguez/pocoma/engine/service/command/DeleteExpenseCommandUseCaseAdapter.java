package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.policy.DeleteExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeleteExpenseCommand;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;

public final class DeleteExpenseCommandUseCaseAdapter extends AbstractPotCommandUseCaseAdapter<DeleteExpenseCommand> {

	private final ExpenseContextPort expenseContextPort;
	private final ExpenseHeaderPort expenseHeaderPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final DeleteExpenseAuthorizationPolicy authorizationPolicy;

	public DeleteExpenseCommandUseCaseAdapter(ExpenseContextPort expenseContextPort, ExpenseHeaderPort expenseHeaderPort,
			PotGlobalVersionPort potGlobalVersionPort, DeleteExpenseAuthorizationPolicy authorizationPolicy) {
		this.expenseContextPort = requireNonNull(expenseContextPort, "expenseContextPort must not be null");
		this.expenseHeaderPort = requireNonNull(expenseHeaderPort, "expenseHeaderPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<DeleteExpenseCommand> commandClass() { return DeleteExpenseCommand.class; }

	@Override
	public CommandUseCaseResult execute(AuthorizationSnapshot authorization, DeleteExpenseCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				PotBusinessUseCaseFactory.deleteExpense(invocation.recording(expenseContextPort), expenseHeaderPort,
						potGlobalVersionPort, invocation, authorizationPolicy)
						.deleteExpense(userContext, command));
	}
}
