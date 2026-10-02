package com.kartaguez.pocoma.orchestrator.command.admission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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

import com.kartaguez.pocoma.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.model.TargetCommandEnvelope;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.orchestrator.command.admission.model.CommandAuthorizationTtl;
import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmitRecordedCommandInput;

class SubmitRecordedCommandServiceTest {
	private static final Instant NOW = Instant.parse("2026-09-05T12:00:00Z");
	private static final CommandId COMMAND_ID = new CommandId(UUID.randomUUID());
	private static final BindingId BINDING_ID = new BindingId(UUID.randomUUID());

	@Test
	void recordsTargetEnvelopeFromPrincipalAndClientBindingInsideOneTransaction() {
		List<RecordedCommand> inserted = new ArrayList<>();
		CountingTransactions transactions = new CountingTransactions();
		SubmitRecordedCommandService service = service(inserted, transactions);

		var result = service.submit(new SubmitRecordedCommandInput(
				new CommandType("POT_CREATE_V1"), BINDING_ID, "{\"label\":\"Trip\"}", principal()));

		assertEquals(COMMAND_ID, result.commandId());
		assertEquals(1, transactions.calls);
		assertEquals(1, inserted.size());
		RecordedCommand command = inserted.getFirst();
		assertEquals(COMMAND_ID, command.commandId());
		assertEquals(NOW, command.submittedAt());
		TargetCommandEnvelope envelope = assertInstanceOf(TargetCommandEnvelope.class, command.envelope());
		assertEquals(new ExternalIdentity("https://issuer.example", "subject"), envelope.externalIdentity());
		assertEquals(BINDING_ID, envelope.bindingId());
		assertEquals(Set.of("pocoma:pot:create", "provider:untranslated"),
				envelope.authenticationEvidence().externalAuthorities());
		assertEquals(NOW.plusSeconds(300), envelope.authenticationEvidence().validUntil());
	}

	private static SubmitRecordedCommandService service(
			List<RecordedCommand> inserted,
			TransactionRunner transactions) {
		return new SubmitRecordedCommandService(
				new RecordedCommandPort() {
					@Override public void insert(RecordedCommand command) { inserted.add(command); }
					@Override public Optional<RecordedCommand> findById(CommandId commandId) { return Optional.empty(); }
				},
				() -> COMMAND_ID,
				new CommandAuthenticationEvidenceFactory(
						new CommandAuthorizationTtl(Duration.ofMinutes(5))),
				Clock.fixed(NOW, ZoneOffset.UTC),
				transactions);
	}

	private static AuthenticatedExternalPrincipal principal() {
		return new AuthenticatedExternalPrincipal(
				"https://issuer.example", "subject", NOW.minusSeconds(120), NOW.minusSeconds(60),
				NOW.plusSeconds(600), Set.of("pocoma:pot:create", "provider:untranslated"));
	}

	private static final class CountingTransactions implements TransactionRunner {
		private int calls;
		@Override public <T> T runInTransaction(Supplier<T> action) { calls++; return action.get(); }
		@Override public void runAfterCommit(Runnable action) { action.run(); }
	}
}
