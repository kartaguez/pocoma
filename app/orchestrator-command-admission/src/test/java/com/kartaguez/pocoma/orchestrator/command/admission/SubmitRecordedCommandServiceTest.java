package com.kartaguez.pocoma.orchestrator.command.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.orchestrator.command.admission.model.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.orchestrator.command.admission.model.CommandAuthorizationTtl;
import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmitRecordedCommandInput;

class SubmitRecordedCommandServiceTest {
	private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");
	private static final CommandId COMMAND_ID = new CommandId(UUID.randomUUID());
	private static final PocomaUserId USER_ID = new PocomaUserId(UUID.randomUUID());

	@Test
	void resolvesRecordsAndReturnsTheServerGeneratedIdentityInsideOneTransaction() {
		List<RecordedCommand> inserted = new ArrayList<>();
		CountingTransactions transactions = new CountingTransactions();
		SubmitRecordedCommandService service = service(Optional.of(USER_ID), inserted, transactions);

		var result = service.submit(new SubmitRecordedCommandInput(
				new CommandType("POT_CREATE_V1"), "{\"label\":\"Trip\"}", principal()));

		assertEquals(COMMAND_ID, result.commandId());
		assertEquals(1, transactions.calls);
		assertEquals(1, inserted.size());
		RecordedCommand command = inserted.getFirst();
		assertEquals(COMMAND_ID, command.commandId());
		assertEquals(NOW, command.submittedAt());
		assertEquals(USER_ID, command.authorization().userId());
		assertEquals(NOW.plusSeconds(300), command.authorization().validUntil());
	}

	@Test
	void unknownIdentityCreatesNoCommand() {
		List<RecordedCommand> inserted = new ArrayList<>();
		assertThrows(UserNotProvisionedException.class,
				() -> service(Optional.empty(), inserted, new CountingTransactions()).submit(
						new SubmitRecordedCommandInput(new CommandType("TYPE"), "{}", principal())));
		assertTrue(inserted.isEmpty());
	}

	private static SubmitRecordedCommandService service(
			Optional<PocomaUserId> userId,
			List<RecordedCommand> inserted,
			TransactionRunner transactions) {
		return new SubmitRecordedCommandService(
				identity -> userId,
				new RecordedCommandPort() {
					@Override public void insert(RecordedCommand command) { inserted.add(command); }
					@Override public Optional<RecordedCommand> findById(CommandId commandId) { return Optional.empty(); }
				},
				() -> COMMAND_ID,
				new AuthorizationSnapshotFactory(new CommandAuthorizationTtl(Duration.ofMinutes(5)),
						new ExternalAuthorityPermissionTranslator()),
				Clock.fixed(NOW, ZoneOffset.UTC),
				transactions);
	}

	private static AuthenticatedExternalPrincipal principal() {
		return new AuthenticatedExternalPrincipal(
				"https://issuer.example", "subject", NOW.minusSeconds(120), NOW.minusSeconds(60),
				NOW.plusSeconds(600), Set.of("pocoma:pot:create"));
	}

	private static final class CountingTransactions implements TransactionRunner {
		private int calls;
		@Override public <T> T runInTransaction(Supplier<T> action) { calls++; return action.get(); }
		@Override public void runAfterCommit(Runnable action) { action.run(); }
	}
}
