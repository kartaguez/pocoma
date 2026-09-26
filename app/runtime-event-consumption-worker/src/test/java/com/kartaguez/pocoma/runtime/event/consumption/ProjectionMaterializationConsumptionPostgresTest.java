package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.ConsumptionSlot;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.event.EventType;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.FencedMutationResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.event.ProjectionMaterializationCandidate;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTask;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskCandidate;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskStorePort;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.FinalizeConsumptionService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalFinalizeConsumptionUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event.JdbcProjectionMaterializationDiscoveryAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JdbcProjectionTaskStoreAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.entity.consumption.JpaConsumptionSlotEntity;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionKeys;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionService;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionSource;
import com.kartaguez.pocoma.orchestrator.consumption.AcquireThenFinalizeConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;

@SpringBootTest(classes = ProjectionMaterializationConsumptionPostgresTest.TestApplication.class, properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
		"spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
class ProjectionMaterializationConsumptionPostgresTest {
	private static final EventType POT_CREATED = new EventType("POT_CREATED");
	private static final ProjectionType READ_POT = new ProjectionType("READ_POT");
	private static final ProjectionType POT_BALANCES = new ProjectionType("POT_BALANCES");
	private static final Instant NOW = Instant.parse("2026-09-26T10:00:00Z");
	private static final ClaimLease LEASE = new ClaimLease(Duration.ofSeconds(30));
	private static final String UNPARSEABLE_PAYLOAD = "not JSON and not a serialized BusinessEvent";

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void databaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired private JdbcProjectionMaterializationDiscoveryAdapter discovery;
	@Autowired private JdbcProjectionTaskStoreAdapter tasks;

	private MutableClock clock;
	private TransactionRunner transactions;
	private AcquireConsumptionUseCase acquire;
	private FinalizeConsumptionUseCase finalizeConsumption;

	@BeforeEach
	void setUp() {
		jdbc.execute("truncate table projection_tasks, consumption_inputs, consumption_results, "
				+ "consumption_slots, consumption_claims, tasks_4_pipeline, business_event_outbox cascade");
		clock = new MutableClock(NOW);
		transactions = new SpringTransactionRunner(new TransactionTemplate(transactionManager));
		acquire = new TransactionalAcquireConsumptionUseCase(
				new AcquireConsumptionService(lifecycle, clock), transactions);
		finalizeConsumption = new TransactionalFinalizeConsumptionUseCase(
				new FinalizeConsumptionService(lifecycle, clock), transactions);
	}

	@Test
	void metadataOnlyHappyPathCommitsTaskClaimAndSlotAsOneTransaction() throws Exception {
		UUID eventId = uuid(1); UUID potId = uuid(101);
		insertEvent(eventId, potId, 7, NOW, UNPARSEABLE_PAYLOAD);
		ProjectionMaterializationCandidate candidate = candidate(READ_POT);
		Claim claim = acquired(acquire.acquire(input(candidate, "worker-a")));
		var effectStarted = new CountDownLatch(1);
		var allowCommit = new CountDownLatch(1);
		var blockingTasks = new AfterEnsureBlockingStore(tasks, effectStarted, allowCommit);
		var service = new ProjectionMaterializationConsumptionService(finalizeConsumption, blockingTasks);

		try (var executor = Executors.newSingleThreadExecutor()) {
			var result = executor.submit(() -> service.finalize(candidate, claim));
			assertTrue(effectStarted.await(10, TimeUnit.SECONDS));

			assertEquals(0, count("select count(*) from projection_tasks"));
			assertEquals("PENDING", jdbc.queryForObject(
					"select status from consumption_slots where slot_id=?", String.class, claim.slotId()));
			assertEquals(0, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));

			allowCommit.countDown();
			assertEquals(FencedMutationResult.APPLIED, result.get(10, TimeUnit.SECONDS));
		}

		assertEquals(1, count("select count(*) from projection_tasks"));
		assertSuccessful(claim);
	}

	@Test
	void twoWorkersNeverInvokeTheEffectConcurrentlyForTheSameCandidate() throws Exception {
		insertEvent(uuid(2), uuid(102), 8, NOW, UNPARSEABLE_PAYLOAD);
		var concurrent = new ConcurrentEffectStore(tasks);
		ConsumptionOrchestrator first = orchestrator(routes(READ_POT), concurrent);
		ConsumptionOrchestrator second = orchestrator(routes(READ_POT), concurrent);

		try (var executor = Executors.newFixedThreadPool(2)) {
			var a = executor.submit(() -> first.run(orchestrationInput("worker-a", 10, 2)));
			assertTrue(concurrent.entered.await(10, TimeUnit.SECONDS));
			var b = executor.submit(() -> second.run(orchestrationInput("worker-b", 10, 2)));
			concurrent.release.countDown();
			a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
		}

		assertEquals(1, concurrent.maxConcurrent.get());
		assertEquals(1, concurrent.calls.get());
		assertEquals(1, count("select count(*) from projection_tasks"));
	}

	@Test
	void expiredCurrentClaimMayFinalizeButTakeoverFencesTheOldClaimBeforeEnsure() {
		insertEvent(uuid(3), uuid(103), 9, NOW, UNPARSEABLE_PAYLOAD);
		ProjectionMaterializationCandidate candidate = candidate(READ_POT);
		Claim expiring = acquired(acquire.acquire(input(candidate, "worker-a")));
		clock.set(NOW.plusSeconds(30));

		assertEquals(FencedMutationResult.APPLIED,
				new ProjectionMaterializationConsumptionService(finalizeConsumption, tasks)
						.finalize(candidate, expiring));
		assertSuccessful(expiring);

		jdbc.execute("truncate table projection_tasks, consumption_slots, consumption_claims cascade");
		Claim stale = acquired(acquire.acquire(input(candidate, "worker-a")));
		clock.set(NOW.plusSeconds(60));
		Claim winner = acquired(acquire.acquire(input(candidate, "worker-b")));
		var counting = new CountingStore(tasks);

		assertEquals(FencedMutationResult.LOST_CLAIM,
				new ProjectionMaterializationConsumptionService(finalizeConsumption, counting)
						.finalize(candidate, stale));
		assertEquals(0, counting.calls.get());
		assertEquals(Optional.of(winner.claimId()), lifecycle.findSlot(winner.slotId()).orElseThrow().currentClaimId());
		assertEquals(Optional.of(ClaimEndReason.TAKEN_OVER),
				lifecycle.findClaim(stale.claimId()).orElseThrow().endReason());
	}

	@Test
	void ensureFailureRollsBackAndSurfacesAsRuntimeFailureWithoutTerminalFailure() {
		insertEvent(uuid(4), uuid(104), 10, NOW, UNPARSEABLE_PAYLOAD);
		ProjectionTaskStorePort failing = new ThrowingStore(new IllegalStateException("ensure failed"));

		var result = assertInstanceOf(ConsumptionOrchestrationResult.RuntimeFailure.class,
				orchestrator(routes(READ_POT), failing).run(orchestrationInput("worker", 10, 2)));

		assertEquals("ensure failed", result.cause().getMessage());
		assertEquals(0, count("select count(*) from projection_tasks"));
		ConsumptionSlot slot = lifecycle.findSlot(key(uuid(4), READ_POT)).orElseThrow();
		assertEquals(ConsumptionStatus.PENDING, slot.status());
		assertTrue(slot.terminalOutcome().isEmpty());
		Claim claim = lifecycle.findClaim(slot.currentClaimId().orElseThrow()).orElseThrow();
		assertTrue(claim.isOpen());
		assertEquals(1, claim.attemptNumber());
	}

	@Test
	void failureAfterEnsureBeforeCommitLeavesNoOrphanTask() {
		insertEvent(uuid(5), uuid(105), 11, NOW, UNPARSEABLE_PAYLOAD);
		ProjectionTaskStorePort failingAfterWrite = new AfterEnsureThrowingStore(
				tasks, new IllegalStateException("after ensure"));

		assertInstanceOf(ConsumptionOrchestrationResult.RuntimeFailure.class,
				orchestrator(routes(READ_POT), failingAfterWrite)
						.run(orchestrationInput("worker", 10, 2)));

		assertEquals(0, count("select count(*) from projection_tasks"));
		ConsumptionSlot slot = lifecycle.findSlot(key(uuid(5), READ_POT)).orElseThrow();
		assertEquals(ConsumptionStatus.PENDING, slot.status());
		assertTrue(lifecycle.findClaim(slot.currentClaimId().orElseThrow()).orElseThrow().isOpen());
	}

	@Test
	void technicalFailureRemainsBusyThenTakeoverReplaysToSuccess() {
		UUID eventId = uuid(6);
		insertEvent(eventId, uuid(106), 12, NOW, UNPARSEABLE_PAYLOAD);
		var switchable = new FailOnceStore(tasks);
		var orchestrator = orchestrator(routes(READ_POT), switchable);

		assertInstanceOf(ConsumptionOrchestrationResult.RuntimeFailure.class,
				orchestrator.run(orchestrationInput("worker-a", 10, 2)));
		var beforeExpiry = assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				orchestrator.run(orchestrationInput("worker-b", 10, 2)));
		assertEquals(Optional.of(NOW.plusSeconds(30)), beforeExpiry.nextKnownEligibility());
		assertEquals(0, count("select count(*) from projection_tasks"));

		clock.set(NOW.plusSeconds(30));
		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				orchestrator.run(orchestrationInput("worker-b", 10, 2)));

		assertEquals(1, count("select count(*) from projection_tasks"));
		ConsumptionSlot slot = lifecycle.findSlot(key(eventId, READ_POT)).orElseThrow();
		assertEquals(Optional.of(TerminalOutcome.SUCCESS), slot.terminalOutcome());
		assertEquals(2, lifecycle.findClaims(slot.slotId()).size());
	}

	@Test
	void consequencesAreIndependentWhileTasksConvergeByProjectionKey() {
		UUID eventA = uuid(7); UUID eventB = uuid(8); UUID sharedPot = uuid(107);
		insertEvent(eventA, sharedPot, 13, NOW, UNPARSEABLE_PAYLOAD);
		insertEvent(eventB, sharedPot, 13, NOW.plusSeconds(1), UNPARSEABLE_PAYLOAD);

		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				orchestrator(routes(READ_POT, POT_BALANCES), tasks)
						.run(orchestrationInput("worker", 20, 10)));

		assertEquals(4, count("select count(*) from consumption_slots"));
		assertEquals(4, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));
		assertEquals(4, count("select count(*) from consumption_slots where status='DONE' "
				+ "and terminal_outcome='SUCCESS'"));
		assertEquals(2, count("select count(*) from projection_tasks"));
		assertEquals(1, count("select count(*) from projection_tasks where projection_type='READ_POT'"));
		assertEquals(1, count("select count(*) from projection_tasks where projection_type='POT_BALANCES'"));
		assertTrue(lifecycle.findSlot(key(eventA, READ_POT)).isPresent());
		assertTrue(lifecycle.findSlot(key(eventA, POT_BALANCES)).isPresent());
		assertTrue(lifecycle.findSlot(key(eventB, READ_POT)).isPresent());
		assertTrue(lifecycle.findSlot(key(eventB, POT_BALANCES)).isPresent());
	}

	@Test
	void alreadyDoneIsNotRediscoveredAndDoesNotRepeatEnsure() {
		insertEvent(uuid(9), uuid(109), 14, NOW, UNPARSEABLE_PAYLOAD);
		var counting = new CountingStore(tasks);
		var orchestrator = orchestrator(routes(READ_POT), counting);

		orchestrator.run(orchestrationInput("worker-a", 10, 2));
		orchestrator.run(orchestrationInput("worker-b", 10, 2));

		assertEquals(1, counting.calls.get());
		assertEquals(1, count("select count(*) from projection_tasks"));
	}

	private ConsumptionOrchestrator orchestrator(
			Map<EventType, Set<ProjectionType>> routes, ProjectionTaskStorePort store) {
		var source = new ProjectionMaterializationConsumptionSource(routes, WorkerSegment.single(), discovery);
		var service = new ProjectionMaterializationConsumptionService(finalizeConsumption, store);
		return new AcquireThenFinalizeConsumptionOrchestrator<>(source,
				ProjectionMaterializationConsumptionKeys::consumptionKey, acquire, service);
	}

	private ProjectionMaterializationCandidate candidate(ProjectionType projectionType) {
		return discovery.findCandidates(routes(projectionType), WorkerSegment.single(), Optional.empty(), 10)
				.stream().filter(value -> value.projectionType().equals(projectionType)).findFirst().orElseThrow();
	}

	private AcquireConsumptionInput input(ProjectionMaterializationCandidate candidate, String worker) {
		return new AcquireConsumptionInput(ProjectionMaterializationConsumptionKeys.consumptionKey(candidate),
				new WorkerId(worker), LEASE);
	}

	private void assertSuccessful(Claim claim) {
		assertEquals(Optional.of(TerminalOutcome.SUCCESS),
				lifecycle.findSlot(claim.slotId()).orElseThrow().terminalOutcome());
		assertEquals(Optional.of(ClaimEndReason.SUCCESS),
				lifecycle.findClaim(claim.claimId()).orElseThrow().endReason());
	}

	private void insertEvent(UUID eventId, UUID potId, long version, Instant createdAt, String payload) {
		jdbc.update("""
				insert into business_event_outbox (
				  id, event_type, pot_id, pot_partition_hash, aggregate_id, version, payload_json,
				  status, attempt_count, created_at
				) values (?, ?, ?, 0, ?, ?, ?, 'PENDING', 0, ?)
				""", eventId, POT_CREATED.value(), potId, potId, version, payload,
				java.sql.Timestamp.from(createdAt));
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private static Map<EventType, Set<ProjectionType>> routes(ProjectionType... projectionTypes) {
		return Map.of(POT_CREATED, Set.of(projectionTypes));
	}

	private static ConsumptionKey key(UUID eventId, ProjectionType projectionType) {
		return ProjectionMaterializationConsumptionKeys.consumptionKey(
				new ProjectionMaterializationCandidate(eventId, POT_CREATED, projectionType,
						new com.kartaguez.pocoma.domain.projection.TargetObjectType("POT"),
						new com.kartaguez.pocoma.domain.projection.TargetObjectId(uuid(999).toString()), 1, NOW));
	}

	private static ConsumptionOrchestrationInput orchestrationInput(
			String worker, int candidates, int executions) {
		return new ConsumptionOrchestrationInput(new WorkerId(worker), LEASE,
				new ConsumptionOrchestrationBudget(candidates, executions));
	}

	private static Claim acquired(AcquireResult result) {
		return assertInstanceOf(AcquireResult.Acquired.class, result).claim();
	}

	private static UUID uuid(long suffix) {
		return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
	}

	private static class DelegatingStore implements ProjectionTaskStorePort {
		protected final ProjectionTaskStorePort delegate;
		private DelegatingStore(ProjectionTaskStorePort delegate) { this.delegate = delegate; }
		@Override public ProjectionTask ensure(ProjectionKey key, Instant createdAt) {
			return delegate.ensure(key, createdAt);
		}
		@Override public List<ProjectionTaskCandidate> findCandidates(Set<ProjectionType> projectionTypes,
				int segmentIndex, int segmentCount, Optional<Instant> afterCreatedAt,
				Optional<UUID> afterRowId, int limit) {
			return delegate.findCandidates(projectionTypes, segmentIndex, segmentCount, afterCreatedAt, afterRowId, limit);
		}
	}

	private static final class CountingStore extends DelegatingStore {
		private final AtomicInteger calls = new AtomicInteger();
		private CountingStore(ProjectionTaskStorePort delegate) { super(delegate); }
		@Override public ProjectionTask ensure(ProjectionKey key, Instant createdAt) {
			calls.incrementAndGet(); return super.ensure(key, createdAt);
		}
	}

	private static final class AfterEnsureBlockingStore extends DelegatingStore {
		private final CountDownLatch entered;
		private final CountDownLatch release;
		private AfterEnsureBlockingStore(ProjectionTaskStorePort delegate, CountDownLatch entered, CountDownLatch release) {
			super(delegate); this.entered = entered; this.release = release;
		}
		@Override public ProjectionTask ensure(ProjectionKey key, Instant createdAt) {
			ProjectionTask task = super.ensure(key, createdAt); entered.countDown(); await(release); return task;
		}
	}

	private static final class ConcurrentEffectStore extends DelegatingStore {
		private final CountDownLatch entered = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final AtomicInteger calls = new AtomicInteger();
		private final AtomicInteger concurrent = new AtomicInteger();
		private final AtomicInteger maxConcurrent = new AtomicInteger();
		private ConcurrentEffectStore(ProjectionTaskStorePort delegate) { super(delegate); }
		@Override public ProjectionTask ensure(ProjectionKey key, Instant createdAt) {
			calls.incrementAndGet(); int active = concurrent.incrementAndGet();
			maxConcurrent.accumulateAndGet(active, Math::max); entered.countDown(); await(release);
			try { return super.ensure(key, createdAt); } finally { concurrent.decrementAndGet(); }
		}
	}

	private static final class ThrowingStore implements ProjectionTaskStorePort {
		private final RuntimeException failure;
		private ThrowingStore(RuntimeException failure) { this.failure = failure; }
		@Override public ProjectionTask ensure(ProjectionKey key, Instant createdAt) { throw failure; }
		@Override public List<ProjectionTaskCandidate> findCandidates(Set<ProjectionType> projectionTypes,
				int segmentIndex, int segmentCount, Optional<Instant> afterCreatedAt,
				Optional<UUID> afterRowId, int limit) { throw new UnsupportedOperationException(); }
	}

	private static final class AfterEnsureThrowingStore extends DelegatingStore {
		private final RuntimeException failure;
		private AfterEnsureThrowingStore(ProjectionTaskStorePort delegate, RuntimeException failure) {
			super(delegate); this.failure = failure;
		}
		@Override public ProjectionTask ensure(ProjectionKey key, Instant createdAt) {
			super.ensure(key, createdAt); throw failure;
		}
	}

	private static final class FailOnceStore extends DelegatingStore {
		private final AtomicBoolean fail = new AtomicBoolean(true);
		private FailOnceStore(ProjectionTaskStorePort delegate) { super(delegate); }
		@Override public ProjectionTask ensure(ProjectionKey key, Instant createdAt) {
			if (fail.compareAndSet(true, false)) throw new IllegalStateException("first attempt");
			return super.ensure(key, createdAt);
		}
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt(); throw new IllegalStateException(exception);
		}
	}

	private static final class MutableClock extends Clock {
		private final AtomicReference<Instant> instant;
		private MutableClock(Instant value) { instant = new AtomicReference<>(value); }
		void set(Instant value) { instant.set(value); }
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return instant.get(); }
	}

	@Configuration(proxyBeanMethods = false)
	@EnableAutoConfiguration
	@EntityScan(basePackageClasses = JpaConsumptionSlotEntity.class)
	@EnableJpaRepositories(basePackageClasses = JpaConsumptionSlotRepository.class)
	@Import({JpaConsumptionLifecycleAdapter.class, JdbcProjectionMaterializationDiscoveryAdapter.class,
			JdbcProjectionTaskStoreAdapter.class})
	static class TestApplication {
		@Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
	}
}
