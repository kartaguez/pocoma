package com.kartaguez.pocoma.engine.service.transaction.command;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseSharesCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.UpdateExpenseSharesUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalUpdateExpenseSharesUseCase implements UpdateExpenseSharesUseCase {

	private final UpdateExpenseSharesUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalUpdateExpenseSharesUseCase(
			UpdateExpenseSharesUseCase delegate,
			TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ExpenseSharesSnapshot updateExpenseShares(UserContext userContext, UpdateExpenseSharesCommand command) {
		return transactionRunner.runInTransaction(() -> delegate.updateExpenseShares(userContext, command));
	}
}
