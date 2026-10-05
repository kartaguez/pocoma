package com.kartaguez.pocoma.engine.consume.command.pot.dispatch;

import com.kartaguez.pocoma.engine.write.pot.service.PotAuthorizationGuard;

import com.kartaguez.pocoma.engine.write.pot.service.PotBusinessUseCaseFactory;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.consume.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.consume.command.model.CommandExecutionAuthorization;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotGlobalVersionPort;

public final class CreateExpenseCommandUseCaseAdapter extends AbstractPotCommandUseCaseAdapter<CreateExpenseCommand> {

	private final PotContextPort potContextPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final ExpenseHeaderPort expenseHeaderPort;
	private final ExpenseSharesPort expenseSharesPort;
	private final PotAuthorizationGuard authorizationPolicy;

	public CreateExpenseCommandUseCaseAdapter(PotContextPort potContextPort, PotGlobalVersionPort potGlobalVersionPort,
			ExpenseHeaderPort expenseHeaderPort, ExpenseSharesPort expenseSharesPort,
			PotAuthorizationGuard authorizationPolicy) {
		this.potContextPort = requireNonNull(potContextPort, "potContextPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.expenseHeaderPort = requireNonNull(expenseHeaderPort, "expenseHeaderPort must not be null");
		this.expenseSharesPort = requireNonNull(expenseSharesPort, "expenseSharesPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<CreateExpenseCommand> commandClass() { return CreateExpenseCommand.class; }

	@Override
	public CommandUseCaseResult execute(CommandExecutionAuthorization authorization, CreateExpenseCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				PotBusinessUseCaseFactory.createExpense(invocation.recording(potContextPort), potGlobalVersionPort,
						expenseHeaderPort, expenseSharesPort, invocation, authorizationPolicy)
						.createExpense(userContext, PotCommandInputMapper.toInput(command)));
	}
}
