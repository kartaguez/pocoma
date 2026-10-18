package com.kartaguez.pocoma.runtime.command.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
import com.kartaguez.pocoma.contracts.command.CommandId;
import com.kartaguez.pocoma.contracts.command.CommandType;
import com.kartaguez.pocoma.contracts.command.TargetCommandEnvelope;
import com.kartaguez.pocoma.contracts.command.CommandAuthenticationEvidence;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.contracts.command.RecordedCommand;
import com.kartaguez.pocoma.engine.consume.command.port.out.EventAppendPort;
import com.kartaguez.pocoma.engine.admit.command.port.out.RecordedCommandInsertionPort;
import com.kartaguez.pocoma.engine.consume.command.pot.decode.PotCommandTypes;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.JpaPotCommandEventAppendAdapter;
import com.kartaguez.pocoma.supra.consume.command.CommandConsumptionKeys;
import com.kartaguez.pocoma.domain.authorization.Permission;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.engine.exception.consumption.ConsumptionTransactionResolutionException;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalFinalizeConsumptionUseCase;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.runtime.consumption.ConsumptionTerminalParticipant;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
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

	@Autowired RecordedCommandInsertionPort commands;
	@Autowired @Qualifier("commandConsumptionTransactionRunner") TransactionRunner transactions;
	@Autowired JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired JdbcTemplate jdbc;

	@Test
	@Order(1)
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
		assertEquals("POT_CREATED", jdbc.queryForObject(
				"select event_type from business_event_outbox where pot_id = "
						+ "(select pot_id from pot_headers where label = ?)",
				String.class, label));
		assertEquals("POT_CREATED", jdbc.queryForObject(
				"select payload_json::jsonb ->> 'eventType' from business_event_outbox where pot_id = "
						+ "(select pot_id from pot_headers where label = ?)",
				String.class, label));
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
	void oldObservationSurvivesIndependentMutationAndObsoleteCommandIsRejectedOnce() {
		UUID user = UUID.randomUUID();
		String label = "obsolescence-" + UUID.randomUUID();
		awaitSuccessful(command(PotCommandTypes.POT_CREATE_V1,
				"{\"label\":\"%s\",\"creatorId\":\"%s\"}".formatted(label, user),
				user, Set.of(PocomaPermissions.POT_CREATE), Instant.now().plusSeconds(60)));
		UUID pot = jdbc.queryForObject("select pot_id from pot_headers where label=?", UUID.class, label);
		awaitSuccessful(command(PotCommandTypes.POT_SHAREHOLDERS_ADD_V1,
				"{\"potId\":\"%s\",\"shareholders\":[{\"name\":\"Member\",\"weightNumerator\":1,\"weightDenominator\":1}],\"observedVersion\":1}".formatted(pot),
				user, Set.of(PocomaPermissions.SHAREHOLDER_CREATE), Instant.now().plusSeconds(60)));
		String payload = "{\"potId\":\"%s\",\"label\":\"Changed\",\"observedVersion\":1}".formatted(pot);
		var compatible = command(PotCommandTypes.POT_DETAILS_UPDATE_V1, payload,
				user, Set.of(PocomaPermissions.POT_UPDATE), Instant.now().plusSeconds(60));
		awaitSuccessful(compatible); // O=1, V=2, header still starts at 1.
		assertEquals(payload, jdbc.queryForObject("select payload_json from recorded_commands where command_id=?", String.class, compatible.commandId().value()));
		var obsolete = command(PotCommandTypes.POT_DETAILS_UPDATE_V1, payload,
				user, Set.of(PocomaPermissions.POT_UPDATE), Instant.now().plusSeconds(60));
		transactions.runInTransaction(() -> commands.insert(obsolete));
		var slot = awaitTerminal(obsolete.commandId());
		assertEquals(TerminalOutcome.REJECTED, slot.terminalOutcome().orElseThrow());
		assertEquals(new TerminalReason("OBSOLETE_COMMAND"), slot.terminalReason().orElseThrow());
		assertEquals(1, lifecycle.findClaims(slot.slotId()).size());
		assertTrue(slot.currentClaimId().isEmpty());
		assertEquals(3L, jdbc.queryForObject("select version from pot_global_versions where pot_id=?", Long.class, pot));
		assertEquals(3, jdbc.queryForObject("select count(*) from business_event_outbox where pot_id=?", Integer.class, pot));
		assertEquals(1, jdbc.queryForObject("select count(*) from business_event_outbox where pot_id=? and version=3", Integer.class, pot));
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

	@Test
	void createAndUpdateExpenseCommandsPersistTheBusinessDateAtTheirExactHistoricalVersions() {
		UUID userId = UUID.randomUUID();
		String potLabel = "expense-date-history-" + UUID.randomUUID();
		awaitSuccessful(command(PotCommandTypes.POT_CREATE_V1,
				"{\"label\":\"%s\",\"creatorId\":\"%s\"}".formatted(potLabel, userId),
				userId, Set.of(PocomaPermissions.POT_CREATE), Instant.now().plusSeconds(60)));
		UUID potId = jdbc.queryForObject("select pot_id from pot_headers where label = ?", UUID.class, potLabel);

		awaitSuccessful(command(PotCommandTypes.POT_SHAREHOLDERS_ADD_V1,
				("{\"potId\":\"%s\",\"shareholders\":[{\"name\":\"Payer\",\"weightNumerator\":1,"
						+ "\"weightDenominator\":1}],\"observedVersion\":1}").formatted(potId),
				userId, Set.of(PocomaPermissions.SHAREHOLDER_CREATE), Instant.now().plusSeconds(60)));
		UUID shareholderId = jdbc.queryForObject(
				"select shareholder_id from shareholders where pot_id = ? and started_at_version = 2",
				UUID.class, potId);

		LocalDate firstDate = LocalDate.parse("2026-01-01");
		awaitSuccessful(command(PotCommandTypes.EXPENSE_CREATE_V1,
				("{\"potId\":\"%s\",\"payerId\":\"%s\",\"amountNumerator\":10,"
						+ "\"amountDenominator\":1,\"label\":\"Dinner\",\"date\":\"%s\","
						+ "\"shares\":[{\"shareholderId\":\"%s\",\"weightNumerator\":1,"
						+ "\"weightDenominator\":1}],\"observedVersion\":2}")
						.formatted(potId, shareholderId, firstDate, shareholderId),
				userId, Set.of(PocomaPermissions.EXPENSE_CREATE), Instant.now().plusSeconds(60)));
		UUID expenseId = jdbc.queryForObject(
				"select expense_id from expense_headers where pot_id = ? and started_at_version = 3",
				UUID.class, potId);

		LocalDate secondDate = LocalDate.parse("2026-02-02");
		awaitSuccessful(command(PotCommandTypes.EXPENSE_DETAILS_UPDATE_V1,
				("{\"expenseId\":\"%s\",\"payerId\":\"%s\",\"amountNumerator\":20,"
						+ "\"amountDenominator\":1,\"label\":\"Updated dinner\",\"date\":\"%s\","
						+ "\"observedVersion\":3}").formatted(expenseId, shareholderId, secondDate),
				userId, Set.of(PocomaPermissions.EXPENSE_UPDATE), Instant.now().plusSeconds(60)));

		assertEquals(firstDate, expenseDate(expenseId, 3));
		assertEquals(4L, jdbc.queryForObject(
				"select ended_at_version from expense_headers where expense_id = ? and started_at_version = 3",
				Long.class, expenseId));
		assertEquals(secondDate, expenseDate(expenseId, 4));
	}

	@Autowired PlatformTransactionManager transactionManager;
	@Autowired @Qualifier("commandConsumptionAcquire") AcquireConsumptionUseCase publicAcquire;
	@Autowired @Qualifier("commandConsumptionExecute") ExecuteConsumptionUseCase publicExecute;
	@Autowired @Qualifier("commandConsumptionHandleFailure") HandleConsumptionFailureUseCase publicFailure;
	@Autowired @Qualifier("commandConsumptionOrchestrator") ConsumptionOrchestrator publicOrchestrator;
	@Autowired @Qualifier("commandConsumptionTerminalParticipant") ConsumptionTerminalParticipant terminalParticipant;

	@Test
	void publicOwnersRejectAmbientTransactionsBeforeTheirDelegates() {
		new TransactionTemplate(transactionManager).execute(status -> {
			Assertions.assertThrows(IllegalStateException.class, () -> publicAcquire.acquire(null));
			Assertions.assertThrows(IllegalStateException.class, () -> publicExecute.execute(null));
			Assertions.assertThrows(IllegalStateException.class, () -> publicFailure.handle(null));
			Assertions.assertThrows(IllegalStateException.class, () -> publicOrchestrator.run(null));
			var publicFinalize = new TransactionalFinalizeConsumptionUseCase(terminalParticipant.finalizer(), transactions);
			Assertions.assertThrows(IllegalStateException.class, () -> publicFinalize.finalizeConsumption(null));
			assertTrue(!status.isRollbackOnly());
			return null;
		});
	}

	@Test
	void requiredParticipantSharesXidAndLossEscapesUntilPhysicalRollback() {
		jdbc.execute("create table if not exists wp2_owner_effects(id uuid primary key, xid bigint not null)");
		var key = new ConsumptionKey(
			new ConsumableIdentity("WP2_TEST", List.of(UUID.randomUUID().toString())),
			new ConsumerIdentity("WP2_TEST", List.of("owner")));
		Instant now = Instant.now();
		var old = Assertions.assertInstanceOf(AcquireResult.Acquired.class,
			transactions.runInTransaction(() -> lifecycle.acquire(key, new ClaimId(UUID.randomUUID()),
				new WorkerId("old"), new ClaimLease(Duration.ofSeconds(30)), now))).claim();
		var winner = Assertions.assertInstanceOf(AcquireResult.Acquired.class,
			transactions.runInTransaction(() -> lifecycle.acquire(key, new ClaimId(UUID.randomUUID()),
				new WorkerId("winner"), new ClaimLease(Duration.ofSeconds(30)), now.plusSeconds(30)))).claim();
		UUID effect = UUID.randomUUID();
		var participant = new ExecuteConsumptionService(terminalParticipant.finalizer());
		Assertions.assertThrows(LostClaimException.class,
			() -> transactions.runInTransaction(() -> {
				long xid = jdbc.queryForObject("select txid_current()", Long.class);
				return participant.execute(new ExecuteConsumptionInput(old.slotId(), old.claimId(), context -> {
					new TransactionTemplate(transactionManager).execute(status -> {
						assertEquals(xid, jdbc.queryForObject("select txid_current()", Long.class));
						jdbc.update("insert into wp2_owner_effects values (?,?)", effect, xid);
						return null;
					});
					return new ConsumptionExecutionResult(
						new BusinessConsumptionOutcome.Success(), List.of(), List.of());
				}));
			}));
		assertEquals(0, jdbc.queryForObject("select count(*) from wp2_owner_effects where id=?", Integer.class, effect));
		assertEquals(winner.claimId(), lifecycle.findSlot(old.slotId()).orElseThrow().currentClaimId().orElseThrow());
	}

	@Test
	void postCommitObservationFailureIsReportedAsCommittedAndCannotBecomeBusinessFailure() {
		jdbc.execute("create table if not exists wp2_resolution_effects(id uuid primary key)");
		UUID id = UUID.randomUUID();
		var failure = Assertions.assertThrows(
			ConsumptionTransactionResolutionException.class,
			() -> transactions.runInTransaction(() -> {
				jdbc.update("insert into wp2_resolution_effects values (?)", id);
				TransactionSynchronizationManager.registerSynchronization(
					new TransactionSynchronization() {
						@Override public void afterCommit() { throw new IllegalStateException("observer failed after commit"); }
					});
			}));
		assertEquals(ConsumptionTransactionResolutionException.Outcome.COMMITTED, failure.outcome());
		assertEquals(1, jdbc.queryForObject("select count(*) from wp2_resolution_effects where id=?", Integer.class, id));
	}

	@Test
	void ownerNeverReturnsAppliedWhenDeferredConstraintRejectsItsCommit() {
		jdbc.execute("create table if not exists wp2_resolution_effects(id uuid primary key)");
		jdbc.execute("create table if not exists wp2_resolution_guard(id uuid primary key, parent uuid references wp2_resolution_effects(id) deferrable initially deferred)");
		UUID id = UUID.randomUUID();
		var failure = Assertions.assertThrows(
			ConsumptionTransactionResolutionException.class,
			() -> transactions.runInTransaction(() -> {
				jdbc.update("insert into wp2_resolution_effects values (?)", id);
				jdbc.update("insert into wp2_resolution_guard values (?,?)", id, UUID.randomUUID());
				return FencedMutationResult.APPLIED;
			}));
		assertTrue(failure.outcome() != ConsumptionTransactionResolutionException.Outcome.COMMITTED);
		assertEquals(0, jdbc.queryForObject("select count(*) from wp2_resolution_effects where id=?", Integer.class, id));
		assertEquals(0, jdbc.queryForObject("select count(*) from wp2_resolution_guard where id=?", Integer.class, id));
	}

	private void awaitSuccessful(RecordedCommand command) {
		transactions.runInTransaction(() -> commands.insert(command));
		ConsumptionSlot slot = awaitTerminal(command.commandId());
		assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow(),
				() -> "Command failed with " + slot.terminalReason());
	}

	private LocalDate expenseDate(UUID expenseId, long startedAtVersion) {
		return jdbc.queryForObject(
				"select expense_date from expense_headers where expense_id = ? and started_at_version = ?",
				(result, row) -> result.getObject(1, LocalDate.class), expenseId, startedAtVersion);
	}

	private ConsumptionSlot awaitTerminal(CommandId commandId) {
		await(() -> lifecycle.findSlot(CommandConsumptionKeys.forCommand(commandId))
				.map(slot -> slot.terminalOutcome().isPresent()).orElse(false));
		return lifecycle.findSlot(CommandConsumptionKeys.forCommand(commandId)).orElseThrow();
	}

	private RecordedCommand command(CommandType type, String payload, UUID userId,
			Set<Permission> permissions, Instant validUntil) {
		ExternalIdentity identity = new ExternalIdentity("runtime-test", "subject-" + UUID.randomUUID());
		BindingId binding = createBinding(identity, userId);
		Set<String> authorities = permissions.stream()
				.map(permission -> "pocoma:" + permission.objectType().toLowerCase(Locale.ROOT)
						+ ":" + permission.action().toLowerCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());
		return new RecordedCommand(new CommandId(UUID.randomUUID()), type, payload, Instant.now(),
				new TargetCommandEnvelope(identity, binding,
						new CommandAuthenticationEvidence(authorities, validUntil)));
	}

	private BindingId createBinding(ExternalIdentity identity, UUID userId) {
		UUID binding = UUID.randomUUID();
		jdbc.update("insert into users(user_id) values (?) on conflict do nothing", userId);
		jdbc.update("insert into external_identity_binding_streams(issuer,subject,current_revision) values (?,?,0)",
				identity.issuer(), identity.subject());
		jdbc.update("""
				insert into external_identity_binding_occurrences
				(binding_id,issuer,subject,user_id,attached_revision,created_at) values (?,?,?,?,0,now())
				""", binding, identity.issuer(), identity.subject(), userId);
		jdbc.update("insert into external_identities(issuer,subject,user_id,binding_id) values (?,?,?,?)",
				identity.issuer(), identity.subject(), userId, binding);
		return new BindingId(binding);
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
