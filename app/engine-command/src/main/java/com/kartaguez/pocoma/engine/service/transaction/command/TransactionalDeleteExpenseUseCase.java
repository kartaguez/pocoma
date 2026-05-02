package com.kartaguez.pocoma.engine.service.transaction.command;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.intent.DeleteExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.DeleteExpenseUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalDeleteExpenseUseCase implements DeleteExpenseUseCase {

	private final DeleteExpenseUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalDeleteExpenseUseCase(DeleteExpenseUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ExpenseHeaderSnapshot deleteExpense(UserContext userContext, DeleteExpenseCommand command) {
		return transactionRunner.runInTransaction(() -> delegate.deleteExpense(userContext, command));
	}
}
