package com.kartaguez.pocoma.engine.service.transaction.query;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotBalancesQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.PotBalancesSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalGetPotBalancesUseCase implements GetPotBalancesUseCase {

	private final GetPotBalancesUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalGetPotBalancesUseCase(GetPotBalancesUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public PotBalancesSnapshot getPotBalances(UserContext userContext, GetPotBalancesQuery query) {
		return transactionRunner.runInTransaction(() -> delegate.getPotBalances(userContext, query));
	}
}
