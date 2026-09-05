package com.kartaguez.pocoma.locator.consumption.command;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.kartaguez.pocoma.engine.command.discovery.CommandConsumptionCandidate;
import com.kartaguez.pocoma.engine.command.discovery.CommandDiscoveryCursor;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.port.out.CommandConsumptionDiscoveryPort;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionSearch;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.locator.LocatedConsumption;

/** Discovers durable Commands and supplies their post-acquire authoritative execution. */
public final class CommandConsumptionLocator implements ConsumptionLocator {

	private final CommandConsumptionDiscoveryPort discovery;
	private final CommandConsumptionExecution execution;
	private final ConsumptionTechnicalFailureClassifier failureClassifier;
	private final Clock clock;

	public CommandConsumptionLocator(
			CommandConsumptionDiscoveryPort discovery,
			CommandConsumptionExecution execution,
			ConsumptionTechnicalFailureClassifier failureClassifier,
			Clock clock) {
		this.discovery = requireNonNull(discovery, "discovery must not be null");
		this.execution = requireNonNull(execution, "execution must not be null");
		this.failureClassifier = requireNonNull(failureClassifier, "failureClassifier must not be null");
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ConsumptionSearch openSearch() {
		return new Search(clock.instant());
	}

	private final class Search implements ConsumptionSearch {
		private final Instant now;
		private Optional<CommandDiscoveryCursor> cursor = Optional.empty();

		private Search(Instant now) {
			this.now = now;
		}

		@Override
		public Optional<LocatedConsumption> next() {
			Optional<CommandConsumptionCandidate> candidate = discovery.findNextEligibleCandidate(now, cursor);
			if (candidate.isEmpty()) return Optional.empty();
			CommandConsumptionCandidate command = candidate.orElseThrow();
			cursor = Optional.of(command.cursor());
			CommandId commandId = command.commandId();
			return Optional.of(new LocatedConsumption(
					CommandConsumptionKeys.forCommand(commandId),
					execution.forCommand(commandId),
					failureClassifier));
		}
	}
}
