package com.kartaguez.pocoma.engine.service.command;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.policy.CreateExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;

public final class CreateExpenseCommandUseCaseAdapter extends AbstractPotCommandUseCaseAdapter<CreateExpenseCommand> {

	private final PotContextPort potContextPort;
	private final PotGlobalVersionPort potGlobalVersionPort;
	private final ExpenseHeaderPort expenseHeaderPort;
	private final ExpenseSharesPort expenseSharesPort;
	private final CreateExpenseAuthorizationPolicy authorizationPolicy;

	public CreateExpenseCommandUseCaseAdapter(PotContextPort potContextPort, PotGlobalVersionPort potGlobalVersionPort,
			ExpenseHeaderPort expenseHeaderPort, ExpenseSharesPort expenseSharesPort,
			CreateExpenseAuthorizationPolicy authorizationPolicy) {
		this.potContextPort = requireNonNull(potContextPort, "potContextPort must not be null");
		this.potGlobalVersionPort = requireNonNull(potGlobalVersionPort, "potGlobalVersionPort must not be null");
		this.expenseHeaderPort = requireNonNull(expenseHeaderPort, "expenseHeaderPort must not be null");
		this.expenseSharesPort = requireNonNull(expenseSharesPort, "expenseSharesPort must not be null");
		this.authorizationPolicy = requireNonNull(authorizationPolicy, "authorizationPolicy must not be null");
	}

	@Override public Class<CreateExpenseCommand> commandClass() { return CreateExpenseCommand.class; }

	@Override
	public CommandUseCaseResult execute(AuthorizationSnapshot authorization, CreateExpenseCommand command) {
		return executeAdapted(authorization, command, (invocation, userContext) ->
				new CreateExpenseService(invocation.recording(potContextPort), potGlobalVersionPort,
						expenseHeaderPort, expenseSharesPort, invocation, authorizationPolicy)
						.createExpense(userContext, command));
	}
}
