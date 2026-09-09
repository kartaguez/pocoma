package com.kartaguez.pocoma.runtime.sourceversion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.read.projection.ObserveSourceVersionInput;
import com.kartaguez.pocoma.engine.read.projection.SourceVersionWatermarkPersistencePort;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;
import com.kartaguez.pocoma.locator.consumption.sourceversion.SourceVersionWatermarkConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;

@SpringBootTest(properties = {
		"pocoma.source-version-watermark-consumption.enabled=false",
		"spring.jpa.hibernate.ddl-auto=validate"
})
class SourceVersionWatermarkRuntimePostgresTest {
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
	@Autowired private SourceVersionWatermarkPersistencePort watermarks;
	@Autowired private JdbcTemplate jdbc;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, "
				+ "consumption_claims, tasks_4_pipeline, "
				+ "business_event_outbox cascade");
		jdbc.execute("truncate table pocoma_read.source_version_watermarks");
	}

	@Test
	void consumesOutOfOrderEventsWithoutCreatingTasksOrProjectionMetadata() {
		PotId potId = PotId.of(UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 45));
		outbox.append(new PotCreatedEvent(potId, 42));

		orchestrator.run(input());

		assertEquals(45L, version(potId));
		assertEquals(0L, count("tasks_4_pipeline"));
		assertEquals(0L, countRead("projection_artifacts"));
		assertEquals(0L, countRead("projection_failures"));
		assertEquals(0L, countRead("projection_heads"));
		for (var event : events.findAll()) {
			var slot = lifecycle.findSlot(SourceVersionWatermarkConsumptionLocator.key(event.id())).orElseThrow();
			assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
			assertEquals(1, provenance.findInputs(slot.slotId()).size());
			assertTrue(provenance.findResults(slot.slotId()).isEmpty());
		}
	}

	@Test
	void watermarkRollsBackWhenExecutionFailsBeforeProvenanceAndTerminalCas() {
		PotId potId = PotId.of(UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 42));
		UUID eventId = events.findAll().getFirst().id();
		var acquired = (AcquireResult.Acquired) acquire.acquire(new AcquireConsumptionInput(
				SourceVersionWatermarkConsumptionLocator.key(eventId), new WorkerId("rollback-worker"),
				new ClaimLease(java.time.Duration.ofSeconds(30))));

		assertThrows(ExpectedFailure.class, () -> execute.execute(new ExecuteConsumptionInput(
				acquired.claim().slotId(), acquired.claim().claimId(), context -> {
					watermarks.observe(new ObserveSourceVersionInput(potId, 42, Instant.now()));
					throw new ExpectedFailure();
				})));

		assertEquals(0L, jdbc.queryForObject(
				"select count(*) from pocoma_read.source_version_watermarks where pot_id=?", Long.class,
				potId.value()));
		var slot = lifecycle.findSlot(SourceVersionWatermarkConsumptionLocator.key(eventId)).orElseThrow();
		assertTrue(slot.terminalOutcome().isEmpty());
		assertTrue(provenance.findInputs(slot.slotId()).isEmpty());
		assertTrue(provenance.findResults(slot.slotId()).isEmpty());
	}

	private static ConsumptionOrchestrationInput input() {
		return new ConsumptionOrchestrationInput(new WorkerId("watermark-worker"),
				new ClaimLease(java.time.Duration.ofSeconds(30)), new ConsumptionOrchestrationBudget(20, 10));
	}

	private long version(PotId potId) {
		return jdbc.queryForObject("select latest_version_seen from pocoma_read.source_version_watermarks where pot_id=?",
				Long.class, potId.value());
	}

	private long count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Long.class);
	}

	private long countRead(String table) {
		return jdbc.queryForObject("select count(*) from pocoma_read." + table, Long.class);
	}

	private static final class ExpectedFailure extends RuntimeException {}
}
