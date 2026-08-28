package com.kartaguez.pocoma.engine.service.consumption;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimToken;
import com.kartaguez.pocoma.domain.consumption.claim.ConsumptionSlot;
import com.kartaguez.pocoma.engine.processing.ordering.CommandOrderingKey;
import com.kartaguez.pocoma.engine.context.consumption.ConsumableCommand;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ClaimNextCommandInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.CommandClaimResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ClaimNextCommandUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ClaimPort;
import com.kartaguez.pocoma.engine.port.out.consumption.CommandPort;

public final class ClaimNextCommandService implements ClaimNextCommandUseCase {

	private final CommandPort commandPort;
	private final ClaimPort claimPort;
	private final Clock clock;
	private final Supplier<ClaimId> claimIdFactory;
	private final Supplier<ClaimToken> claimTokenFactory;

	public ClaimNextCommandService(CommandPort commandPort, ClaimPort claimPort, Clock clock) {
		this(commandPort, claimPort, clock, ClaimId::generate, ClaimToken::generate);
	}

	public ClaimNextCommandService(
			CommandPort commandPort,
			ClaimPort claimPort,
			Clock clock,
			Supplier<ClaimId> claimIdFactory,
			Supplier<ClaimToken> claimTokenFactory) {
		this.commandPort = requireNonNull(commandPort, "commandPort must not be null");
		this.claimPort = requireNonNull(claimPort, "claimPort must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
		this.claimIdFactory = requireNonNull(claimIdFactory, "claimIdFactory must not be null");
		this.claimTokenFactory = requireNonNull(claimTokenFactory, "claimTokenFactory must not be null");
	}

	@Override
	public Optional<CommandClaimResult> claimNext(ClaimNextCommandInput input) {
		requireNonNull(input, "input must not be null");
		Optional<CommandOrderingKey> cursor = Optional.empty();
		Instant now = clock.instant();

		while (true) {
			Optional<ConsumableCommand> candidate = commandPort.findNextReady(input.segment(), cursor);
			if (candidate.isEmpty()) {
				return Optional.empty();
			}

			ConsumableCommand command = candidate.orElseThrow();
			ConsumptionSlot observedSlot = claimPort.findSlot(command.consumptionKey())
					.orElseGet(() -> ConsumptionSlot.initial(command.consumptionKey()));
			Claim proposedClaim = Claim.active(claimIdFactory.get(), command.consumptionKey(),
					claimTokenFactory.get(), input.workerId(), now, input.lease());
			Optional<Claim> acquired = claimPort.tryAcquire(observedSlot, proposedClaim, now);
			if (acquired.isPresent()) {
				return Optional.of(new CommandClaimResult(command, acquired.orElseThrow()));
			}

			cursor = Optional.of(command.orderingKey());
		}
	}
}
