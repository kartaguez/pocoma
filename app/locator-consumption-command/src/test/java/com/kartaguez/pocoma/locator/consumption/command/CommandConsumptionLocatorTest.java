package com.kartaguez.pocoma.locator.consumption.command;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.engine.command.discovery.CommandConsumptionCandidate;
import com.kartaguez.pocoma.engine.command.discovery.CommandDiscoveryCursor;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;

class CommandConsumptionLocatorTest {

	@Test
	void advancesTheCursorWithoutReloadingUntilTheLocatedExecutionRuns() {
		Instant now = Instant.parse("2026-01-01T00:00:00Z");
		CommandConsumptionCandidate first = candidate(now, 1);
		CommandConsumptionCandidate second = candidate(now.plusSeconds(1), 2);
		List<Optional<CommandDiscoveryCursor>> cursors = new ArrayList<>();
		AtomicInteger executions = new AtomicInteger();
		var locator = new CommandConsumptionLocator(
				(instant, cursor) -> {
					cursors.add(cursor);
					return switch (cursors.size()) {
						case 1 -> Optional.of(first);
						case 2 -> Optional.of(second);
						default -> Optional.empty();
					};
				},
				new CommandConsumptionExecution(id -> {
					executions.incrementAndGet();
					return new com.kartaguez.pocoma.engine.command.execution.RecordedCommandExecutionResult.Succeeded(
							List.of(), List.of());
				}),
				failure -> { throw failure; },
				Clock.fixed(now, ZoneOffset.UTC));

		var search = locator.openSearch();
		var locatedFirst = search.next().orElseThrow();
		var locatedSecond = search.next().orElseThrow();

		assertEquals(0, executions.get());
		assertEquals(List.of(Optional.empty(), Optional.of(first.cursor())), cursors);
		assertEquals(CommandConsumptionKeys.forCommand(first.commandId()), locatedFirst.consumptionKey());
		assertEquals(CommandConsumptionKeys.forCommand(second.commandId()), locatedSecond.consumptionKey());

		locatedFirst.execution().execute(new com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecutionContext(
				UUID.randomUUID(), com.kartaguez.pocoma.domain.consumption.claim.ClaimId.generate()));
		assertEquals(1, executions.get());

		locator.openSearch().next();
		assertEquals(Optional.empty(), cursors.get(2));
	}

	private static CommandConsumptionCandidate candidate(Instant submittedAt, int suffix) {
		return new CommandConsumptionCandidate(
				new CommandId(new UUID(0, suffix)), submittedAt);
	}
}
