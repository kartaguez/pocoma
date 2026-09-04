package com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

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
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.Fail;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision.RetryAfter;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult.Abandoned;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.Acquired;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.AlreadyDone;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.Busy;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult.NotReady;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption.JpaConsumptionSlotEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
		"spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class JpaConsumptionLifecycleAdapterPostgresTest {

	private static final Instant NOW = Instant.parse("2026-08-31T08:00:00Z");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final TerminalReason REJECTION_REASON = new TerminalReason("VERSION_CONFLICT");
	private static final TerminalReason ABANDON_REASON = new TerminalReason("SUPERSEDED");

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

	private ExecutorService executor;

	@BeforeEach
	void cleanDatabase() {
		jdbc.update("delete from consumption_inputs");
		jdbc.update("delete from consumption_results");
		jdbc.update("update consumption_slots set current_claim_id = null");
		jdbc.update("delete from consumption_claims");
		jdbc.update("delete from consumption_slots");
		executor = Executors.newFixedThreadPool(2);
	}

	@AfterEach
	void shutdownExecutor() {
		executor.shutdownNow();
	}

	@Test
	void concurrentLazyCreationProducesOneSlot() throws Exception {
		ConsumptionKey key = key("EVENT", "event-1", "PIPELINE", "balance", "v1");
		CountDownLatch start = new CountDownLatch(1);

		Future<AcquireResult> first = concurrentAcquire(start, key, "worker-1", NOW);
		Future<AcquireResult> second = concurrentAcquire(start, key, "worker-2", NOW);
		start.countDown();

		List<AcquireResult> results = List.of(first.get(), second.get());
		assertEquals(1, results.stream().filter(Acquired.class::isInstance).count());
		assertEquals(1, results.stream().filter(Busy.class::isInstance).count());
		assertEquals(1, jdbc.queryForObject("select count(*) from consumption_slots", Integer.class));
		assertEquals(1, jdbc.queryForObject("select count(*) from consumption_claims", Integer.class));
		ConsumptionSlot slot = lifecycle.findSlot(key).orElseThrow();
		assertEquals(slot.createdAt(), slot.nextClaimAt());
	}

	@Test
	void structuralConsumersOfTheSameConsumableRemainIndependent() {
		ConsumptionKey balances = key("EVENT", "event-1", "PIPELINE", "balance", "v1");
		ConsumptionKey notifications = key("EVENT", "event-1", "PIPELINE", "notification", "v1");

		Claim first = acquired(inTransaction(() -> acquire(balances, "worker-1", NOW)));
		Claim second = acquired(inTransaction(() -> acquire(notifications, "worker-2", NOW)));

		assertNotEquals(first.slotId(), second.slotId());
		assertEquals(2, jdbc.queryForObject("select count(*) from consumption_slots", Integer.class));
	}

	@Test
	void expiredLeaseKeepsAuthorityUntilTakeover() {
		ConsumptionKey key = key("TASK", "task-1", "TASK_EXECUTOR");
		Claim original = acquired(inTransaction(() -> acquire(key, "worker-1", NOW)));

		assertTrue(inTransaction(() -> lifecycle.tryTerminalize(
				original.slotId(), original.claimId(), TerminalOutcome.SUCCESS,
				Optional.empty(), NOW.plusSeconds(31))));

		Claim ended = lifecycle.findClaim(original.claimId()).orElseThrow();
		assertEquals(Optional.of(ClaimEndReason.SUCCESS), ended.endReason());
		assertTrue(ended.invalidatedAt().isEmpty());
	}

	@Test
	void takeoverIsAtomicAndFencesThePreviousClaim() {
		ConsumptionKey key = key("TASK", "task-2", "TASK_EXECUTOR");
		Claim first = acquired(inTransaction(() -> acquire(key, "worker-1", NOW)));
		Claim second = acquired(inTransaction(() -> acquire(key, "worker-2", NOW.plusSeconds(30))));

		assertEquals(1, first.attemptNumber());
		assertEquals(2, second.attemptNumber());
		assertEquals(Optional.of(ClaimEndReason.TAKEN_OVER),
				lifecycle.findClaim(first.claimId()).orElseThrow().endReason());
		assertEquals(Optional.of(second.claimId()), lifecycle.findSlot(key).orElseThrow().currentClaimId());
		assertFalse(inTransaction(() -> lifecycle.tryTerminalize(
				first.slotId(), first.claimId(), TerminalOutcome.SUCCESS,
				Optional.empty(), NOW.plusSeconds(31))));
		assertTrue(inTransaction(() -> lifecycle.tryTerminalize(
				second.slotId(), second.claimId(), TerminalOutcome.SUCCESS,
				Optional.empty(), NOW.plusSeconds(31))));
	}

	@Test
	void attemptNumbersAreAllocatedUnderTheSlotLockAcrossRetriesAndTakeovers() {
		ConsumptionKey key = key("COMMAND", "command-1", "COMMAND_PROCESSOR");
		Claim first = acquired(inTransaction(() -> acquire(key, "worker-1", NOW)));
		assertEquals(FencedMutationResult.APPLIED, inTransaction(() -> lifecycle.handleFailure(
				first.slotId(), first.claimId(), failure("temporary"),
				new RetryAfter(Duration.ofSeconds(1)), NOW)));

		assertInstanceOf(NotReady.class, inTransaction(() -> acquire(key, "too-early", NOW.plusMillis(999))));
		Claim second = acquired(inTransaction(() -> acquire(key, "worker-2", NOW.plusSeconds(1))));
		Claim third = acquired(inTransaction(() -> acquire(key, "worker-3", NOW.plusSeconds(31))));

		assertEquals(List.of(1, 2, 3), lifecycle.findClaims(first.slotId()).stream()
				.map(Claim::attemptNumber).toList());
		assertEquals(Optional.of(ClaimEndReason.TAKEN_OVER),
				lifecycle.findClaim(second.claimId()).orElseThrow().endReason());
		assertEquals(3, third.attemptNumber());
	}

	@Test
	void retryAndTerminalFailureApplyOnlyForTheCurrentClaim() {
		ConsumptionKey retryKey = key("TASK", "retry", "TASK_EXECUTOR");
		Claim retryClaim = acquired(inTransaction(() -> acquire(retryKey, "worker", NOW)));
		ProcessingFailure deadlock = failure("DEADLOCK", "TRANSIENT");
		assertEquals(FencedMutationResult.APPLIED, inTransaction(() -> lifecycle.handleFailure(
				retryClaim.slotId(), retryClaim.claimId(), deadlock,
				new RetryAfter(Duration.ofSeconds(5)), NOW)));
		ConsumptionSlot pending = lifecycle.findSlot(retryKey).orElseThrow();
		assertEquals(ConsumptionStatus.PENDING, pending.status());
		assertTrue(pending.currentClaimId().isEmpty());
		assertEquals(NOW.plusSeconds(5), pending.nextClaimAt());
		assertEquals(Optional.empty(), pending.terminalOutcome());
		assertEquals(Optional.empty(), pending.terminalReason());
		assertEquals(new ProcessingFailureCode("DEADLOCK"),
				lifecycle.findClaim(retryClaim.claimId()).orElseThrow().failure().orElseThrow().code());

		Claim finalDeadlockClaim = acquired(inTransaction(() -> acquire(retryKey, "worker-2", NOW.plusSeconds(5))));
		assertEquals(FencedMutationResult.APPLIED, inTransaction(() -> lifecycle.handleFailure(
				finalDeadlockClaim.slotId(), finalDeadlockClaim.claimId(), deadlock, new Fail(), NOW.plusSeconds(5))));
		ConsumptionSlot deadlockFailed = lifecycle.findSlot(retryKey).orElseThrow();
		assertEquals(Optional.of(TerminalOutcome.FAILED), deadlockFailed.terminalOutcome());
		assertEquals(Optional.of(new TerminalReason("DEADLOCK")), deadlockFailed.terminalReason());

		ConsumptionKey failedKey = key("TASK", "failed", "TASK_EXECUTOR");
		Claim failedClaim = acquired(inTransaction(() -> acquire(failedKey, "worker", NOW)));
		assertEquals(FencedMutationResult.APPLIED, inTransaction(() -> lifecycle.handleFailure(
				failedClaim.slotId(), failedClaim.claimId(),
				failure("DATABASE_UNAVAILABLE", "TRANSIENT"), new Fail(), NOW)));
		ConsumptionSlot failed = lifecycle.findSlot(failedKey).orElseThrow();
		assertEquals(ConsumptionStatus.DONE, failed.status());
		assertEquals(Optional.of(TerminalOutcome.FAILED), failed.terminalOutcome());
		assertEquals(Optional.of(new TerminalReason("DATABASE_UNAVAILABLE")), failed.terminalReason());
		AlreadyDone alreadyDone = assertInstanceOf(AlreadyDone.class,
				inTransaction(() -> acquire(failedKey, "other", NOW.plusSeconds(60))));
		assertEquals(TerminalOutcome.FAILED, alreadyDone.outcome());
		assertEquals(Optional.of(new TerminalReason("DATABASE_UNAVAILABLE")), alreadyDone.reason());
	}

	@Test
	void staleFailureDoesNotMutateTheWinningClaim() {
		ConsumptionKey key = key("TASK", "stale", "TASK_EXECUTOR");
		Claim stale = acquired(inTransaction(() -> acquire(key, "old", NOW)));
		Claim winner = acquired(inTransaction(() -> acquire(key, "new", NOW.plusSeconds(30))));

		assertEquals(FencedMutationResult.LOST_CLAIM, inTransaction(() -> lifecycle.handleFailure(
				stale.slotId(), stale.claimId(), failure("late"), new Fail(), NOW.plusSeconds(31))));
		assertEquals(Optional.of(winner.claimId()), lifecycle.findSlot(key).orElseThrow().currentClaimId());
		assertTrue(lifecycle.findClaim(winner.claimId()).orElseThrow().isOpen());
	}

	@Test
	void abandonInvalidatesTheCurrentClaimAndIsIdempotent() {
		ConsumptionKey key = key("TASK", "abandon", "TASK_EXECUTOR");
		Claim claim = acquired(inTransaction(() -> acquire(key, "worker", NOW)));

		assertInstanceOf(Abandoned.class,
				inTransaction(() -> lifecycle.abandon(claim.slotId(), ABANDON_REASON, NOW.plusSeconds(1))));
		ConsumptionSlot slot = lifecycle.findSlot(key).orElseThrow();
		assertEquals(Optional.of(TerminalOutcome.ABANDONED), slot.terminalOutcome());
		assertEquals(Optional.of(ABANDON_REASON), slot.terminalReason());
		assertTrue(slot.currentClaimId().isEmpty());
		Claim invalidated = lifecycle.findClaim(claim.claimId()).orElseThrow();
		assertEquals(Optional.of(ClaimEndReason.ABANDONED), invalidated.endReason());
		assertFalse(inTransaction(() -> lifecycle.tryTerminalize(
				claim.slotId(), claim.claimId(), TerminalOutcome.SUCCESS,
				Optional.empty(), NOW.plusSeconds(2))));
		assertInstanceOf(com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult.AlreadyDone.class,
				inTransaction(() -> lifecycle.abandon(claim.slotId(), ABANDON_REASON, NOW.plusSeconds(2))));
	}

	@Test
	void concurrentAbandonAndTakeoverAlwaysEndAbandonedWithoutCurrentClaim() throws Exception {
		ConsumptionKey key = key("TASK", "abandon-race", "TASK_EXECUTOR");
		Claim original = acquired(inTransaction(() -> acquire(key, "old", NOW)));
		CountDownLatch start = new CountDownLatch(1);
		Future<AcquireResult> takeover = executor.submit(() -> {
			start.await();
			return inTransaction(() -> acquire(key, "new", NOW.plusSeconds(30)));
		});
		Future<?> abandon = executor.submit(() -> {
			start.await();
			return inTransaction(() -> lifecycle.abandon(
					original.slotId(), ABANDON_REASON, NOW.plusSeconds(30)));
		});

		start.countDown();
		AcquireResult takeoverResult = takeover.get();
		abandon.get();

		ConsumptionSlot slot = lifecycle.findSlot(key).orElseThrow();
		assertEquals(Optional.of(TerminalOutcome.ABANDONED), slot.terminalOutcome());
		assertEquals(Optional.of(ABANDON_REASON), slot.terminalReason());
		assertTrue(slot.currentClaimId().isEmpty());
		if (takeoverResult instanceof Acquired acquired) {
			assertEquals(Optional.of(ClaimEndReason.ABANDONED),
					lifecycle.findClaim(acquired.claim().claimId()).orElseThrow().endReason());
		} else {
			assertEquals(TerminalOutcome.ABANDONED,
					assertInstanceOf(AlreadyDone.class, takeoverResult).outcome());
		}
	}

	@Test
	void concurrentAbandonAndTerminalizationHaveOneCoherentWinner() throws Exception {
		ConsumptionKey key = key("TASK", "abandon-terminalize-race", "TASK_EXECUTOR");
		Claim claim = acquired(inTransaction(() -> acquire(key, "worker", NOW)));
		CountDownLatch start = new CountDownLatch(1);
		Future<Boolean> terminalization = executor.submit(() -> {
			start.await();
			return inTransaction(() -> lifecycle.tryTerminalize(
					claim.slotId(), claim.claimId(), TerminalOutcome.SUCCESS,
					Optional.empty(), NOW.plusSeconds(1)));
		});
		Future<com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult> abandonment =
				executor.submit(() -> {
					start.await();
					return inTransaction(() -> lifecycle.abandon(
							claim.slotId(), ABANDON_REASON, NOW.plusSeconds(1)));
				});

		start.countDown();
		boolean terminalized = terminalization.get();
		var abandonResult = abandonment.get();

		ConsumptionSlot slot = lifecycle.findSlot(key).orElseThrow();
		assertTrue(slot.currentClaimId().isEmpty());
		if (terminalized) {
			assertEquals(Optional.of(TerminalOutcome.SUCCESS), slot.terminalOutcome());
			assertEquals(Optional.empty(), slot.terminalReason());
			assertInstanceOf(
					com.kartaguez.pocoma.engine.port.in.consumption.result.AbandonResult.AlreadyDone.class,
					abandonResult);
			assertEquals(Optional.of(ClaimEndReason.SUCCESS),
					lifecycle.findClaim(claim.claimId()).orElseThrow().endReason());
		} else {
			assertInstanceOf(Abandoned.class, abandonResult);
			assertEquals(Optional.of(TerminalOutcome.ABANDONED), slot.terminalOutcome());
			assertEquals(Optional.of(ABANDON_REASON), slot.terminalReason());
			assertEquals(Optional.of(ClaimEndReason.ABANDONED),
					lifecycle.findClaim(claim.claimId()).orElseThrow().endReason());
		}
	}

	@Test
	void alreadyDoneReturnsTheExactTerminalOutcome() {
		for (TerminalOutcome outcome : TerminalOutcome.values()) {
			ConsumptionKey key = key("TASK", "done-" + outcome, "TASK_EXECUTOR");
			Claim claim = acquired(inTransaction(() -> acquire(key, "worker", NOW)));
			switch (outcome) {
			case SUCCESS, REJECTED -> assertTrue(inTransaction(() -> lifecycle.tryTerminalize(
						claim.slotId(), claim.claimId(), outcome,
						outcome == TerminalOutcome.SUCCESS ? Optional.empty() : Optional.of(REJECTION_REASON),
						NOW.plusSeconds(1))));
				case FAILED -> assertEquals(FencedMutationResult.APPLIED,
						inTransaction(() -> lifecycle.handleFailure(
								claim.slotId(), claim.claimId(), failure("permanent"), new Fail(), NOW.plusSeconds(1))));
			case ABANDONED -> assertInstanceOf(Abandoned.class,
						inTransaction(() -> lifecycle.abandon(
								claim.slotId(), ABANDON_REASON, NOW.plusSeconds(1))));
			}
			AlreadyDone result = assertInstanceOf(AlreadyDone.class,
					inTransaction(() -> acquire(key, "other", NOW.plusSeconds(60))));
			assertEquals(outcome, result.outcome());
			assertEquals(expectedReason(outcome), result.reason());
		}
	}

	@Test
	void provenanceJoinsTheCallingTransactionAndRollsBack() {
		ConsumptionKey key = key("COMMAND", "provenance", "COMMAND_PROCESSOR");
		Claim claim = acquired(inTransaction(() -> acquire(key, "worker", NOW)));
		ConsumptionInput input = new ConsumptionInput(claim.slotId(), "POT", "pot-1", 7);
		ConsumptionResult result = new ConsumptionResult(
				claim.slotId(), "EVENT", "POT_UPDATED", "event-1", OptionalLong.empty(),
				Optional.of("POT"), Optional.of("pot-1"), OptionalLong.of(8), NOW.plusSeconds(1));

		assertThrows(RollbackMarker.class, () -> inTransaction(() -> {
			provenance.appendInputs(List.of(input));
			provenance.appendResults(List.of(result));
			throw new RollbackMarker();
		}));
		assertTrue(provenance.findInputs(claim.slotId()).isEmpty());
		assertTrue(provenance.findResults(claim.slotId()).isEmpty());

		inTransaction(() -> {
			provenance.appendInputs(List.of(input));
			provenance.appendResults(List.of(result));
			return null;
		});
		assertEquals(List.of(input), provenance.findInputs(claim.slotId()));
		assertEquals(List.of(result), provenance.findResults(claim.slotId()));
	}

	@Test
	void databaseRejectsStructuralAndLifecycleConstraintViolations() {
		ConsumptionKey key = key("TASK", "constraints", "TASK_EXECUTOR");
		Claim claim = acquired(inTransaction(() -> acquire(key, "worker", NOW)));

		assertConstraintViolation("""
				insert into consumption_slots (
				  slot_id, consumable_type, consumable_components, consumer_type, consumer_components,
				  revision, last_attempt_number, status, next_claim_at, created_at
				) select ?, consumable_type, '[  \"constraints\" ]'::jsonb, consumer_type,
				         consumer_components, 0, 0, 'PENDING', ?, ?
				  from consumption_slots where slot_id = ?
				""", UUID.randomUUID(), NOW, NOW, claim.slotId());
		assertConstraintViolation("""
				insert into consumption_claims (
				  claim_id, slot_id, attempt_number, claimed_by, claimed_at, lease_until
				) values (?, ?, 1, 'duplicate', ?, ?)
				""", UUID.randomUUID(), claim.slotId(), NOW, NOW.plusSeconds(1));
		assertConstraintViolation("""
				insert into consumption_claims (
				  claim_id, slot_id, attempt_number, claimed_by, claimed_at, lease_until
				) values (?, ?, 2, 'invalid-lease', ?, ?)
				""", UUID.randomUUID(), claim.slotId(), NOW, NOW);
		assertConstraintViolation("""
				update consumption_slots set status='DONE', terminal_outcome=null, done_at=null
				where slot_id=?
				""", claim.slotId());
		assertConstraintViolation("""
				update consumption_slots
				set status='DONE', terminal_outcome='REJECTED', terminal_reason=null,
				    current_claim_id=null, done_at=?
				where slot_id=?
				""", NOW, claim.slotId());
		assertConstraintViolation("""
				update consumption_slots
				set status='DONE', terminal_outcome='SUCCESS', terminal_reason='NOT_ALLOWED',
				    current_claim_id=null, done_at=?
				where slot_id=?
				""", NOW, claim.slotId());
		assertConstraintViolation("""
				update consumption_slots
				set status='DONE', terminal_outcome='FAILED', terminal_reason='  ',
				    current_claim_id=null, done_at=?
				where slot_id=?
				""", NOW, claim.slotId());
		assertConstraintViolation("""
				insert into consumption_claims (
				  claim_id, slot_id, attempt_number, claimed_by, claimed_at, lease_until,
				  ended_at, end_reason, failure_category
				) values (?, ?, 2, 'invalid', ?, ?, ?, 'PROCESSING_FAILURE', 'partial')
				""", UUID.randomUUID(), claim.slotId(), NOW, NOW.plusSeconds(1), NOW.plusSeconds(1));
		assertConstraintViolation("""
				insert into consumption_claims (
				  claim_id, slot_id, attempt_number, claimed_by, claimed_at, lease_until,
				  ended_at, end_reason, failure_category, failure_message, failure_occurred_at
				) values (?, ?, 2, 'wrong-reason', ?, ?, ?, 'SUCCESS', 'category', 'message', ?)
				""", UUID.randomUUID(), claim.slotId(), NOW, NOW.plusSeconds(1), NOW.plusSeconds(1), NOW);
		assertConstraintViolation("""
				insert into consumption_inputs (input_id, slot_id, subject_type, subject_id, subject_version)
				values (?, ?, 'POT', 'pot', 0)
				""", UUID.randomUUID(), claim.slotId());
		assertConstraintViolation("""
				insert into consumption_inputs (input_id, slot_id, subject_type, subject_id, subject_version)
				values (?, ?, 'POT', 'pot', 1)
				""", UUID.randomUUID(), UUID.randomUUID());
		assertConstraintViolation("""
				insert into consumption_results (
				  result_id, slot_id, space, object_type, object_id, subject_type, created_at
				) values (?, ?, 'EVENT', 'TYPE', 'id', 'POT', ?)
				""", UUID.randomUUID(), claim.slotId(), NOW);
		assertConstraintViolation("""
				insert into consumption_results (
				  result_id, slot_id, space, object_type, object_id, object_version, created_at
				) values (?, ?, 'EVENT', 'TYPE', 'id', 0, ?)
				""", UUID.randomUUID(), claim.slotId(), NOW);

		jdbc.update("""
				insert into consumption_inputs (input_id, slot_id, subject_type, subject_id, subject_version)
				values (?, ?, 'POT', 'unique-pot', 1)
				""", UUID.randomUUID(), claim.slotId());
		assertConstraintViolation("""
				insert into consumption_inputs (input_id, slot_id, subject_type, subject_id, subject_version)
				values (?, ?, 'POT', 'unique-pot', 1)
				""", UUID.randomUUID(), claim.slotId());
	}

	@Test
	void databaseRejectsCurrentClaimFromAnotherSlot() {
		Claim first = acquired(inTransaction(() -> acquire(
				key("TASK", "first", "TASK_EXECUTOR"), "first", NOW)));
		Claim second = acquired(inTransaction(() -> acquire(
				key("TASK", "second", "TASK_EXECUTOR"), "second", NOW)));

		assertConstraintViolation(
				"update consumption_slots set current_claim_id=? where slot_id=?",
				second.claimId().value(), first.slotId());
	}

	private Future<AcquireResult> concurrentAcquire(
			CountDownLatch start, ConsumptionKey key, String worker, Instant now) {
		return executor.submit(() -> {
			start.await();
			return inTransaction(() -> acquire(key, worker, now));
		});
	}

	private AcquireResult acquire(ConsumptionKey key, String worker, Instant now) {
		return lifecycle.acquire(key, ClaimId.generate(), new WorkerId(worker), LEASE, now);
	}

	private static Claim acquired(AcquireResult result) {
		return assertInstanceOf(Acquired.class, result).claim();
	}

	private <T> T inTransaction(Supplier<T> action) {
		return new TransactionTemplate(transactionManager).execute(status -> action.get());
	}

	private void assertConstraintViolation(String sql, Object... arguments) {
		assertThrows(RuntimeException.class, () -> jdbc.update(sql, arguments));
	}

	private static ProcessingFailure failure(String category) {
		return failure(category, category);
	}

	private static ProcessingFailure failure(String code, String category) {
		return new ProcessingFailure(new ProcessingFailureCode(code), category, code + " failure", NOW);
	}

	private static Optional<TerminalReason> expectedReason(TerminalOutcome outcome) {
		return switch (outcome) {
			case SUCCESS -> Optional.empty();
			case REJECTED -> Optional.of(REJECTION_REASON);
			case FAILED -> Optional.of(new TerminalReason("permanent"));
			case ABANDONED -> Optional.of(ABANDON_REASON);
		};
	}

	private static ConsumptionKey key(
			String consumableType, String consumableComponent, String consumerType, String... consumerComponents) {
		return new ConsumptionKey(
				new ConsumableIdentity(consumableType, List.of(consumableComponent)),
				new ConsumerIdentity(consumerType, List.of(consumerComponents)));
	}

	private static final class RollbackMarker extends RuntimeException {
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
