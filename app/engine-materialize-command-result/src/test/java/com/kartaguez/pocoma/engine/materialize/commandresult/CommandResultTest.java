package com.kartaguez.pocoma.engine.materialize.commandresult;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.read.commandresult.CommandResultStore;
import com.kartaguez.pocoma.engine.read.commandresult.ImmutableCommandResult;
import com.kartaguez.pocoma.engine.read.commandresult.GetCommandResultService;
import com.kartaguez.pocoma.engine.read.commandresult.GetCommandResult;
import com.kartaguez.pocoma.contracts.command.CommandId;
import com.kartaguez.pocoma.engine.consume.command.model.CommandOutcome;

class CommandResultTest {
	private static final CommandId ID = new CommandId(UUID.randomUUID());
	private static final ExternalIdentity OWNER = new ExternalIdentity("issuer", "subject");
	private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");

	@Test void materializesAllTerminalOutcomesAndPreservesExactHistoricalOwner() {
		for (CommandOutcome outcome : new CommandOutcome[] {
				new CommandOutcome.Applied(ID, UUID.randomUUID(), 7, NOW),
				new CommandOutcome.Rejected(ID, "POT_VERSION_CONFLICT", NOW),
				new CommandOutcome.Failed(ID, CommandOutcome.PUBLIC_FAILURE_CODE, NOW) }) {
			MemoryStore store = new MemoryStore();
			new MaterializeCommandResultService(store).materialize(ID.value(), source(outcome));
			assertEquals(new ImmutableCommandResult(OWNER, MaterializeCommandResultService.publish(outcome)), store.value);
			GetCommandResultService get = new GetCommandResultService(store);
			assertInstanceOf(GetCommandResult.NotFound.class, get.get(ID, new ExternalIdentity("issuer", "other")));
			assertInstanceOf(GetCommandResult.NotFound.class, get.get(ID, new ExternalIdentity("other", "subject")));
			assertEquals(outcome.resolvedAt(), switch (get.get(ID, OWNER)) {
				case GetCommandResult.Applied applied -> applied.resolvedAt();
				case GetCommandResult.Rejected rejected -> rejected.resolvedAt();
				case GetCommandResult.Failed failed -> failed.resolvedAt();
				case GetCommandResult.NotFound ignored -> throw new AssertionError();
			});
		}
	}

	@Test void rejectsEachSourceMismatchBeforePublishing() {
		MemoryStore store = new MemoryStore();
		var materializer = new MaterializeCommandResultService(store);
		CommandOutcome outcome = new CommandOutcome.Applied(ID, UUID.randomUUID(), 1, NOW);
		assertThrows(IllegalStateException.class, () -> materializer.materialize(UUID.randomUUID(), source(outcome)));
		assertThrows(IllegalStateException.class, () -> materializer.materialize(ID.value(),
				new CommandResultSource(UUID.randomUUID(), "COMMAND_APPLIED", outcome, ID.value(), OWNER)));
		assertThrows(IllegalStateException.class, () -> materializer.materialize(ID.value(),
				new CommandResultSource(ID.value(), "COMMAND_APPLIED", outcome, UUID.randomUUID(), OWNER)));
		assertThrows(IllegalStateException.class, () -> materializer.materialize(ID.value(),
				new CommandResultSource(ID.value(), "COMMAND_REJECTED", outcome, ID.value(), OWNER)));
		assertThrows(IllegalStateException.class, () -> materializer.materialize(ID.value(),
				new CommandResultSource(ID.value(), "COMMAND_APPLIED", outcome, ID.value(), null)));
		assertNull(store.value);
	}

	@Test void replayIsIdempotentAndDivergenceIsAnInvariantViolation() {
		MemoryStore store = new MemoryStore();
		var materializer = new MaterializeCommandResultService(store);
		CommandOutcome outcome = new CommandOutcome.Rejected(ID, "DENIED", NOW);
		materializer.materialize(ID.value(), source(outcome));
		materializer.materialize(ID.value(), source(outcome));
		assertEquals(2, store.calls);
		assertThrows(IllegalStateException.class, () -> materializer.materialize(ID.value(),
				source(new CommandOutcome.Rejected(ID, "DIFFERENT", NOW))));
		assertThrows(IllegalStateException.class, () -> store.ensureResult(new ImmutableCommandResult(
				new ExternalIdentity("other", "subject"), MaterializeCommandResultService.publish(outcome))));
	}

	private static CommandResultSource source(CommandOutcome outcome) {
		String event = switch (outcome) {
			case CommandOutcome.Applied ignored -> "COMMAND_APPLIED";
			case CommandOutcome.Rejected ignored -> "COMMAND_REJECTED";
			case CommandOutcome.Failed ignored -> "COMMAND_FAILED";
		};
		return new CommandResultSource(ID.value(), event, outcome, ID.value(), OWNER);
	}

	private static final class MemoryStore implements CommandResultStore {
		private ImmutableCommandResult value;
		private int calls;
		@Override public void ensureResult(ImmutableCommandResult result) {
			calls++;
			if (value == null) value = result;
			else if (!value.equals(result)) throw new IllegalStateException("divergent");
		}
		@Override public Optional<ImmutableCommandResult> find(CommandId id) {
			return Optional.ofNullable(value).filter(v -> v.outcome().commandId().equals(id));
		}
	}
}
