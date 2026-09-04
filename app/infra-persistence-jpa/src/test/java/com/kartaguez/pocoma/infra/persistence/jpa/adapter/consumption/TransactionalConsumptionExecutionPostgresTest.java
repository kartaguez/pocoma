package com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption;

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
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome.Rejected;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.Fail;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.RetryAfter;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.HandleConsumptionFailureInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.Acquired;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionLifecyclePersistencePort;
import com.kartaguez.pocoma.engine.port.out.consumption.ConsumptionProvenancePersistencePort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption.JpaConsumptionSlotEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;

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
				new HandleConsumptionFailureInput(claim.slotId(), claim.claimId(), failure("PERMANENT"))));

		assertEquals(0, observedCount("lot3_business_effects"));
		assertEquals(Optional.of(TerminalOutcome.FAILED), lifecycle.findSlot(claim.slotId()).orElseThrow().terminalOutcome());
		assertEquals(Optional.of(new TerminalReason("PERMANENT")),
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
		return new ProcessingFailure(category, category + " failure", clock.instant());
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
		public boolean tryTerminalize(
				UUID slotId, ClaimId claimId, TerminalOutcome outcome,
				Optional<TerminalReason> reason, Instant doneAt) {
			reached.countDown();
			await(proceed);
			return delegate.tryTerminalize(slotId, claimId, outcome, reason, doneAt);
		}

		@Override
		public com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult acquire(
				ConsumptionKey key, ClaimId claimId, WorkerId workerId, ClaimLease lease, Instant now) {
			return delegate.acquire(key, claimId, workerId, lease, now);
		}

		@Override
		public FencedMutationResult handleFailure(
				UUID slotId, ClaimId claimId, ProcessingFailure failure,
				com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision decision,
				Instant now) {
			return delegate.handleFailure(slotId, claimId, failure, decision, now);
		}

		@Override
		public com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult abandon(
				UUID slotId, TerminalReason reason, Instant now) {
			return delegate.abandon(slotId, reason, now);
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
