package com.kartaguez.pocoma.engine.service.transaction.projection;

import java.util.Objects;

import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;

public final class TransactionalComputePotBalancesUseCase implements ComputePotBalancesUseCase {

	private final ComputePotBalancesUseCase delegate;
	private final TransactionRunner transactionRunner;

	public TransactionalComputePotBalancesUseCase(
			ComputePotBalancesUseCase delegate,
			TransactionRunner transactionRunner) {
		this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public PotBalances computePotBalances(PotId potId, long targetVersion) {
		return transactionRunner.runInTransaction(() -> delegate.computePotBalances(potId, targetVersion));
	}

	@Override
	public PotBalances computePotBalancesFull(PotId potId, long targetVersion) {
		return transactionRunner.runInTransaction(() -> delegate.computePotBalancesFull(potId, targetVersion));
	}
}
