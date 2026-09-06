package com.kartaguez.pocoma.runtime.command.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication;
import com.kartaguez.pocoma.domain.authorization.PocomaPermissions;
import com.kartaguez.pocoma.domain.consumption.claim.ConsumptionSlot;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.port.out.EventAppendPort;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.engine.pot.command.decode.PotCommandTypes;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaPotCommandEventAppendAdapter;
import com.kartaguez.pocoma.locator.consumption.command.CommandConsumptionKeys;

@SpringBootTest(classes = PocomaCommandConsumptionWorkerApplication.class, properties = {
		"pocoma.command-consumption.enabled=true",
		"pocoma.command-consumption.worker-id=command-runtime-test",
		"pocoma.command-consumption.poll-interval=20ms",
		"pocoma.command-consumption.runtime-failure-backoff=20ms",
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true"
})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(CommandConsumptionRuntimePostgresTest.TransientEventAppendConfiguration.class)
class CommandConsumptionRuntimePostgresTest {

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired RecordedCommandPort commands;
	@Autowired TransactionRunner transactions;
	@Autowired JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired JdbcTemplate jdbc;

	@Test
	void retriesARecognizedTransientFailureThenConsumesThroughTheRealRuntimeLoop() {
		UUID userId = UUID.randomUUID();
		String label = "runtime-success-" + UUID.randomUUID();
		int eventsBefore = jdbc.queryForObject("select count(*) from business_event_outbox", Integer.class);
		RecordedCommand command = command(PotCommandTypes.POT_CREATE_V1,
				"{\"label\":\"%s\",\"creatorId\":\"%s\"}".formatted(label, userId),
				userId, Set.of(PocomaPermissions.POT_CREATE), Instant.now().plusSeconds(60));

		transactions.runInTransaction(() -> commands.insert(command));
		ConsumptionSlot slot = awaitTerminal(command.commandId());

		assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
		assertTrue(slot.terminalReason().isEmpty());
		assertTrue(slot.currentClaimId().isEmpty());
		assertEquals(1, jdbc.queryForObject("select count(*) from pot_headers where label = ?", Integer.class, label));
		assertEquals(1, jdbc.queryForObject(
				"select count(*) from consumption_results where slot_id = ?", Integer.class, slot.slotId()));
		assertEquals(2, lifecycle.findClaims(slot.slotId()).size());
		assertEquals(eventsBefore + 1,
				jdbc.queryForObject("select count(*) from business_event_outbox", Integer.class));
	}

	@Test
	void terminalizesBusinessRejectionWithoutMutationOrEvent() {
		UUID userId = UUID.randomUUID();
		String label = "runtime-rejected-" + UUID.randomUUID();
		int eventsBefore = jdbc.queryForObject("select count(*) from business_event_outbox", Integer.class);
		RecordedCommand command = command(PotCommandTypes.POT_CREATE_V1,
				"{\"label\":\"%s\",\"creatorId\":\"%s\"}".formatted(label, userId),
				userId, Set.of(), Instant.now().plusSeconds(60));

		transactions.runInTransaction(() -> commands.insert(command));
		ConsumptionSlot slot = awaitTerminal(command.commandId());

		assertEquals(TerminalOutcome.REJECTED, slot.terminalOutcome().orElseThrow());
		assertEquals(new TerminalReason("MISSING_PERMISSION"), slot.terminalReason().orElseThrow());
		assertEquals(0, jdbc.queryForObject("select count(*) from pot_headers where label = ?", Integer.class, label));
		assertEquals(eventsBefore, jdbc.queryForObject("select count(*) from business_event_outbox", Integer.class));
	}

	@Test
	void terminalizesInvalidPayloadAsFailed() {
		RecordedCommand command = command(PotCommandTypes.POT_CREATE_V1, "not-json", UUID.randomUUID(),
				Set.of(PocomaPermissions.POT_CREATE), Instant.now().plusSeconds(60));

		transactions.runInTransaction(() -> commands.insert(command));
		ConsumptionSlot slot = awaitTerminal(command.commandId());

		assertEquals(TerminalOutcome.FAILED, slot.terminalOutcome().orElseThrow());
		assertEquals(new TerminalReason("INVALID_COMMAND_PAYLOAD"), slot.terminalReason().orElseThrow());
		assertTrue(slot.currentClaimId().isEmpty());
	}

	private ConsumptionSlot awaitTerminal(CommandId commandId) {
		await(() -> lifecycle.findSlot(CommandConsumptionKeys.forCommand(commandId))
				.map(slot -> slot.terminalOutcome().isPresent()).orElse(false));
		return lifecycle.findSlot(CommandConsumptionKeys.forCommand(commandId)).orElseThrow();
	}

	private static RecordedCommand command(CommandType type, String payload, UUID userId,
			Set<com.kartaguez.pocoma.domain.authorization.Permission> permissions, Instant validUntil) {
		Instant issuedAt = Instant.now().minusSeconds(30);
		return new RecordedCommand(new CommandId(UUID.randomUUID()), type, payload, Instant.now(),
				new AuthorizationSnapshot(new PocomaUserId(userId), permissions, issuedAt, issuedAt,
						validUntil, "runtime-test"));
	}

	private static void await(BooleanSupplier condition) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("test interrupted", exception);
			}
		}
		assertTrue(condition.getAsBoolean(), "Command was not terminalized before timeout");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TransientEventAppendConfiguration {
		@Bean @Primary
		EventAppendPort transientEventAppend(JpaPotCommandEventAppendAdapter delegate) {
			AtomicBoolean first = new AtomicBoolean(true);
			return events -> {
				if (first.compareAndSet(true, false)) {
					throw new RuntimeException(new SQLException("simulated deadlock", "40P01"));
				}
				return delegate.appendAll(events);
			};
		}
	}
}
