package com.kartaguez.pocoma.engine.service.transaction.query;

import java.util.List;
import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.intent.ListPotExpensesQuery;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListPotExpensesUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalListPotExpensesUseCase implements ListPotExpensesUseCase {

	private final ListPotExpensesUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalListPotExpensesUseCase(ListPotExpensesUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public List<ExpenseHeaderSnapshot> listPotExpenses(UserContext userContext, ListPotExpensesQuery query) {
		return transactionRunner.runInTransaction(() -> delegate.listPotExpenses(userContext, query));
	}
}
