package com.kartaguez.pocoma.engine.consume.command.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.List;

import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.engine.consume.command.execution.BindingFenceConflictException;
import com.kartaguez.pocoma.engine.consume.command.execution.BindingFenceLostException;
import com.kartaguez.pocoma.engine.consume.command.model.CommandOutcome;
import com.kartaguez.pocoma.engine.consume.command.port.out.CommandOutcomePublicationPort;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;

/** Rechecks exact WRITE authority only after the failed business transaction has rolled back. */
public final class BindingFenceRecoveryExecuteUseCase implements ExecuteConsumptionUseCase {
	private static final String NOT_CURRENT = "CALLER_IDENTITY_NOT_CURRENT";
	private final ExecuteConsumptionUseCase transactionalExecute;
	private final TransactionRunner transactions;
	private final ConsumptionLifecyclePersistencePort lifecycle;
	private final ExternalIdentityBindingPort bindings;
	private final CommandOutcomePublicationPort outcomes;
	private final Clock clock;

	public BindingFenceRecoveryExecuteUseCase(ExecuteConsumptionUseCase transactionalExecute,
			TransactionRunner transactions, ConsumptionLifecyclePersistencePort lifecycle,
			ExternalIdentityBindingPort bindings, CommandOutcomePublicationPort outcomes, Clock clock) {
		this.transactionalExecute = requireNonNull(transactionalExecute);
		this.transactions = requireNonNull(transactions);
		this.lifecycle = requireNonNull(lifecycle);
		this.bindings = requireNonNull(bindings);
		this.outcomes = requireNonNull(outcomes);
		this.clock = requireNonNull(clock);
	}

	@Override
	public ConsumptionExecutionResult execute(ExecuteConsumptionInput input) {
		try {
			return transactionalExecute.execute(input);
		} catch (BindingFenceLostException lost) {
			return transactions.runInTransaction(() -> {
				if (!lifecycle.lockCurrentClaim(input.slotId(), input.claimId())) {
					throw new LostClaimException(input.slotId(), input.claimId());
				}
				if (bindings.findUserId(lost.identity(), lost.bindingId()).isPresent()) {
					throw new BindingFenceConflictException(lost);
				}
				return transactionalExecute.execute(new ExecuteConsumptionInput(input.slotId(), input.claimId(), context -> {
					outcomes.publish(new CommandOutcome.Rejected(lost.commandId(), NOT_CURRENT, clock.instant()));
					return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Rejected(NOT_CURRENT),
							List.of(), List.of());
				}));
			});
		}
	}
}
