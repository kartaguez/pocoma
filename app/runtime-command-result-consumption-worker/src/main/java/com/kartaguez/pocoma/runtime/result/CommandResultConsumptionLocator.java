package com.kartaguez.pocoma.runtime.result;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.engine.command.result.MaterializeCommandResultService;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.command.JdbcCommandResultSource;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionSearch;
import com.kartaguez.pocoma.orchestrator.consumption.locator.LocatedConsumption;

/** Direct terminal Event consumer, with an identity disjoint from historical Event-to-Task slots. */
public final class CommandResultConsumptionLocator implements ConsumptionLocator {
	private final int segmentIndex, segmentCount;
	private final JdbcCommandResultSource source;
	private final MaterializeCommandResultService materializer;
	private final Clock clock;

	public CommandResultConsumptionLocator(int segmentIndex, int segmentCount, JdbcCommandResultSource source,
			MaterializeCommandResultService materializer, Clock clock) {
		this.segmentIndex = segmentIndex; this.segmentCount = segmentCount;
		this.source = source; this.materializer = materializer; this.clock = clock;
	}

	@Override public ConsumptionSearch openSearch() {
		return new ConsumptionSearch() {
			private Optional<JdbcCommandResultSource.Cursor> cursor = Optional.empty();
			private final java.time.Instant now = clock.instant();
			@Override public Optional<LocatedConsumption> next() {
				var candidate = source.next(segmentIndex, segmentCount, now, cursor);
				if (candidate.isEmpty()) return Optional.empty();
				var found = candidate.orElseThrow(); cursor = Optional.of(found.cursor());
				return Optional.of(new LocatedConsumption(key(found.eventId()), context -> {
					materializer.materialize(found.commandId(), source.reload(found.eventId()));
					return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(),
							List.of(new ConsumptionInput(context.slotId(), "COMMAND_TERMINAL_EVENT",
									found.eventId().toString(), 1)), List.of());
				}, failure -> new ProcessingFailure(new ProcessingFailureCode("COMMAND_RESULT_MATERIALIZATION"),
					failure instanceof IllegalStateException ? "COMMAND_RESULT_INVARIANT" : "COMMAND_RESULT_TECHNICAL",
					String.valueOf(failure.getMessage()), clock.instant())));
			}
		};
	}

	public static ConsumptionFailurePolicy failurePolicy() {
		return context -> context.failure().category().equals("COMMAND_RESULT_INVARIANT")
				? new FailureDecision.Fail() : new FailureDecision.RetryAfter(Duration.ofSeconds(5));
	}

	public static ConsumptionKey key(UUID eventId) {
		return new ConsumptionKey(new ConsumableIdentity("COMMAND_TERMINAL_EVENT", List.of(eventId.toString())),
				new ConsumerIdentity(JdbcCommandResultSource.CONSUMER_TYPE, List.of()));
	}
}
