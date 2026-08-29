package com.kartaguez.pocoma.engine.service.processing.command;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.engine.processing.command.ordering.CommandOrderingKey;
import com.kartaguez.pocoma.engine.port.in.processing.command.input.ClaimNextCommandInput;
import com.kartaguez.pocoma.engine.port.in.processing.command.result.CommandClaimResult;
import com.kartaguez.pocoma.engine.port.in.processing.command.usecase.ClaimNextCommandUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.input.TryAcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.Acquired;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.AlreadyCompleted;
import com.kartaguez.pocoma.engine.port.in.consumption.result.TryAcquireConsumptionResult.AlreadyFailed;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.TryAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.command.CommandPort;
import com.kartaguez.pocoma.engine.port.out.processing.command.model.RecordedCommand;

public final class ClaimNextCommandService implements ClaimNextCommandUseCase {

	private final CommandPort commandPort;
	private final TryAcquireConsumptionUseCase tryAcquireConsumptionUseCase;

	public ClaimNextCommandService(
			CommandPort commandPort,
			TryAcquireConsumptionUseCase tryAcquireConsumptionUseCase) {
		this.commandPort = requireNonNull(commandPort, "commandPort must not be null");
		this.tryAcquireConsumptionUseCase = requireNonNull(
				tryAcquireConsumptionUseCase, "tryAcquireConsumptionUseCase must not be null");
	}

	@Override
	public Optional<CommandClaimResult> claimNext(ClaimNextCommandInput input) {
		requireNonNull(input, "input must not be null");
		Optional<CommandOrderingKey> cursor = Optional.empty();
		while (true) {
			Optional<RecordedCommand> candidate = commandPort.findNextReady(input.segment(), cursor);
			if (candidate.isEmpty()) {
				return Optional.empty();
			}
			RecordedCommand command = candidate.orElseThrow();
			TryAcquireConsumptionResult acquisition = tryAcquireConsumptionUseCase.tryAcquire(
					new TryAcquireConsumptionInput(
							CommandProcessingKeys.forCommand(command.commandId()),
							input.workerId(),
							input.lease()));
			if (acquisition instanceof Acquired acquired) {
				return Optional.of(new CommandClaimResult(command, acquired.claim()));
			}
			if (acquisition instanceof AlreadyCompleted) {
				commandPort.markCompleted(command.commandId());
			}
			else if (acquisition instanceof AlreadyFailed failed) {
				commandPort.markFailed(command.commandId(), failed.failure());
			}
			cursor = Optional.of(new CommandOrderingKey(command.createdAt(), command.commandId()));
		}
	}
}
