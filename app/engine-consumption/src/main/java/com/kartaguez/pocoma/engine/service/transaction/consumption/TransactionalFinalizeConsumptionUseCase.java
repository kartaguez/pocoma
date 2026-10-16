package com.kartaguez.pocoma.engine.service.transaction.consumption;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionQueryPort;
import com.kartaguez.pocoma.engine.service.consumption.ConsumptionOwnership;

public final class TransactionalFinalizeConsumptionUseCase implements FinalizeConsumptionUseCase {
	private final FinalizeConsumptionUseCase delegate;
	private final TransactionRunner transactionRunner;
	private final ConsumptionQueryPort query;

	public TransactionalFinalizeConsumptionUseCase(
			FinalizeConsumptionUseCase delegate, TransactionRunner transactionRunner) {
		this(delegate, transactionRunner, null);
	}

	public TransactionalFinalizeConsumptionUseCase(
			FinalizeConsumptionUseCase delegate, TransactionRunner transactionRunner,
			ConsumptionQueryPort query) {
		this.query = query;
		this.delegate = requireNonNull(delegate, "delegate must not be null");
		this.transactionRunner = requireNonNull(transactionRunner, "transactionRunner must not be null");
	}

	@Override
	public FencedMutationResult finalizeConsumption(FinalizeConsumptionInput input) {
		try {
		return transactionRunner.runInTransaction(() -> delegate.finalizeConsumption(input));
		} catch (LostClaimException lost) {
			return FencedMutationResult.LOST_CLAIM;
		} catch (RuntimeException failure) {
			if (input != null && query != null && ConsumptionOwnership.lostAfterSqlRollback(query, input.slotId(), input.claimId(), failure))
				return FencedMutationResult.LOST_CLAIM;
			throw failure;
	}
}
}
