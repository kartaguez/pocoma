package com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
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
import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimEndReason;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ConsumptionSlot;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome.Rejected;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.Fail;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.RetryAfter;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.HandleConsumptionFailureInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.Acquired;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionProvenancePersistencePort;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.FinalizeConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalFinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.entity.consumption.JpaConsumptionSlotEntity;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.engine.exception.consumption.ConsumptionClaimClosureException;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import java.sql.Timestamp;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
		"spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class TransactionalConsumptionExecutionPostgresTest {

	private static final Instant NOW = Instant.parse("2026-08-31T08:00:00Z");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma")
			.withUsername("pocoma")
			.withPassword("pocoma");

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired
	private JpaConsumptionLifecycleAdapter lifecycle;

	@Autowired
	private JpaConsumptionProvenanceAdapter provenance;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbc;

	private MutableClock clock;
	private TransactionRunner transactions;
	private AcquireConsumptionUseCase acquire;
	private ExecuteConsumptionUseCase execute;
	private FinalizeConsumptionUseCase finalizeConsumption;
	private ExecutorService executor;

	@BeforeEach
	void setUp() {
		jdbc.execute("""
				create table if not exists lot3_business_effects (
				  effect_id uuid primary key, owner varchar(255) not null
				)
				""");
		jdbc.execute("""
				create table if not exists lot3_outbox (
				  outbox_id uuid primary key, payload varchar(255) not null
				)
				""");
		jdbc.update("delete from lot3_business_effects");
		jdbc.update("delete from lot3_outbox");
		jdbc.update("delete from consumption_inputs");
		jdbc.update("delete from consumption_results");
		jdbc.update("update consumption_slots set current_claim_id = null");
		jdbc.update("delete from consumption_claims");
		jdbc.update("delete from consumption_slots");

		clock = new MutableClock(NOW);
		transactions = new SpringTransactionRunner(new TransactionTemplate(transactionManager));
		acquire = new TransactionalAcquireConsumptionUseCase(
				new AcquireConsumptionService(lifecycle, clock), transactions);
		execute = transactionalExecution(lifecycle, provenance);
		finalizeConsumption = new TransactionalFinalizeConsumptionUseCase(
				new FinalizeConsumptionService(lifecycle, clock), transactions);
		executor = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void shutdownExecutor() {
		executor.shutdownNow();
	}

	@Test
	void successfulExecutionBecomesVisibleAsOneCommit() throws Exception {
		Claim claim = acquire("atomic-success", "worker-a");
		CountDownLatch beforeCas = new CountDownLatch(1);
		CountDownLatch allowCas = new CountDownLatch(1);
		ExecuteConsumptionUseCase blocked = transactionalExecution(
				new BlockingTerminalizationPort(lifecycle, beforeCas, allowCas), provenance);

		Future<ConsumptionExecutionResult> future = executor.submit(() -> blocked.execute(input(
				claim, "winner", new Success())));
		assertTrue(beforeCas.await(10, TimeUnit.SECONDS));

		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(0, observedCount("lot3_outbox"));
		assertEquals(0, observedCount("consumption_inputs"));
		assertEquals("PENDING", observedSlotStatus(claim.slotId()));

		allowCas.countDown();
		future.get(10, TimeUnit.SECONDS);

		assertEquals(1, observedCount("lot3_business_effects"));
		assertEquals(1, observedCount("lot3_outbox"));
		assertEquals(1, observedCount("consumption_inputs"));
		assertEquals(1, observedCount("consumption_results"));
		assertEquals("DONE", observedSlotStatus(claim.slotId()));
		assertEquals(Optional.of(TerminalOutcome.SUCCESS), lifecycle.findSlot(claim.slotId()).orElseThrow().terminalOutcome());
		assertEquals(Optional.of(ClaimEndReason.SUCCESS), lifecycle.findClaim(claim.claimId()).orElseThrow().endReason());
	}

	@Test
	void expiredLeaseStillCommitsWithoutTakeover() {
		Claim claim = acquire("expired-without-takeover", "worker-a");
		clock.set(NOW.plusSeconds(30));

		execute.execute(input(claim, "expired-owner", new Success()));

		assertEquals(Optional.of(TerminalOutcome.SUCCESS), lifecycle.findSlot(claim.slotId()).orElseThrow().terminalOutcome());
		assertEquals(1, observedCount("lot3_business_effects"));
	}

	@Test
	void takeoverDuringProvisionalEffectWinsBeforeFinalCas() throws Exception {
		Claim claim = acquire("short-finalization-wins", "worker-a");
		clock.set(NOW.plusSeconds(30));
		CountDownLatch effectStarted = new CountDownLatch(1);
		CountDownLatch allowEffect = new CountDownLatch(1);

		Future<FencedMutationResult> finalization = executor.submit(() -> finalizeConsumption.finalizeConsumption(
				new FinalizeConsumptionInput(claim.slotId(), claim.claimId(), new ConsumptionFinalization.Success(), () -> {
					writeBusiness("worker-a");
					effectStarted.countDown();
					await(allowEffect);
				})));
		assertTrue(effectStarted.await(10, TimeUnit.SECONDS));
		Future<AcquireResult> takeover =
				executor.submit(() -> acquire.acquire(new AcquireConsumptionInput(
						key("short-finalization-wins"), new WorkerId("worker-b"), LEASE)));

		var winner = assertInstanceOf(AcquireResult.Acquired.class,
				takeover.get(10, TimeUnit.SECONDS)).claim();
		assertEquals(0, observedCount("lot3_business_effects"));
		allowEffect.countDown();
		assertEquals(FencedMutationResult.LOST_CLAIM, finalization.get(10, TimeUnit.SECONDS));
		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(Optional.of(winner.claimId()), lifecycle.findSlot(claim.slotId()).orElseThrow().currentClaimId());
	}

	@Test
	void expiredCurrentClaimCanStillFinalizeWithoutTakeover() {
		Claim claim = acquire("expired-finalizer", "worker-a");
		clock.set(NOW.plusSeconds(30));
		assertEquals(FencedMutationResult.APPLIED, finalizeConsumption.finalizeConsumption(
				new FinalizeConsumptionInput(claim.slotId(), claim.claimId(), new ConsumptionFinalization.Success(), () -> writeBusiness("expired"))));
		assertEquals(1, observedCount("lot3_business_effects"));
	}

	@Test
	void winningCasAndEffectsRollBackWhenClaimClosureRefuses() {
		Claim claim = acquire("closure-refused", "worker-a");
		assertThrows(ConsumptionClaimClosureException.class,
				() -> finalizeConsumption.finalizeConsumption(new FinalizeConsumptionInput(claim.slotId(), claim.claimId(),
					new ConsumptionFinalization.Success(), () -> {
						writeBusiness("closure-refused");
						jdbc.update("update consumption_claims set invalidated_at=?, end_reason='TAKEN_OVER' where claim_id=?",
								Timestamp.from(clock.instant()), claim.claimId().value());
					})));
		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(ConsumptionStatus.PENDING, lifecycle.findSlot(claim.slotId()).orElseThrow().status());
		assertEquals(Optional.of(claim.claimId()), lifecycle.findSlot(claim.slotId()).orElseThrow().currentClaimId());
		assertTrue(lifecycle.findClaim(claim.claimId()).orElseThrow().isOpen());
		assertTrue(lifecycle.findClaim(claim.claimId()).orElseThrow().invalidatedAt().isEmpty());
	}

	@Test
	void retryCasAndScheduleRollBackWhenClaimClosureRefuses() {
		Claim claim = acquire("retry-closure-refused", "worker-a");
		var before = lifecycle.findSlot(claim.slotId()).orElseThrow();
		var failure = new ProcessingFailure(new ProcessingFailureCode("RETRY_REFUSED"), "transient", "retry", NOW);
		assertThrows(ConsumptionClaimClosureException.class,
			() -> transactions.runInTransaction(() -> {
				jdbc.update("update consumption_claims set invalidated_at=?, end_reason='TAKEN_OVER' where claim_id=?",
					Timestamp.from(NOW), claim.claimId().value());
				return new HandleConsumptionFailureService(lifecycle, lifecycle,
					context -> new FailureDecision.RetryAfter(Duration.ofSeconds(5)), clock)
					.handle(new HandleConsumptionFailureInput(claim.slotId(), claim.claimId(), failure,
						() -> { throw new AssertionError("Retry must not invoke a terminal publication"); }));
			}));
		assertEquals(before, lifecycle.findSlot(claim.slotId()).orElseThrow());
		assertTrue(lifecycle.findClaim(claim.claimId()).orElseThrow().isOpen());
		assertTrue(lifecycle.findClaim(claim.claimId()).orElseThrow().invalidatedAt().isEmpty());
		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(0, observedCount("consumption_inputs"));
		assertEquals(0, observedCount("consumption_results"));
	}

	@Test
	void deferredConstraintRefusesCommitAfterWinningCasAndRollsBackEverything() {
		jdbc.execute("create table if not exists lot3_commit_guard (id uuid primary key, parent uuid references lot3_business_effects(effect_id) deferrable initially deferred)");
		Claim claim = acquire("commit-refused", "worker-a");
		var refused = assertThrows(RuntimeException.class, () -> finalizeConsumption.finalizeConsumption(
			new FinalizeConsumptionInput(claim.slotId(), claim.claimId(), new ConsumptionFinalization.Success(), () -> {
				writeBusiness("commit-refused");
				jdbc.update("insert into lot3_commit_guard values (?,?)", UUID.randomUUID(), UUID.randomUUID());
			})));
		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(0, observedCount("lot3_commit_guard"));
		assertTrue(sqlStates(refused).contains("23503"));
		System.out.println("WP4_COMMIT_REFUSED SQLSTATE="+sqlStates(refused)+" effects=0 slot=PENDING claim=open");
		assertEquals(ConsumptionStatus.PENDING, lifecycle.findSlot(claim.slotId()).orElseThrow().status());
		assertEquals(Optional.of(claim.claimId()), lifecycle.findSlot(claim.slotId()).orElseThrow().currentClaimId());
		assertTrue(lifecycle.findClaim(claim.claimId()).orElseThrow().isOpen());
	}

	@Test
	void takeoverBeforeShortFinalizationPreventsAnyDurableEffect() {
		Claim stale = acquire("short-finalization-loses", "worker-a");
		clock.set(NOW.plusSeconds(30));
		Claim winner = acquire("short-finalization-loses", "worker-b");

		assertEquals(FencedMutationResult.LOST_CLAIM, finalizeConsumption.finalizeConsumption(
				new FinalizeConsumptionInput(stale.slotId(), stale.claimId(), new ConsumptionFinalization.Success(),
						() -> writeBusiness("stale"))));
		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(Optional.of(winner.claimId()), lifecycle.findSlot(winner.slotId()).orElseThrow().currentClaimId());
	}

	@Test
	void shortFinalizationRollsBackTheEffectWhenCompletionCannotCommit() {
		Claim claim = acquire("short-finalization-rollback", "worker-a");

		assertThrows(TestTechnicalException.class, () -> finalizeConsumption.finalizeConsumption(
				new FinalizeConsumptionInput(claim.slotId(), claim.claimId(), new ConsumptionFinalization.Success(), () -> {
					writeBusiness("rollback");
					throw new TestTechnicalException();
				})));
		assertEquals(0, observedCount("lot3_business_effects"));
		assertTrue(lifecycle.findClaim(claim.claimId()).orElseThrow().isOpen());
		assertEquals(ConsumptionStatus.PENDING, lifecycle.findSlot(claim.slotId()).orElseThrow().status());
	}

	@Test
	void staleWorkerRollsBackEverythingAndTakeoverWinnerCommits() throws Exception {
		Claim stale = acquire("takeover", "worker-a");
		CountDownLatch businessWritten = new CountDownLatch(1);
		CountDownLatch allowStaleToFinish = new CountDownLatch(1);
		Future<ConsumptionExecutionResult> staleExecution = executor.submit(() -> execute.execute(
				new ExecuteConsumptionInput(stale.slotId(), stale.claimId(), context -> {
					writeBusiness("worker-a");
					businessWritten.countDown();
					await(allowStaleToFinish);
					return provenance(context.slotId(), new Success(), "worker-a");
				})));
		assertTrue(businessWritten.await(10, TimeUnit.SECONDS));

		clock.set(NOW.plusSeconds(30));
		Claim winner = acquire("takeover", "worker-b");
		allowStaleToFinish.countDown();

		ExecutionException staleFailure = assertThrows(
				ExecutionException.class, () -> staleExecution.get(10, TimeUnit.SECONDS));
		assertInstanceOf(LostClaimException.class, staleFailure.getCause());
		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(0, observedCount("lot3_outbox"));
		assertEquals(0, observedCount("consumption_inputs"));
		assertEquals(0, observedCount("consumption_results"));
		assertEquals(Optional.of(winner.claimId()), lifecycle.findSlot(winner.slotId()).orElseThrow().currentClaimId());

		execute.execute(input(winner, "worker-b", new Success()));

		assertEquals(1, observedCount("lot3_business_effects"));
		assertEquals("worker-b", jdbc.queryForObject(
				"select owner from lot3_business_effects", String.class));
		assertEquals(Optional.of(TerminalOutcome.SUCCESS), lifecycle.findSlot(winner.slotId()).orElseThrow().terminalOutcome());
	}

	@Test
	void businessRejectionCommitsWithoutTechnicalFailure() {
		Claim claim = acquire("rejected", "worker");

		ConsumptionExecutionResult result = execute.execute(
				input(claim, "rejected-owner", new Rejected("VERSION_CONFLICT")));

		assertEquals("VERSION_CONFLICT", ((Rejected) result.outcome()).rejectionCode());
		assertEquals(Optional.of(TerminalOutcome.REJECTED), lifecycle.findSlot(claim.slotId()).orElseThrow().terminalOutcome());
		assertEquals(Optional.of(new TerminalReason("VERSION_CONFLICT")),
				lifecycle.findSlot(claim.slotId()).orElseThrow().terminalReason());
		Claim ended = lifecycle.findClaim(claim.claimId()).orElseThrow();
		assertEquals(Optional.of(ClaimEndReason.REJECTED), ended.endReason());
		assertTrue(ended.failure().isEmpty());
		assertEquals(1, observedCount("lot3_business_effects"));
	}

	@Test
	void technicalExceptionRollsBackThenRetryPolicyRunsInASeparateTransaction() {
		Claim claim = acquire("technical-retry", "worker");

		assertThrows(TestTechnicalException.class, () -> execute.execute(new ExecuteConsumptionInput(
				claim.slotId(), claim.claimId(), context -> {
					writeBusiness("will-rollback");
					throw new TestTechnicalException();
				})));
		assertEquals(0, observedCount("lot3_business_effects"));
		assertTrue(lifecycle.findSlot(claim.slotId()).orElseThrow().terminalOutcome().isEmpty());
		assertTrue(lifecycle.findClaim(claim.claimId()).orElseThrow().isOpen());

		clock.set(NOW.plusSeconds(1));
		FencedMutationResult handled = failureHandler(context -> new RetryAfter(Duration.ofSeconds(5))).handle(
				new HandleConsumptionFailureInput(claim.slotId(), claim.claimId(), failure("TEMPORARY")));

		assertEquals(FencedMutationResult.APPLIED, handled);
		ConsumptionSlot slot = lifecycle.findSlot(claim.slotId()).orElseThrow();
		assertEquals(ConsumptionStatus.PENDING, slot.status());
		assertTrue(slot.currentClaimId().isEmpty());
		assertEquals(NOW.plusSeconds(6), slot.nextClaimAt());
		assertEquals(Optional.of(ClaimEndReason.PROCESSING_FAILURE),
				lifecycle.findClaim(claim.claimId()).orElseThrow().endReason());
	}

	@Test
	void terminalFailureCommitsFailedAfterBusinessRollback() {
		Claim claim = acquire("technical-fail", "worker");

		assertThrows(TestTechnicalException.class, () -> execute.execute(new ExecuteConsumptionInput(
				claim.slotId(), claim.claimId(), context -> {
					writeBusiness("will-rollback");
					throw new TestTechnicalException();
				})));
		clock.set(NOW.plusSeconds(1));
		assertEquals(FencedMutationResult.APPLIED, failureHandler(context -> new Fail()).handle(
				new HandleConsumptionFailureInput(
						claim.slotId(), claim.claimId(), failure("DEADLOCK", "TRANSIENT"))));

		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(Optional.of(TerminalOutcome.FAILED), lifecycle.findSlot(claim.slotId()).orElseThrow().terminalOutcome());
		assertEquals(Optional.of(new TerminalReason("DEADLOCK")),
				lifecycle.findSlot(claim.slotId()).orElseThrow().terminalReason());
	}

	@Test
	void staleFailureHandlingDoesNotModifyTakeoverWinner() {
		Claim stale = acquire("stale-failure", "worker-a");
		clock.set(NOW.plusSeconds(30));
		Claim winner = acquire("stale-failure", "worker-b");

		assertEquals(FencedMutationResult.LOST_CLAIM, failureHandler(context -> new Fail()).handle(
				new HandleConsumptionFailureInput(stale.slotId(), stale.claimId(), failure("LATE"))));

		assertEquals(Optional.of(winner.claimId()), lifecycle.findSlot(winner.slotId()).orElseThrow().currentClaimId());
		assertTrue(lifecycle.findClaim(winner.claimId()).orElseThrow().isOpen());
	}

	@Test
	void provenancePersistenceExceptionRollsBackBusinessAndProvenance() {
		Claim claim = acquire("provenance-failure", "worker");
		ConsumptionProvenancePersistencePort failing = new FailingProvenancePort(provenance);
		ExecuteConsumptionUseCase execution = transactionalExecution(lifecycle, failing);

		assertThrows(TestTechnicalException.class, () -> execution.execute(
				input(claim, "will-rollback", new Success())));

		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(0, observedCount("lot3_outbox"));
		assertEquals(0, observedCount("consumption_inputs"));
		assertEquals(0, observedCount("consumption_results"));
		assertTrue(lifecycle.findClaim(claim.claimId()).orElseThrow().isOpen());
	}

	@Test
	void crossSlotProvenanceRollsBackBusinessEffects() {
		Claim claim = acquire("wrong-provenance", "worker");

		assertThrows(IllegalArgumentException.class, () -> execute.execute(new ExecuteConsumptionInput(
				claim.slotId(), claim.claimId(), context -> {
					writeBusiness("will-rollback");
					return provenance(UUID.randomUUID(), new Success(), "wrong-slot");
				})));

		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(0, observedCount("consumption_inputs"));
		assertTrue(lifecycle.findClaim(claim.claimId()).orElseThrow().isOpen());
	}

	@Test void twoFkHoldersAndQueuedTakeoverAllowTerminalCommit() throws Exception {
		characterizeFkQueue(false);
	}

	@Test void twoFkHoldersAndQueuedTakeoverAllowTerminalRollback() throws Exception {
		characterizeFkQueue(true);
	}

	private void characterizeFkQueue(boolean rollbackFirst) throws Exception {
		Claim claim = acquire("fk-queue", "owner");
		clock.set(NOW.plusSeconds(31));
		CountDownLatch firstReady = new CountDownLatch(1), secondReady = new CountDownLatch(1);
		CountDownLatch firstCas = new CountDownLatch(1), secondCas = new CountDownLatch(1);
		AtomicReference<Integer> firstPid = new AtomicReference<>(), secondPid = new AtomicReference<>(), takeoverPid = new AtomicReference<>();
		try (var pool = Executors.newFixedThreadPool(3)) {
			var first = pool.submit(() -> transactions.runInTransaction(() -> {
				firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
				writeBusiness("first");
				insertImmediateInput(claim, "first");
				firstReady.countDown(); await(firstCas);
				lifecycle.terminalize(claim.slotId(), claim.claimId(), new ConsumptionFinalization.Success(), clock.instant());
				if (rollbackFirst) throw new QueueRollback();
				return "committed";
			}));
			var second = pool.submit(() -> transactions.runInTransaction(() -> {
				secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
				jdbc.queryForObject("select txid_current()", Long.class);
				writeBusiness("second");
				insertImmediateInput(claim, "second");
				secondReady.countDown(); await(secondCas);
				lifecycle.terminalize(claim.slotId(), claim.claimId(), new ConsumptionFinalization.Rejected("SECOND"), clock.instant());
				return "committed";
			}));
			try {
				assertTrue(firstReady.await(10, TimeUnit.SECONDS));
				assertTrue(secondReady.await(10, TimeUnit.SECONDS));
				assertEquals(0, observedCount("consumption_inputs"));
				var takeover = pool.submit(() -> transactions.runInTransaction(() -> {
					takeoverPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
					jdbc.queryForObject("select txid_current()", Long.class);
					return lifecycle.acquire(key("fk-queue"), new ClaimId(UUID.randomUUID()), new WorkerId("takeover"), LEASE, clock.instant());
				}));
				awaitBlockedBy(takeoverPid, firstPid.get(), secondPid.get());
				System.out.println("WP4_THREE_BACKENDS " + jdbc.queryForList("select pid,backend_xid::text as xid from pg_stat_activity where pid in (?,?,?)", firstPid.get(), secondPid.get(), takeoverPid.get()));
				System.out.println("WP4_T07 two FK holders=" + firstPid + "," + secondPid + " takeover=" + takeoverPid);
				firstCas.countDown();
				Throwable firstError = queueError(first);
				if (rollbackFirst) assertInstanceOf(QueueRollback.class, firstError);
				else assertEquals(null, firstError);
				secondCas.countDown();
				Throwable secondError = queueError(second);
				if (rollbackFirst) assertEquals(null, secondError);
				else assertInstanceOf(LostClaimException.class, secondError);
				assertInstanceOf(AcquireResult.AlreadyDone.class, takeover.get(10, TimeUnit.SECONDS));
				assertEquals(1, observedCount("lot3_business_effects"));
				assertEquals(1, observedCount("lot3_outbox"));
				assertEquals(1, observedCount("consumption_inputs"));
				assertEquals(rollbackFirst ? "second" : "first", jdbc.queryForObject("select subject_id from consumption_inputs", String.class));
				assertEquals(rollbackFirst ? TerminalOutcome.REJECTED : TerminalOutcome.SUCCESS,
						lifecycle.findSlot(claim.slotId()).orElseThrow().terminalOutcome().orElseThrow());
				assertEquals(1, lifecycle.findClaims(claim.slotId()).size());
				System.out.println("WP4_T07 rollbackFirst=" + rollbackFirst + " durable effects=1 inputs=1 claims=1 DONE; SQLSTATE none");
			} finally { firstCas.countDown(); secondCas.countDown(); }
		}
	}

	@Test void takeoverAfterWinningCasSeesDoneAfterCommit() throws Exception { characterizeAfterCas(false, true); }
	@Test void takeoverAfterWinningCasAcquiresAfterRollback() throws Exception { characterizeAfterCas(true, true); }
	@Test void secondTerminalizationLosesAfterFirstCommit() throws Exception { characterizeAfterCas(false, false); }
	@Test void secondTerminalizationWinsAfterFirstRollback() throws Exception { characterizeAfterCas(true, false); }

	private void characterizeAfterCas(boolean rollbackFirst, boolean isTakeover) throws Exception {
		Claim claim = acquire("after-cas", "first");
		clock.set(NOW.plusSeconds(31));
		CountDownLatch casWon = new CountDownLatch(1), resolve = new CountDownLatch(1);
		AtomicReference<Integer> firstPid = new AtomicReference<>(), secondPid = new AtomicReference<>();
		var first = executor.submit(() -> transactions.runInTransaction(() -> {
			firstPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
			writeBusiness("first"); insertImmediateInput(claim, "first");
			lifecycle.terminalize(claim.slotId(), claim.claimId(), new ConsumptionFinalization.Success(), clock.instant());
			casWon.countDown(); await(resolve);
			if (rollbackFirst) throw new QueueRollback();
			return "committed";
		}));
		try {
			assertTrue(casWon.await(10, TimeUnit.SECONDS));
			assertEquals("PENDING", observedSlotStatus(claim.slotId()));
			assertEquals(0, observedCount("lot3_business_effects"));
			try (Connection probe = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
				probe.setAutoCommit(false);
				try (var statement = probe.prepareStatement("select slot_id from consumption_slots where slot_id=? for key share nowait")) {
					statement.setObject(1, claim.slotId());
					try (var rows = statement.executeQuery()) { assertTrue(rows.next()); }
				} finally { probe.rollback(); }
			}
			System.out.println("WP4_CAS_MODE KEY SHARE NOWAIT granted after CAS; compatible with NO KEY UPDATE");
			var second = executor.submit(() -> transactions.runInTransaction(() -> {
				secondPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
				jdbc.queryForObject("select txid_current()", Long.class);
				if (isTakeover) return lifecycle.acquire(key("after-cas"), new ClaimId(UUID.randomUUID()), new WorkerId("second"), LEASE, clock.instant());
				writeBusiness("second"); insertImmediateInput(claim, "second");
				lifecycle.terminalize(claim.slotId(), claim.claimId(), new ConsumptionFinalization.TerminalFailure(failure("SECOND")), clock.instant());
				return FencedMutationResult.APPLIED;
			}));
			awaitBlockedBy(secondPid, firstPid.get());
			resolve.countDown();
			Throwable firstError = queueError(first);
			if (rollbackFirst) assertInstanceOf(QueueRollback.class, firstError); else assertEquals(null, firstError);
			if (isTakeover) {
				var result = second.get(10, TimeUnit.SECONDS);
				if (rollbackFirst) {
					var winner = assertInstanceOf(Acquired.class, result).claim();
					assertEquals(2, winner.attemptNumber());
					assertEquals(Optional.of(winner.claimId()), lifecycle.findSlot(claim.slotId()).orElseThrow().currentClaimId());
					assertEquals(Optional.of(ClaimEndReason.TAKEN_OVER), lifecycle.findClaim(claim.claimId()).orElseThrow().endReason());
				} else assertInstanceOf(AcquireResult.AlreadyDone.class, result);
			} else {
				Throwable error = queueError(second);
				if (rollbackFirst) assertEquals(null, error); else assertInstanceOf(LostClaimException.class, error);
				assertEquals(rollbackFirst ? TerminalOutcome.FAILED : TerminalOutcome.SUCCESS,
						lifecycle.findSlot(claim.slotId()).orElseThrow().terminalOutcome().orElseThrow());
			}
			int effects = isTakeover && rollbackFirst ? 0 : 1;
			assertEquals(effects, observedCount("lot3_business_effects"));
			assertEquals(effects, observedCount("lot3_outbox"));
			assertEquals(effects, observedCount("consumption_inputs"));
			System.out.println("WP4_AFTER_CAS takeover="+isTakeover+" rollbackFirst="+rollbackFirst+" durable effects="+effects+" SQLSTATE none");
		} finally { resolve.countDown(); }
	}

	@Test void retryLosingToCommittedTakeoverLeavesWinnerAndCalendarIntact() {
		Claim stale = acquire("retry-takeover", "stale");
		clock.set(NOW.plusSeconds(31));
		Claim winner = acquire("retry-takeover", "winner");
		var before = lifecycle.findSlot(winner.slotId()).orElseThrow();
		assertEquals(FencedMutationResult.LOST_CLAIM, failureHandler(context -> new RetryAfter(Duration.ofSeconds(5)))
				.handle(new HandleConsumptionFailureInput(stale.slotId(), stale.claimId(), failure("RETRY"),
						() -> { throw new AssertionError("no terminal retry effect"); })));
		assertEquals(before, lifecycle.findSlot(winner.slotId()).orElseThrow());
		assertTrue(lifecycle.findClaim(winner.claimId()).orElseThrow().isOpen());
		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(0, observedCount("consumption_inputs"));
	}

	@Test void rolledBackTakeoverDoesNotConsumeAttemptOrInvalidateOldAuthority() {
		Claim first = acquire("takeover-rollback", "first");
		clock.set(NOW.plusSeconds(31));
		AtomicReference<Claim> provisional = new AtomicReference<>();
		assertThrows(QueueRollback.class, () -> transactions.runInTransaction(() -> {
			provisional.set(assertInstanceOf(Acquired.class, lifecycle.acquire(key("takeover-rollback"),
					new ClaimId(UUID.randomUUID()), new WorkerId("rolled-back"), LEASE, clock.instant())).claim());
			throw new QueueRollback();
		}));
		assertTrue(lifecycle.findClaim(provisional.get().claimId()).isEmpty());
		assertTrue(lifecycle.findClaim(first.claimId()).orElseThrow().isOpen());
		assertEquals(Optional.of(first.claimId()), lifecycle.findSlot(first.slotId()).orElseThrow().currentClaimId());
		Claim winner = acquire("takeover-rollback", "winner");
		assertEquals(2, winner.attemptNumber());
		assertEquals(2, lifecycle.findClaims(first.slotId()).size());
	}

	@Test void sharedInputUniqueWaitPreservesCommittedHolder() throws Exception { characterizeUniqueInput(false); }
	@Test void sharedInputUniqueWaitAllowsWaiterAfterRollback() throws Exception { characterizeUniqueInput(true); }

	private void characterizeUniqueInput(boolean rollbackHolder) throws Exception {
		Claim claim = acquire("shared-input", "owner");
		CountDownLatch inserted = new CountDownLatch(1), resolve = new CountDownLatch(1);
		AtomicReference<Integer> holderPid = new AtomicReference<>(), waiterPid = new AtomicReference<>();
		var holder = executor.submit(() -> transactions.runInTransaction(() -> {
			 holderPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
			 writeBusiness("holder"); insertImmediateInput(claim, "shared");
			 inserted.countDown(); await(resolve);
			 lifecycle.terminalize(claim.slotId(),claim.claimId(),new ConsumptionFinalization.Success(),clock.instant());
			 if (rollbackHolder) throw new QueueRollback();
			 return "committed";
		}));
		try {
			assertTrue(inserted.await(10,TimeUnit.SECONDS));
			var waiter = executor.submit(() -> transactions.runInTransaction(() -> {
				waiterPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
				writeBusiness("waiter"); insertImmediateInput(claim, "shared");
				lifecycle.terminalize(claim.slotId(),claim.claimId(),new ConsumptionFinalization.Rejected("WAITER"),clock.instant());
				return "committed";
			}));
			awaitBlockedBy(waiterPid,holderPid.get());
			assertEquals(0,observedCount("consumption_inputs"));
			resolve.countDown();
			Throwable holderError=queueError(holder), waiterError=queueError(waiter);
			if (rollbackHolder) {
				assertInstanceOf(QueueRollback.class,holderError); assertEquals(null,waiterError);
			} else {
				assertEquals(null,holderError); assertTrue(sqlStates(waiterError).contains("23505"));
			}
			assertEquals(1,observedCount("consumption_inputs"));
			assertEquals(1,observedCount("lot3_business_effects"));
			assertEquals(rollbackHolder?"waiter":"holder",jdbc.queryForObject("select owner from lot3_business_effects",String.class));
			System.out.println("WP4_UNIQUE rollbackHolder="+rollbackHolder+" waiterSQLSTATE="+sqlStates(waiterError)+" inputs=1 effects=1");
		} finally { resolve.countDown(); }
	}

	private static java.util.Set<String> sqlStates(Throwable error) {
		var states = new java.util.HashSet<String>();
		for (Throwable cause=error;cause!=null;cause=cause.getCause())
			if (cause instanceof java.sql.SQLException sql) states.add(sql.getSQLState());
		return states;
	}

	private void insertImmediateInput(Claim claim, String subject) {
		jdbc.update("insert into consumption_inputs(input_id,slot_id,subject_type,subject_id,subject_version) values (?,?,'QUEUE',?,1)",
				UUID.randomUUID(), claim.slotId(), subject);
	}

	private void awaitBlockedBy(AtomicReference<Integer> waiter, int... blockers) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Integer pid = waiter.get();
			for (int blocker : blockers) {
			if (pid != null && Boolean.TRUE.equals(jdbc.queryForObject(
					"select ?=any(pg_blocking_pids(?))", Boolean.class, blocker, pid))) {
				System.out.println("WP4_WAIT " + jdbc.queryForList(
						"select pid,backend_xid::text as xid,wait_event_type,wait_event,query,pg_blocking_pids(pid) as blockers from pg_stat_activity where pid in (?,?)", blocker,pid));
				System.out.println("WP4_LOCKS " + jdbc.queryForList(
						"select pid,locktype,mode,granted,relation::regclass::text as relation,transactionid::text from pg_locks where pid in (?,?)",blocker,pid));
				return;
			}
			}
			Thread.onSpinWait();
		}
		throw new AssertionError("No PostgreSQL wait for " + waiter + " on " + java.util.Arrays.toString(blockers));
	}

	private static Throwable queueError(Future<?> future) throws Exception {
		try { future.get(10, TimeUnit.SECONDS); return null; }
		catch (ExecutionException error) { return error.getCause(); }
	}

	private static final class QueueRollback extends RuntimeException { }

	private ExecuteConsumptionUseCase transactionalExecution(
			ConsumptionLifecyclePersistencePort lifecyclePort,
			ConsumptionProvenancePersistencePort provenancePort) {
		return new TransactionalExecuteConsumptionUseCase(
				new ExecuteConsumptionService(lifecyclePort, provenancePort, clock), transactions);
	}

	private HandleConsumptionFailureUseCase failureHandler(ConsumptionFailurePolicy policy) {
		return new TransactionalHandleConsumptionFailureUseCase(
				new HandleConsumptionFailureService(lifecycle, lifecycle, policy, clock), transactions);
	}

	private Claim acquire(String identity, String worker) {
		return assertInstanceOf(Acquired.class, acquire.acquire(new AcquireConsumptionInput(
				key(identity), new WorkerId(worker), LEASE))).claim();
	}

	private ExecuteConsumptionInput input(
			Claim claim, String owner, BusinessConsumptionOutcome outcome) {
		return new ExecuteConsumptionInput(claim.slotId(), claim.claimId(), context -> {
			writeBusiness(owner);
			return provenance(context.slotId(), outcome, owner);
		});
	}

	private void writeBusiness(String owner) {
		jdbc.update("insert into lot3_business_effects (effect_id, owner) values (?, ?)", UUID.randomUUID(), owner);
		jdbc.update("insert into lot3_outbox (outbox_id, payload) values (?, ?)", UUID.randomUUID(), owner);
	}

	private static ConsumptionExecutionResult provenance(
			UUID slotId, BusinessConsumptionOutcome outcome, String suffix) {
		return new ConsumptionExecutionResult(
				outcome,
				List.of(new ConsumptionInput(slotId, "POT", "pot-" + suffix, 7)),
				List.of(new ConsumptionResult(
						slotId, "OUTBOX", "TEST_EFFECT", "effect-" + suffix,
						OptionalLong.empty(), Optional.of("POT"), Optional.of("pot-" + suffix),
						OptionalLong.of(8), NOW)));
	}

	private static ConsumptionKey key(String identity) {
		return new ConsumptionKey(
				new ConsumableIdentity("TEST_WORK", List.of(identity)),
				new ConsumerIdentity("LOT_3_TEST", List.of()));
	}

	private ProcessingFailure failure(String category) {
		return failure(category, category);
	}

	private ProcessingFailure failure(String code, String category) {
		return new ProcessingFailure(
				new ProcessingFailureCode(code), category, code + " failure", clock.instant());
	}

	private int observedCount(String table) {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				Statement statement = connection.createStatement();
				ResultSet result = statement.executeQuery("select count(*) from " + table)) {
			result.next();
			return result.getInt(1);
		} catch (Exception exception) {
			throw new IllegalStateException("Could not observe table " + table, exception);
		}
	}

	private String observedSlotStatus(UUID slotId) {
		try (Connection connection = DriverManager.getConnection(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
				var statement = connection.prepareStatement(
						"select status from consumption_slots where slot_id = ?")) {
			statement.setObject(1, slotId);
			try (ResultSet result = statement.executeQuery()) {
				result.next();
				return result.getString(1);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Could not observe slot " + slotId, exception);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for concurrent test coordination");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while coordinating concurrent test", exception);
		}
	}

	private static final class BlockingTerminalizationPort implements ConsumptionLifecyclePersistencePort {
		@Override public void terminalize(UUID slotId, ClaimId claimId,
				ConsumptionFinalization terminal, Instant now) {
			if (terminal instanceof ConsumptionFinalization.TerminalFailure failed) {
				if (handleFailure(slotId, claimId, failed.failure(), new FailureDecision.Fail(), now) != FencedMutationResult.APPLIED)
					throw new LostClaimException(slotId, claimId);
			} else {
				boolean rejected = terminal instanceof ConsumptionFinalization.Rejected;
				if (!tryTerminalize(slotId, claimId, rejected ? TerminalOutcome.REJECTED : TerminalOutcome.SUCCESS,
						rejected ? Optional.of(new TerminalReason(((ConsumptionFinalization.Rejected) terminal).rejectionCode())) : Optional.empty(), now))
					throw new LostClaimException(slotId, claimId);
			}
		}
		@Override public void scheduleRetry(UUID slotId, ClaimId claimId, ProcessingFailure failure, Instant nextClaimAt, Instant now) {
			if (handleFailure(slotId, claimId, failure, new FailureDecision.RetryAfter(Duration.between(now, nextClaimAt)), now) != FencedMutationResult.APPLIED)
				throw new LostClaimException(slotId, claimId);
		}

		private final ConsumptionLifecyclePersistencePort delegate;
		private final CountDownLatch reached;
		private final CountDownLatch proceed;

		private BlockingTerminalizationPort(
				ConsumptionLifecyclePersistencePort delegate,
				CountDownLatch reached,
				CountDownLatch proceed) {
			this.delegate = delegate;
			this.reached = reached;
			this.proceed = proceed;
		}

		@Override
		public boolean lockCurrentClaim(UUID slotId, ClaimId claimId) {
			return delegate.lockCurrentClaim(slotId, claimId);
		}

		@Override
		public boolean tryTerminalize(
				UUID slotId, ClaimId claimId, TerminalOutcome outcome,
				Optional<TerminalReason> reason, Instant doneAt) {
			reached.countDown();
			await(proceed);
			return delegate.tryTerminalize(slotId, claimId, outcome, reason, doneAt);
		}

		@Override
		public AcquireResult acquire(
				ConsumptionKey key, ClaimId claimId, WorkerId workerId, ClaimLease lease, Instant now) {
			return delegate.acquire(key, claimId, workerId, lease, now);
		}

		@Override
		public FencedMutationResult handleFailure(
				UUID slotId, ClaimId claimId, ProcessingFailure failure,
				FailureDecision decision,
				Instant now) {
			return delegate.handleFailure(slotId, claimId, failure, decision, now);
		}

	}

	private static final class FailingProvenancePort implements ConsumptionProvenancePersistencePort {
		private final ConsumptionProvenancePersistencePort delegate;

		private FailingProvenancePort(ConsumptionProvenancePersistencePort delegate) {
			this.delegate = delegate;
		}

		@Override
		public void appendInputs(List<ConsumptionInput> inputs) {
			delegate.appendInputs(inputs);
		}

		@Override
		public void appendResults(List<ConsumptionResult> results) {
			delegate.appendResults(results);
			throw new TestTechnicalException();
		}

		@Override
		public List<ConsumptionInput> findInputs(UUID slotId) {
			return delegate.findInputs(slotId);
		}

		@Override
		public List<ConsumptionResult> findResults(UUID slotId) {
			return delegate.findResults(slotId);
		}
	}

	private static final class MutableClock extends Clock {
		private final AtomicReference<Instant> instant;

		private MutableClock(Instant initial) {
			instant = new AtomicReference<>(initial);
		}

		void set(Instant value) {
			instant.set(value);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant.get();
		}
	}

	private static final class TestTechnicalException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = JpaConsumptionSlotEntity.class)
	@EnableJpaRepositories(basePackageClasses = JpaConsumptionSlotRepository.class)
	@Import({JpaConsumptionLifecycleAdapter.class, JpaConsumptionProvenanceAdapter.class})
	static class TestApplication {
		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}
}
