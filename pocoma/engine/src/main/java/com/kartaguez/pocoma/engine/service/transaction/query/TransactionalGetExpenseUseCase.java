package com.kartaguez.pocoma.engine.service.transaction.query;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.query.intent.GetExpenseQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.ExpenseViewSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetExpenseUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalGetExpenseUseCase implements GetExpenseUseCase {

	private final GetExpenseUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalGetExpenseUseCase(GetExpenseUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public ExpenseViewSnapshot getExpense(UserContext userContext, GetExpenseQuery query) {
		return transactionRunner.runInTransaction(() -> delegate.getExpense(userContext, query));
	}
}
