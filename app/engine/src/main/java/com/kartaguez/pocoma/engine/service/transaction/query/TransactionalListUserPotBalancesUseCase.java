package com.kartaguez.pocoma.engine.service.transaction.query;

import java.util.List;
import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.query.intent.ListUserPotBalancesQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.UserPotBalanceSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.ListUserPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalListUserPotBalancesUseCase implements ListUserPotBalancesUseCase {

	private final ListUserPotBalancesUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalListUserPotBalancesUseCase(
			ListUserPotBalancesUseCase delegate,
			TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public List<UserPotBalanceSnapshot> listUserPotBalances(UserContext userContext, ListUserPotBalancesQuery query) {
		return transactionRunner.runInTransaction(() -> delegate.listUserPotBalances(userContext, query));
	}
}
