package com.kartaguez.pocoma.runtime.task.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionAcquisitionPrecondition;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.Success;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionFinalization.TerminalFailure;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.FinalizeConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionWritePort;
import com.kartaguez.pocoma.engine.projection.balance.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskKeys;

@SpringBootTest(properties = {
		"pocoma.task-consumption.enabled=false",
		"pocoma.projection-task-consumption.enabled=true",
		"pocoma.projection-task-consumption.projection-type=POT_BALANCES",
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

	@BeforeEach
	void clean() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims, "
				+ "projection_tasks cascade");
		jdbc.execute("truncate table pocoma_read.projection_failure, pocoma_read.projection_artifact, "
				+ "pocoma_read.projection_root cascade");
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

	private int count(String sql) {
		return jdbc.queryForObject(sql, Integer.class);
	}
}
