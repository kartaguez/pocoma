package com.kartaguez.pocoma.runtime.task.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.domain.projection.Projection;
import com.kartaguez.pocoma.domain.projection.ProjectionFailure;
import com.kartaguez.pocoma.domain.projection.ProjectionFailureId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.JsonNumber;
import com.kartaguez.pocoma.domain.projection.JsonObject;
import com.kartaguez.pocoma.domain.projection.JsonString;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionAcquisitionPrecondition;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.TerminalFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionWritePort;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionReadPort;
import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskKeys;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskStorePort;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;

@SpringBootTest(properties = {
		"pocoma.task-consumption.enabled=false",
		"pocoma.projection-task-consumption.enabled=true",
		"pocoma.projection-task-consumption.catalog-projection-types=POT_BALANCES,READ_POT",
		"pocoma.projection-task-consumption.locator-projection-types=POT_BALANCES,READ_POT",
		"pocoma.projection-task-consumption.poll-interval=1h",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class CanonicalProjectionTaskRuntimePostgresTest {
	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private JdbcTemplate jdbc;
	@Autowired private AcquireConsumptionUseCase acquire;
	@Autowired private FinalizeConsumptionUseCase finalizer;
	@Autowired private ProjectionWritePort writer;
	@Autowired private ProjectionValidator validator;
	@Autowired private ProjectionTaskStorePort tasks;
	@Autowired private ProjectionReadPort reader;
	@Autowired private ConsumptionPollingWorker worker;

	@BeforeEach
	void clean() throws InterruptedException {
		var stopped = new CountDownLatch(1);
		worker.requestStop(stopped::countDown);
		if (!stopped.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("projection worker did not stop");
		}
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims, "
				+ "projection_tasks cascade");
		jdbc.execute("truncate table pocoma_read.projection_failure, pocoma_read.projection_artifact, "
				+ "pocoma_read.projection_root cascade");
		jdbc.execute("truncate table expense_shares, expense_headers, shareholders, pot_headers, "
				+ "pot_version_metadata, pot_global_versions cascade");
	}

	@Test
	void realPotBalancesTaskRunsThroughLocatorGenericEngineProducerAndFencedFinalization() {
		var data = seedHistoricalPot();
		ProjectionKey key = new ProjectionKey(PotBalancesProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(data.potId().toString()), 2);
		tasks.ensure(key, Instant.parse("2026-09-20T10:00:00Z"));

		worker.runOneCycle();

		var projection = reader.findProjection(key).orElseThrow();
		assertEquals(2, projection.artifacts().size());
		var valuesByShareholder = projection.artifacts().stream().collect(java.util.stream.Collectors.toMap(
				artifact -> artifact.artifactKey().value(),
				artifact -> (JsonObject) ((JsonObject) artifact.payload()).values().get("balance")));
		assertEquals(new JsonNumber(java.math.BigDecimal.TEN),
				valuesByShareholder.get(data.payerId().toString()).values().get("numerator"));
		assertEquals(new JsonNumber(java.math.BigDecimal.valueOf(-10)),
				valuesByShareholder.get(data.shareholderId().toString()).values().get("numerator"));
		assertCanonicalConsumptionSucceeded();
	}

	@Test
	void realReadPotTasksLoadTheExactHistoricalExpenseDateAtEachVersion() {
		var data = seedHistoricalPot();
		ProjectionKey versionOne = readPotKey(data.potId(), 1);
		ProjectionKey versionTwo = readPotKey(data.potId(), 2);
		tasks.ensure(versionOne, Instant.parse("2026-09-20T10:00:00Z"));
		tasks.ensure(versionTwo, Instant.parse("2026-09-20T10:00:01Z"));

		worker.runOneCycle();
		worker.runOneCycle();

		assertEquals(LocalDate.parse("2026-01-01"), projectedExpenseDate(versionOne));
		assertEquals(LocalDate.parse("2026-02-02"), projectedExpenseDate(versionTwo));
		assertEquals(2, count("select count(*) from consumption_slots where status='DONE' "
				+ "and terminal_outcome='SUCCESS'"));
		assertEquals(2, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));
	}

	@Test
	void canonicalPublishAndConsumptionFinalizationShareOneLocalTransaction() {
		ProjectionKey key = key();
		var claim = assertInstanceOf(AcquireResult.Acquired.class,
				acquire.acquire(new AcquireConsumptionInput(ProjectionTaskKeys.consumptionKey(key),
						new WorkerId("canonical-test"), new ClaimLease(java.time.Duration.ofSeconds(30)),
						ConsumptionAcquisitionPrecondition.alwaysSatisfied())))
				.claim();
		var validated = validator.validate(PotBalancesProjectionDefinition.DEFINITION,
				new Projection(key, List.of()));

		finalizer.finalizeConsumption(new FinalizeConsumptionInput(claim.slotId(), claim.claimId(), new Success(),
				() -> writer.publish(validated)));

		assertEquals(1, count("select count(*) from pocoma_read.projection_root"));
		assertEquals(1, count("select count(*) from consumption_slots where status='DONE' "
				+ "and terminal_outcome='SUCCESS'"));
		assertEquals(1, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));
	}

	@Test
	void canonicalPublishRollsBackWhenFinalizationTransactionFails() {
		ProjectionKey key = key();
		var claim = assertInstanceOf(AcquireResult.Acquired.class,
				acquire.acquire(new AcquireConsumptionInput(ProjectionTaskKeys.consumptionKey(key),
						new WorkerId("canonical-test"), new ClaimLease(java.time.Duration.ofSeconds(30)),
						ConsumptionAcquisitionPrecondition.alwaysSatisfied())))
				.claim();
		var validated = validator.validate(PotBalancesProjectionDefinition.DEFINITION,
				new Projection(key, List.of()));

		assertThrows(IllegalStateException.class, () -> finalizer.finalizeConsumption(
				new FinalizeConsumptionInput(claim.slotId(), claim.claimId(), new Success(), () -> {
					writer.publish(validated);
					throw new IllegalStateException("force rollback after durable effect");
				})));

		assertEquals(0, count("select count(*) from pocoma_read.projection_root"));
		assertEquals(1, count("select count(*) from consumption_slots where status='PENDING'"));
		assertEquals(1, count("select count(*) from consumption_claims where ended_at is null"));
	}

	@Test
	void canonicalProjectionFailureAndConsumptionFinalizationShareOneLocalTransaction() {
		ProjectionKey key = key();
		var claim = assertInstanceOf(AcquireResult.Acquired.class,
				acquire.acquire(new AcquireConsumptionInput(ProjectionTaskKeys.consumptionKey(key),
						new WorkerId("canonical-test"), new ClaimLease(java.time.Duration.ofSeconds(30)),
						ConsumptionAcquisitionPrecondition.alwaysSatisfied())))
				.claim();
		Instant failedAt = Instant.parse("2026-09-20T10:00:00.123456789Z");
		var projectionFailure = new ProjectionFailure(ProjectionFailureId.random(), key, failedAt);
		var processingFailure = new ProcessingFailure(new ProcessingFailureCode("IMPOSSIBLE_PROJECTION"),
				"projection", "projection cannot be produced", failedAt);

		finalizer.finalizeConsumption(new FinalizeConsumptionInput(claim.slotId(), claim.claimId(),
				new TerminalFailure(processingFailure), () -> writer.recordFailure(projectionFailure)));

		assertEquals(1, count("select count(*) from pocoma_read.projection_failure"));
		assertEquals(1, count("select count(*) from consumption_slots where status='DONE' "
				+ "and terminal_outcome='FAILED'"));
		assertEquals(1, count("select count(*) from consumption_claims "
				+ "where end_reason='PROCESSING_FAILURE'"));
	}

	private ProjectionKey key() {
		return new ProjectionKey(PotBalancesProjectionDefinition.PROJECTION_TYPE,
				PotBalancesProjectionDefinition.TARGET_OBJECT_TYPE,
				new TargetObjectId(UUID.randomUUID().toString()), 42);
	}

	private ProjectionKey readPotKey(UUID potId, long version) {
		return new ProjectionKey(ReadPotProjectionDefinition.PROJECTION_TYPE,
				ReadPotProjectionDefinition.TARGET_OBJECT_TYPE, new TargetObjectId(potId.toString()), version);
	}

	private LocalDate projectedExpenseDate(ProjectionKey key) {
		var expense = reader.findProjection(key).orElseThrow().artifacts().stream()
				.filter(artifact -> artifact.artifactType().equals(ReadPotProjectionDefinition.EXPENSE))
				.findFirst().orElseThrow();
		return LocalDate.parse(((JsonString) ((JsonObject) expense.payload()).values().get("date")).value());
	}

	private HistoricalData seedHistoricalPot() {
		UUID potId = UUID.randomUUID();
		UUID payerId = UUID.randomUUID();
		UUID shareholderId = UUID.randomUUID();
		UUID expenseId = UUID.randomUUID();
		jdbc.update("insert into pot_global_versions(pot_id, version) values (?, 2)", potId);
		jdbc.update("insert into pot_version_metadata(pot_id, version, created_at) values (?, 1, ?), (?, 2, ?)",
				potId, java.sql.Timestamp.from(Instant.parse("2026-01-01T10:00:00Z")),
				potId, java.sql.Timestamp.from(Instant.parse("2026-02-02T10:00:00Z")));
		jdbc.update("insert into pot_headers(id, pot_id, started_at_version, ended_at_version, label, creator_id, deleted) "
				+ "values (?, ?, 1, null, 'Trip', ?, false)", UUID.randomUUID(), potId, UUID.randomUUID());
		jdbc.update("insert into shareholders(id, shareholder_id, pot_id, started_at_version, ended_at_version, "
				+ "name, weight_numerator, weight_denominator, user_id, deleted) "
				+ "values (?, ?, ?, 1, null, 'Payer', 1, 1, null, false), "
				+ "(?, ?, ?, 1, null, 'Guest', 1, 1, null, false)",
				UUID.randomUUID(), payerId, potId, UUID.randomUUID(), shareholderId, potId);
		jdbc.update("insert into expense_headers(id, expense_id, pot_id, started_at_version, ended_at_version, "
				+ "payer_id, amount_numerator, amount_denominator, label, deleted, expense_date) "
				+ "values (?, ?, ?, 1, 2, ?, 10, 1, 'Dinner', false, ?), "
				+ "(?, ?, ?, 2, null, ?, 10, 1, 'Dinner', false, ?)",
				UUID.randomUUID(), expenseId, potId, payerId, LocalDate.parse("2026-01-01"),
				UUID.randomUUID(), expenseId, potId, payerId, LocalDate.parse("2026-02-02"));
		jdbc.update("insert into expense_shares(id, expense_id, shareholder_id, pot_id, started_at_version, "
				+ "ended_at_version, weight_numerator, weight_denominator) values (?, ?, ?, ?, 1, null, 1, 1)",
				UUID.randomUUID(), expenseId, shareholderId, potId);
		return new HistoricalData(potId, payerId, shareholderId);
	}

	private void assertCanonicalConsumptionSucceeded() {
		assertEquals(1, count("select count(*) from consumption_slots where status='DONE' "
				+ "and terminal_outcome='SUCCESS'"));
		assertEquals(1, count("select count(*) from consumption_claims where end_reason='SUCCESS'"));
	}

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}

	private record HistoricalData(UUID potId, UUID payerId, UUID shareholderId) {}
}
