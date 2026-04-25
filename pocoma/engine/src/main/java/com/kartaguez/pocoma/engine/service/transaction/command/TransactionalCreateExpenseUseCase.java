package com.kartaguez.pocoma.engine.service.transaction.command;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.usecase.CreateExpenseUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalCreateExpenseUseCase implements CreateExpenseUseCase {

	private final CreateExpenseUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalCreateExpenseUseCase(CreateExpenseUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ExpenseSharesSnapshot createExpense(UserContext userContext, CreateExpenseCommand command) {
		return transactionRunner.runInTransaction(() -> delegate.createExpense(userContext, command));
	}
}
