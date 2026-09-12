package com.kartaguez.pocoma.runtime.latestknownversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimEndReason;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ConsumptionStatus;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecution;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.read.projection.AdvanceLatestKnownVersionInput;
import com.kartaguez.pocoma.engine.read.projection.LatestKnownVersionPersistencePort;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;
import com.kartaguez.pocoma.locator.consumption.latestknownversion.LatestKnownVersionConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;

@SpringBootTest(properties = {
		"pocoma.latest-known-version-consumption.enabled=false",
		"spring.jpa.hibernate.ddl-auto=validate"
})
class LatestKnownVersionRuntimePostgresTest {
	private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
	static { POSTGRES.start(); }

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JpaBusinessEventOutboxAdapter outbox;
	@Autowired private JpaBusinessEventOutboxRepository events;
	@Autowired private JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired private JpaConsumptionProvenanceAdapter provenance;
	@Autowired private ConsumptionOrchestrator orchestrator;
	@Autowired private AcquireConsumptionUseCase acquire;
	@Autowired private ExecuteConsumptionUseCase execute;
	@Autowired private LatestKnownVersionPersistencePort latestKnownVersions;
	@Autowired private LatestKnownVersionConsumptionLocator locator;
	@Autowired private JdbcTemplate jdbc;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, "
				+ "consumption_claims, tasks_4_pipeline, "
				+ "business_event_outbox cascade");
		jdbc.execute("truncate table pocoma_read.source_version_watermarks");
	}

	@Test
	void consumesOutOfOrderDuplicateAndOldEventsWithoutCreatingProjectionWork() {
		PotId potId = PotId.of(UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 3));
		outbox.append(new PotCreatedEvent(potId, 2));
		outbox.append(new PotCreatedEvent(potId, 3));
		outbox.append(new PotCreatedEvent(potId, 1));

		orchestrator.run(input());

		assertEquals(3L, version(potId));
		assertEquals(0L, count("tasks_4_pipeline"));
		assertEquals(0L, countRead("projection_artifacts"));
		assertEquals(0L, countRead("projection_failures"));
		assertEquals(0L, countRead("projection_heads"));
		for (var event : events.findAll()) {
			var slot = lifecycle.findSlot(LatestKnownVersionConsumptionLocator.key(event.id())).orElseThrow();
			assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
			assertEquals(1, provenance.findInputs(slot.slotId()).size());
			assertTrue(provenance.findResults(slot.slotId()).isEmpty());
		}
		assertEquals(4L, jdbc.queryForObject("select count(*) from consumption_slots "
				+ "where consumer_type='SOURCE_VERSION_WATERMARK'", Long.class));
	}

	@Test
	void terminalSlotPreventsANominalSecondApplicationAfterCommit() {
		PotId potId = PotId.of(UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 1));

		orchestrator.run(input());
		Instant firstAdvancedAt = advancedAt(potId);
		long firstInputCount = count("consumption_inputs");
		orchestrator.run(input());

		assertEquals(1L, version(potId));
		assertEquals(firstAdvancedAt, advancedAt(potId));
		assertEquals(firstInputCount, count("consumption_inputs"));
		assertEquals(1L, count("consumption_slots"));
	}

	@Test
	void latestKnownVersionRollsBackWhenExecutionFailsBeforeProvenanceAndTerminalCas() {
		PotId potId = PotId.of(UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 42));
		UUID eventId = events.findAll().getFirst().id();
		var acquired = (AcquireResult.Acquired) acquire.acquire(new AcquireConsumptionInput(
				LatestKnownVersionConsumptionLocator.key(eventId), new WorkerId("rollback-worker"),
				new ClaimLease(java.time.Duration.ofSeconds(30))));

		assertThrows(ExpectedFailure.class, () -> execute.execute(new ExecuteConsumptionInput(
				acquired.claim().slotId(), acquired.claim().claimId(), context -> {
					latestKnownVersions.advanceToAtLeast(new AdvanceLatestKnownVersionInput(potId, 42, Instant.now()));
					throw new ExpectedFailure();
				})));

		assertEquals(0L, jdbc.queryForObject(
				"select count(*) from pocoma_read.source_version_watermarks where pot_id=?", Long.class,
				potId.value()));
		var slot = lifecycle.findSlot(LatestKnownVersionConsumptionLocator.key(eventId)).orElseThrow();
		assertTrue(slot.terminalOutcome().isEmpty());
		assertTrue(provenance.findInputs(slot.slotId()).isEmpty());
		assertTrue(provenance.findResults(slot.slotId()).isEmpty());
	}

	@Test
	void lostClaimRollsBackLatestKnownVersionAndProvenanceBeforeWinnerRetries() throws Exception {
		PotId potId = PotId.of(UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 11));
		var located = locator.openSearch().next().orElseThrow();
		Claim staleClaim = acquired(acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),
				new WorkerId("latest-known-stale"), new ClaimLease(Duration.ofMillis(1)))));
		var updateAttempted = new CountDownLatch(1);
		var allowTerminalCas = new CountDownLatch(1);
		ConsumptionExecution blocked = context -> {
			var result = located.execution().execute(context);
			updateAttempted.countDown();
			try {
				if (!allowTerminalCas.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("CAS was not released");
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted", interrupted);
			}
			return result;
		};

		try (var executor = Executors.newSingleThreadExecutor()) {
			var staleExecution = executor.submit(() -> assertThrows(LostClaimException.class,
					() -> execute.execute(new ExecuteConsumptionInput(
							staleClaim.slotId(), staleClaim.claimId(), blocked))));
			assertTrue(updateAttempted.await(10, TimeUnit.SECONDS));
			while (Instant.now().isBefore(staleClaim.leaseUntil())) Thread.onSpinWait();
			Claim winner = acquired(acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),
					new WorkerId("latest-known-winner"), new ClaimLease(Duration.ofSeconds(30)))));
			allowTerminalCas.countDown();
			staleExecution.get(10, TimeUnit.SECONDS);

			assertEquals(0L, jdbc.queryForObject(
					"select count(*) from pocoma_read.source_version_watermarks where pot_id=?", Long.class,
					potId.value()));
			assertEquals(List.of(), provenance.findInputs(staleClaim.slotId()));
			assertEquals(List.of(), provenance.findResults(staleClaim.slotId()));
			assertEquals(ClaimEndReason.TAKEN_OVER,
					lifecycle.findClaim(staleClaim.claimId()).orElseThrow().endReason().orElseThrow());
			var pending = lifecycle.findSlot(staleClaim.slotId()).orElseThrow();
			assertEquals(ConsumptionStatus.PENDING, pending.status());
			assertTrue(pending.terminalOutcome().isEmpty());

			execute.execute(new ExecuteConsumptionInput(winner.slotId(), winner.claimId(), located.execution()));
			assertEquals(11L, version(potId));
			assertEquals(1, provenance.findInputs(winner.slotId()).size());
			assertEquals(List.of(), provenance.findResults(winner.slotId()));
			assertEquals(TerminalOutcome.SUCCESS,
					lifecycle.findSlot(winner.slotId()).orElseThrow().terminalOutcome().orElseThrow());
		}
	}

	private static ConsumptionOrchestrationInput input() {
		return new ConsumptionOrchestrationInput(new WorkerId("latest-known-version-worker"),
				new ClaimLease(java.time.Duration.ofSeconds(30)), new ConsumptionOrchestrationBudget(20, 10));
	}

	private long version(PotId potId) {
		return jdbc.queryForObject("select latest_version_seen from pocoma_read.source_version_watermarks where pot_id=?",
				Long.class, potId.value());
	}

	private Instant advancedAt(PotId potId) {
		return jdbc.queryForObject("select advanced_at from pocoma_read.source_version_watermarks where pot_id=?",
				(rs, row) -> rs.getTimestamp(1).toInstant(), potId.value());
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	private long countRead(String table) {
		return jdbc.queryForObject("select count(*) from pocoma_read." + table, Long.class);
	}

	private static Claim acquired(AcquireResult result) {
		return assertInstanceOf(AcquireResult.Acquired.class, result).claim();
	}

	private static final class ExpectedFailure extends RuntimeException {}
}
