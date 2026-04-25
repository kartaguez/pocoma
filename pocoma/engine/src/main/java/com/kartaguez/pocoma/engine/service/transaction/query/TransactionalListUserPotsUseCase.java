package com.kartaguez.pocoma.engine.service.transaction.query;

import java.util.List;
import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotsUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalListUserPotsUseCase implements ListUserPotsUseCase {

	private final ListUserPotsUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalListUserPotsUseCase(ListUserPotsUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public List<PotHeaderSnapshot> listUserPots(UserContext userContext) {
		return transactionRunner.runInTransaction(() -> delegate.listUserPots(userContext));
	}
}
