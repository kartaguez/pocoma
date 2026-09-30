package com.kartaguez.pocoma.infra.persistence.jpa.adapter.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ConsumptionSlot;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.engine.command.decode.CommandDecoderRegistry;
import com.kartaguez.pocoma.engine.command.decode.CommandPayloadDecoder;
import com.kartaguez.pocoma.engine.command.dispatch.CommandDispatcher;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCase;
import com.kartaguez.pocoma.engine.command.dispatch.CommandUseCaseResult;
import com.kartaguez.pocoma.engine.command.execution.ExecuteRecordedCommandService;
import com.kartaguez.pocoma.engine.command.model.AuthorizationSnapshot;
import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.command.model.CommandAppliedResult;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandType;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;
import com.kartaguez.pocoma.engine.command.result.CommandResultProjectionDefinition;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.HandleConsumptionFailureInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JdbcCommandResultProjectionInputLoader;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.JpaCommandConsumptionDiscoveryRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.JpaRecordedCommandRepository;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.locator.consumption.command.CommandConsumptionExecution;
import com.kartaguez.pocoma.locator.consumption.command.CommandConsumptionKeys;
import com.kartaguez.pocoma.locator.consumption.command.CommandConsumptionLocator;
import com.kartaguez.pocoma.locator.consumption.command.failure.CommandConsumptionFailurePolicy;
import com.kartaguez.pocoma.locator.consumption.command.failure.CommandConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.SequentialConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
		"spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class CommandConsumptionPostgresTest {

	private static final Instant NOW = Instant.parse("2026-09-05T08:00:00Z");
	private static final CommandType TYPE = new CommandType("TEST_COMMAND_V1");
	private static final UUID APPLIED_POT_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JpaRecordedCommandAdapter commands;
	@Autowired private JpaCommandConsumptionDiscoveryAdapter discovery;
	@Autowired private JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired private JpaConsumptionProvenanceAdapter provenance;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbc;

	private Clock clock;
	private SpringTransactionRunner transactions;

	@BeforeEach
	void setUp() {
		jdbc.execute("create table if not exists lot65_command_effects (effect_id uuid primary key, owner text not null)");
		jdbc.update("delete from lot65_command_effects");
		jdbc.update("delete from consumption_inputs");
		jdbc.update("delete from consumption_results");
		jdbc.update("update consumption_slots set current_claim_id = null");
		jdbc.update("delete from consumption_claims");
		jdbc.update("delete from consumption_slots");
		jdbc.update("delete from command_terminal_events");
		jdbc.update("delete from command_outcomes");
		jdbc.update("delete from recorded_commands");
		clock = Clock.fixed(NOW, ZoneOffset.UTC);
		transactions = new SpringTransactionRunner(new TransactionTemplate(transactionManager));
	}

	@Test
	void discoversAcquiresReloadsAndTerminalizesASuccessfulCommand() {
		AtomicInteger calls = new AtomicInteger();
		RecordedCommand command = command("success", NOW.plusSeconds(60));
		insert(command);

		assertTrue(lifecycle.findSlot(CommandConsumptionKeys.forCommand(command.commandId())).isEmpty());
		run(commandValue -> {
			calls.incrementAndGet();
			return succeeded();
		});

		ConsumptionSlot slot = slot(command.commandId());
		assertEquals(1, calls.get());
		assertEquals(ConsumptionStatus.DONE, slot.status());
		assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
		assertTrue(slot.terminalReason().isEmpty());
		assertTrue(slot.currentClaimId().isEmpty());
		assertTerminalResolution(command.commandId(), "APPLIED", "COMMAND_APPLIED");
		assertEquals(APPLIED_POT_ID, jdbc.queryForObject(
				"select pot_id from command_outcomes where command_id = ?", UUID.class,
				command.commandId().value()));
		assertEquals(7L, jdbc.queryForObject(
				"select resulting_version from command_outcomes where command_id = ?", Long.class,
				command.commandId().value()));
		var input = new JdbcCommandResultProjectionInputLoader(
				new JdbcCommandOutcomeAdapter(jdbc), jdbc).load(new ProjectionKey(
						CommandResultProjectionDefinition.PROJECTION_TYPE,
						CommandResultProjectionDefinition.TARGET_OBJECT_TYPE,
						new TargetObjectId(command.commandId().value().toString()), 1));
		assertEquals(command.authorization().userId().value(), input.submittedByUserId());
	}

	@Test
	void expiredAuthorizationIsARejectedTerminalResult() {
		RecordedCommand command = command("expired", NOW);
		insert(command);

		run(value -> { throw new AssertionError("expired Command must not be dispatched"); });

		ConsumptionSlot slot = slot(command.commandId());
		assertEquals(TerminalOutcome.REJECTED, slot.terminalOutcome().orElseThrow());
		assertEquals(new TerminalReason("AUTHORIZATION_EXPIRED"), slot.terminalReason().orElseThrow());
		assertTerminalResolution(command.commandId(), "REJECTED", "COMMAND_REJECTED");
	}

	@Test
	void invalidDurablePayloadFailsTerminally() {
		RecordedCommand command = command("invalid", NOW.plusSeconds(60));
		insert(command);

		run(value -> succeeded());

		ConsumptionSlot slot = slot(command.commandId());
		assertEquals(TerminalOutcome.FAILED, slot.terminalOutcome().orElseThrow());
		assertEquals(new TerminalReason("INVALID_COMMAND_PAYLOAD"), slot.terminalReason().orElseThrow());
		assertTrue(slot.currentClaimId().isEmpty());
		assertTerminalResolution(command.commandId(), "FAILED", "COMMAND_FAILED");
		assertEquals("COMMAND_PROCESSING_FAILED", jdbc.queryForObject(
				"select public_code from command_outcomes where command_id = ?", String.class,
				command.commandId().value()));
	}

	@Test
	void unsupportedCommandTypeFailsTerminally() {
		RecordedCommand command = command(new CommandType("UNKNOWN_COMMAND_V1"), "payload", NOW.plusSeconds(60));
		insert(command);

		run(value -> succeeded());

		ConsumptionSlot slot = slot(command.commandId());
		assertEquals(TerminalOutcome.FAILED, slot.terminalOutcome().orElseThrow());
		assertEquals(new TerminalReason("UNSUPPORTED_COMMAND_TYPE"), slot.terminalReason().orElseThrow());
		assertTerminalResolution(command.commandId(), "FAILED", "COMMAND_FAILED");
	}

	@Test
	void businessRejectionIsTerminalWithoutBeingClassifiedAsFailure() {
		RecordedCommand command = command("rejected", NOW.plusSeconds(60));
		insert(command);

		run(value -> new CommandUseCaseResult.Rejected(
				new TerminalReason("INSUFFICIENT_PERMISSION"), List.of()));

		ConsumptionSlot slot = slot(command.commandId());
		assertEquals(TerminalOutcome.REJECTED, slot.terminalOutcome().orElseThrow());
		assertEquals(new TerminalReason("INSUFFICIENT_PERMISSION"), slot.terminalReason().orElseThrow());
		assertEquals(1, lifecycle.findClaims(slot.slotId()).size());
		assertTerminalResolution(command.commandId(), "REJECTED", "COMMAND_REJECTED");
		assertEquals("INSUFFICIENT_PERMISSION", jdbc.queryForObject(
				"select public_code from command_outcomes where command_id = ?", String.class,
				command.commandId().value()));
	}

	@Test
	void unexpectedRuntimeFailsImmediatelyWithoutRetry() {
		RecordedCommand command = command("runtime", NOW.plusSeconds(60));
		insert(command);

		run(value -> {
			jdbc.update("insert into lot65_command_effects(effect_id, owner) values (?, ?)",
					UUID.randomUUID(), "rolled-back");
			throw new NullPointerException("programming bug");
		});

		ConsumptionSlot slot = slot(command.commandId());
		assertEquals(TerminalOutcome.FAILED, slot.terminalOutcome().orElseThrow());
		assertEquals(new TerminalReason("COMMAND_EXECUTION_FAILURE"), slot.terminalReason().orElseThrow());
		assertTrue(slot.currentClaimId().isEmpty());
		assertEquals(1, lifecycle.findClaims(slot.slotId()).size());
		assertEquals(0, jdbc.queryForObject("select count(*) from lot65_command_effects", Integer.class));
		assertTerminalResolution(command.commandId(), "FAILED", "COMMAND_FAILED");
	}

	@Test
	void recognizedTransientSqlFailureSchedulesTheGenericRetry() {
		RecordedCommand command = command("transient", NOW.plusSeconds(60));
		insert(command);

		run(value -> { throw new RuntimeException(new SQLException("deadlock", "40P01")); });

		ConsumptionSlot slot = slot(command.commandId());
		assertEquals(ConsumptionStatus.PENDING, slot.status());
		assertTrue(slot.terminalOutcome().isEmpty());
		assertTrue(slot.terminalReason().isEmpty());
		assertEquals(NOW.plusSeconds(1), slot.nextClaimAt());
		assertTrue(slot.currentClaimId().isEmpty());
		assertEquals(0, terminalResolutionCount(command.commandId()));
		assertEquals(0, jdbc.queryForObject("select count(*) from projection_tasks "
				+ "where target_object_id = ?", Integer.class, command.commandId().value().toString()));
	}

	@Test
	void retryableOlderCommandDoesNotBlockLaterEligibleCommandForTheSamePot() {
		UUID samePotId = UUID.randomUUID();
		RecordedCommand first = command(TYPE, "retry:" + samePotId, NOW.plusSeconds(60), NOW);
		RecordedCommand second = command(TYPE, "succeed:" + samePotId, NOW.plusSeconds(60), NOW.plusMillis(1));
		insert(first);
		insert(second);
		List<String> attempts = new java.util.concurrent.CopyOnWriteArrayList<>();

		Function<TestCommand, CommandUseCaseResult> behavior = value -> {
			attempts.add(value.value());
			if (value.value().startsWith("retry:")) {
				throw new RuntimeException(new SQLException("serialization", "40001"));
			}
			return succeeded();
		};
		run(behavior);
		run(behavior);

		assertEquals(List.of("retry:" + samePotId, "succeed:" + samePotId), attempts);
		assertEquals(ConsumptionStatus.PENDING, slot(first.commandId()).status());
		assertEquals(NOW.plusSeconds(1), slot(first.commandId()).nextClaimAt());
		assertEquals(0, terminalResolutionCount(first.commandId()));
		assertEquals(TerminalOutcome.SUCCESS, slot(second.commandId()).terminalOutcome().orElseThrow());
		assertTerminalResolution(second.commandId(), "APPLIED", "COMMAND_APPLIED");
	}

	@Test
	void retryableFailureCanBeRetriedAndThenSucceed() {
		MutableClock mutableClock = new MutableClock(NOW);
		clock = mutableClock;
		RecordedCommand command = command("retry-then-success", NOW.plusSeconds(60));
		insert(command);
		AtomicInteger attempts = new AtomicInteger();
		Function<TestCommand, CommandUseCaseResult> behavior = value -> {
			if (attempts.incrementAndGet() == 1) {
				throw new RuntimeException(new SQLException("serialization", "40001"));
			}
			return succeeded();
		};

		run(behavior);
		assertEquals(ConsumptionStatus.PENDING, slot(command.commandId()).status());
		assertEquals(0, terminalResolutionCount(command.commandId()));
		mutableClock.set(NOW.plusSeconds(1));
		run(behavior);

		ConsumptionSlot slot = slot(command.commandId());
		assertEquals(2, attempts.get());
		assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
		assertEquals(2, lifecycle.findClaims(slot.slotId()).size());
		assertTerminalResolution(command.commandId(), "APPLIED", "COMMAND_APPLIED");
	}

	@Test
	void takeoverRollsBackTheLosingCommandAndOnlyTheWinnerCommits() throws Exception {
		MutableClock mutableClock = new MutableClock(NOW);
		clock = mutableClock;
		RecordedCommand command = command("takeover", NOW.plusSeconds(60));
		insert(command);
		var acquire = new TransactionalAcquireConsumptionUseCase(
				new AcquireConsumptionService(lifecycle, clock), transactions);
		var execute = new TransactionalExecuteConsumptionUseCase(
				new ExecuteConsumptionService(lifecycle, provenance, clock), transactions);
		var key = CommandConsumptionKeys.forCommand(command.commandId());
		var stale = assertInstanceOf(AcquireResult.Acquired.class, acquire.acquire(
				new AcquireConsumptionInput(key, new WorkerId("worker-a"), new ClaimLease(Duration.ofSeconds(30))))).claim();
		CountDownLatch effectWritten = new CountDownLatch(1);
		CountDownLatch allowStaleFinish = new CountDownLatch(1);
		AtomicInteger executions = new AtomicInteger();
		var specialized = commandExecution(value -> {
			int execution = executions.incrementAndGet();
			jdbc.update("insert into lot65_command_effects(effect_id, owner) values (?, ?)",
					UUID.randomUUID(), execution == 1 ? "worker-a" : "worker-b");
			if (execution == 1) {
				effectWritten.countDown();
				await(allowStaleFinish);
			}
			return succeeded();
		});

		try (var executor = Executors.newSingleThreadExecutor()) {
			var staleExecution = executor.submit(() -> execute.execute(new ExecuteConsumptionInput(
					stale.slotId(), stale.claimId(), specialized.forCommand(command.commandId()))));
			assertTrue(effectWritten.await(10, TimeUnit.SECONDS));

			mutableClock.set(NOW.plusSeconds(30));
			var winner = assertInstanceOf(AcquireResult.Acquired.class, acquire.acquire(
					new AcquireConsumptionInput(key, new WorkerId("worker-b"),
							new ClaimLease(Duration.ofSeconds(30))))).claim();
			allowStaleFinish.countDown();
			ExecutionException staleFailure = assertThrows(
					ExecutionException.class, () -> staleExecution.get(10, TimeUnit.SECONDS));
			assertInstanceOf(LostClaimException.class, staleFailure.getCause());
			assertEquals(0, jdbc.queryForObject("select count(*) from lot65_command_effects", Integer.class));
			assertEquals(0, terminalResolutionCount(command.commandId()));

			execute.execute(new ExecuteConsumptionInput(
					winner.slotId(), winner.claimId(), specialized.forCommand(command.commandId())));
		}

		assertEquals(1, jdbc.queryForObject("select count(*) from lot65_command_effects", Integer.class));
		assertEquals("worker-b", jdbc.queryForObject("select owner from lot65_command_effects", String.class));
		assertEquals(TerminalOutcome.SUCCESS, slot(command.commandId()).terminalOutcome().orElseThrow());
		assertEquals(2, lifecycle.findClaims(stale.slotId()).size());
		assertTerminalResolution(command.commandId(), "APPLIED", "COMMAND_APPLIED");
	}

	@Test
	void staleClaimCannotPublishATerminalFailureOutcomeOrEvent() {
		MutableClock mutableClock = new MutableClock(NOW);
		clock = mutableClock;
		RecordedCommand command = command("stale-terminal-failure", NOW.plusSeconds(60));
		insert(command);
		var acquire = new TransactionalAcquireConsumptionUseCase(
				new AcquireConsumptionService(lifecycle, clock), transactions);
		var failure = new TransactionalHandleConsumptionFailureUseCase(
				new HandleConsumptionFailureService(
						lifecycle, lifecycle, new CommandConsumptionFailurePolicy(), clock), transactions);
		var key = CommandConsumptionKeys.forCommand(command.commandId());
		var stale = assertInstanceOf(AcquireResult.Acquired.class, acquire.acquire(
				new AcquireConsumptionInput(key, new WorkerId("worker-a"),
						new ClaimLease(Duration.ofSeconds(30))))).claim();
		mutableClock.set(NOW.plusSeconds(30));
		var winner = assertInstanceOf(AcquireResult.Acquired.class, acquire.acquire(
				new AcquireConsumptionInput(key, new WorkerId("worker-b"),
						new ClaimLease(Duration.ofSeconds(30))))).claim();
		var processingFailure = new CommandConsumptionTechnicalFailureClassifier(clock)
				.classify(new NullPointerException("terminal failure"));
		var terminalEffect = commandExecution(value -> succeeded()).terminalFailureEffect(command.commandId());

		assertEquals(FencedMutationResult.LOST_CLAIM, failure.handle(new HandleConsumptionFailureInput(
				stale.slotId(), stale.claimId(), processingFailure, terminalEffect)));
		assertEquals(0, terminalResolutionCount(command.commandId()));

		assertEquals(FencedMutationResult.APPLIED, failure.handle(new HandleConsumptionFailureInput(
				winner.slotId(), winner.claimId(), processingFailure, terminalEffect)));
		assertEquals(TerminalOutcome.FAILED, slot(command.commandId()).terminalOutcome().orElseThrow());
		assertTerminalResolution(command.commandId(), "FAILED", "COMMAND_FAILED");
	}

	private void assertTerminalResolution(CommandId commandId, String outcomeType, String eventType) {
		assertEquals(1, terminalResolutionCount(commandId));
		assertEquals(outcomeType, jdbc.queryForObject(
				"select outcome_type from command_outcomes where command_id = ?", String.class,
				commandId.value()));
		assertEquals(eventType, jdbc.queryForObject(
				"select event_type from command_terminal_events where command_id = ?", String.class,
				commandId.value()));
	}

	private int terminalResolutionCount(CommandId commandId) {
		int outcomes = jdbc.queryForObject(
				"select count(*) from command_outcomes where command_id = ?", Integer.class,
				commandId.value());
		int events = jdbc.queryForObject(
				"select count(*) from command_terminal_events where command_id = ?", Integer.class,
				commandId.value());
		assertEquals(outcomes, events);
		return outcomes;
	}

	private void run(Function<TestCommand, CommandUseCaseResult> behavior) {
		var commandExecution = commandExecution(behavior);
		var locator = new CommandConsumptionLocator(
				discovery,
				commandExecution,
				new CommandConsumptionTechnicalFailureClassifier(clock),
				clock);
		var acquire = new TransactionalAcquireConsumptionUseCase(
				new AcquireConsumptionService(lifecycle, clock), transactions);
		var execute = new TransactionalExecuteConsumptionUseCase(
				new ExecuteConsumptionService(lifecycle, provenance, clock), transactions);
		var failure = new TransactionalHandleConsumptionFailureUseCase(
				new HandleConsumptionFailureService(
						lifecycle, lifecycle, new CommandConsumptionFailurePolicy(), clock), transactions);
		new SequentialConsumptionOrchestrator(locator, acquire, execute, failure).run(
				new ConsumptionOrchestrationInput(
						new WorkerId("command-test"),
						new ClaimLease(Duration.ofSeconds(30)),
						new ConsumptionOrchestrationBudget(10, 1)));
	}

	private CommandConsumptionExecution commandExecution(Function<TestCommand, CommandUseCaseResult> behavior) {
		var decoder = new CommandDecoderRegistry(List.of(new TestDecoder()));
		var dispatcher = new CommandDispatcher(List.of(new TestUseCase(behavior)));
		return new CommandConsumptionExecution(new ExecuteRecordedCommandService(
				commands, decoder, dispatcher, events -> List.of(), clock),
				new JdbcCommandOutcomeAdapter(jdbc), clock);
	}

	private static CommandUseCaseResult.Succeeded succeeded() {
		return new CommandUseCaseResult.Succeeded(
				List.of(), new CommandAppliedResult(APPLIED_POT_ID, 7), List.of());
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("timed out");
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}

	private void insert(RecordedCommand command) {
		transactions.runInTransaction(() -> commands.insert(command));
	}

	private ConsumptionSlot slot(CommandId commandId) {
		return lifecycle.findSlot(CommandConsumptionKeys.forCommand(commandId)).orElseThrow();
	}

	private static RecordedCommand command(String payload, Instant validUntil) {
		return command(TYPE, payload, validUntil);
	}

	private static RecordedCommand command(CommandType type, String payload, Instant validUntil) {
		return command(type, payload, validUntil, NOW);
	}

	private static RecordedCommand command(CommandType type, String payload, Instant validUntil,
			Instant submittedAt) {
		Instant issuedAt = NOW.minusSeconds(60);
		return new RecordedCommand(
				new CommandId(UUID.randomUUID()), type, payload, submittedAt,
				new AuthorizationSnapshot(
						new PocomaUserId(UUID.randomUUID()),
						Set.of(new Permission("POT", "CREATE")),
						issuedAt, issuedAt, validUntil, "test-issuer"));
	}

	private record TestCommand(String value) implements Command {
	}

	private static final class TestDecoder implements CommandPayloadDecoder<TestCommand> {
		@Override public CommandType commandType() { return TYPE; }
		@Override public Class<TestCommand> commandClass() { return TestCommand.class; }
		@Override public TestCommand decode(String serializedPayload) {
			if (serializedPayload.equals("invalid")) throw new IllegalArgumentException("invalid payload");
			return new TestCommand(serializedPayload);
		}
	}

	private record TestUseCase(Function<TestCommand, CommandUseCaseResult> behavior)
			implements CommandUseCase<TestCommand> {
		@Override public Class<TestCommand> commandClass() { return TestCommand.class; }
		@Override public CommandUseCaseResult execute(AuthorizationSnapshot authorization, TestCommand command) {
			return behavior.apply(command);
		}
	}

	private static final class MutableClock extends Clock {
		private Instant instant;
		private MutableClock(Instant instant) { this.instant = instant; }
		void set(Instant instant) { this.instant = instant; }
		@Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(java.time.ZoneId zone) { return this; }
		@Override public Instant instant() { return instant; }
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan("com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories("com.kartaguez.pocoma.infra.persistence.jpa.repository")
	@Import({JpaRecordedCommandAdapter.class, JpaCommandConsumptionDiscoveryAdapter.class,
			JpaRecordedCommandRepository.class, JpaCommandConsumptionDiscoveryRepository.class,
			JpaConsumptionLifecycleAdapter.class, JpaConsumptionProvenanceAdapter.class})
	static class TestApplication {
		@Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
	}
}
