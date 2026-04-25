package com.kartaguez.pocoma.engine.service.transaction.query;

import java.util.Objects;

import com.kartaguez.pocoma.engine.port.in.query.intent.GetPotQuery;
import com.kartaguez.pocoma.engine.port.in.query.result.PotViewSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.usecase.GetPotUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.security.UserContext;

public final class TransactionalGetPotUseCase implements GetPotUseCase {

	private final GetPotUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalGetPotUseCase(GetPotUseCase delegate, TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public PotViewSnapshot getPot(UserContext userContext, GetPotQuery query) {
		return transactionRunner.runInTransaction(() -> delegate.getPot(userContext, query));
	}
}
