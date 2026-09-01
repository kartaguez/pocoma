package com.kartaguez.pocoma.runtime.task.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaImmutableBalanceProjectionAdapter;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionArtifact;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionIdentity;
import com.kartaguez.pocoma.pipeline.balance.projection.BalanceProjectionReference;

@SpringBootTest(properties = {
		"pocoma.task-consumption.enabled=false",
		"pocoma.task-consumption.pipeline-id=balance-projection",
		"pocoma.task-consumption.pipeline-version=2",
		"pocoma.task-consumption.task-types[0]=COMPUTE_BALANCES_FOR_VERSION",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@Testcontainers
class TaskConsumptionTakeoverPostgresTest {
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
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired private JpaImmutableBalanceProjectionAdapter projections;
	@Autowired private ExecuteConsumptionUseCase execute;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, "
				+ "consumption_claims, balance_projection_entries, balance_projection_artifacts cascade");
	}

	@Test
	void takeoverRollsBackTheStaleProjectionAndProvenance() throws Exception {
		UUID taskId = UUID.randomUUID();
		PotId potId = PotId.of(UUID.randomUUID());
		jdbc.update("insert into pot_global_versions(pot_id, version) values (?, 50)", potId.value());
		ConsumptionKey key = new ConsumptionKey(
				new ConsumableIdentity("TASK", List.of(taskId.toString())),
				new ConsumerIdentity("TASK_EXECUTOR", List.of()));
		Instant acquiredAt = Instant.parse("2026-01-01T00:00:00Z");
		Claim staleClaim = acquire(key, new WorkerId("stale-worker"), acquiredAt);
		BalanceProjectionIdentity identity = new BalanceProjectionIdentity("POT_BALANCES",
				new PipelineDefinition(PipelineId.of("balance-projection"), 2), potId, 42);

		CountDownLatch projectionWritten = new CountDownLatch(1);
		CountDownLatch takeoverDone = new CountDownLatch(1);
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var staleExecution = executor.submit(() -> assertThrows(LostClaimException.class,
					() -> execute.execute(new ExecuteConsumptionInput(staleClaim.slotId(), staleClaim.claimId(), context -> {
						BalanceProjectionReference reference = projections
								.createOrVerify(new BalanceProjectionArtifact(identity, Map.of())).reference();
						projectionWritten.countDown();
						await(takeoverDone);
						return successfulResult(context.slotId(), potId, reference);
					}))));

			if (!projectionWritten.await(10, TimeUnit.SECONDS)) {
				throw new AssertionError("stale worker did not write its projection");
			}
			Claim winningClaim = acquire(key, new WorkerId("winning-worker"), acquiredAt.plusSeconds(2));
			takeoverDone.countDown();
			staleExecution.get(10, TimeUnit.SECONDS);

			assertEquals(0, count("balance_projection_artifacts"));
			assertEquals(0, count("consumption_inputs"));
			assertEquals(0, count("consumption_results"));

			execute.execute(new ExecuteConsumptionInput(winningClaim.slotId(), winningClaim.claimId(), context -> {
				BalanceProjectionReference reference = projections
						.createOrVerify(new BalanceProjectionArtifact(identity, Map.of())).reference();
				return successfulResult(context.slotId(), potId, reference);
			}));

			assertEquals(1, count("balance_projection_artifacts"));
			assertEquals(1, count("consumption_inputs"));
			assertEquals(1, count("consumption_results"));
			assertEquals("SUCCESS", jdbc.queryForObject(
					"select terminal_outcome from consumption_slots where slot_id=?",
					String.class, winningClaim.slotId()));
		}
	}

	private Claim acquire(ConsumptionKey key, WorkerId worker, Instant at) {
		AcquireResult result = new TransactionTemplate(transactionManager).execute(status -> lifecycle.acquire(
				key, ClaimId.generate(), worker, new ClaimLease(Duration.ofSeconds(1)), at));
		return assertInstanceOf(AcquireResult.Acquired.class, result).claim();
	}

	private static ConsumptionExecutionResult successfulResult(
			UUID slotId, PotId potId, BalanceProjectionReference reference) {
		return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(),
				List.of(new ConsumptionInput(slotId, "POT", potId.value().toString(), 42)),
				List.of(new ConsumptionResult(slotId, "BALANCE_PROJECTION", "POT_BALANCES",
						reference.projectionId().toString(), OptionalLong.of(2), Optional.of("POT"),
						Optional.of(potId.value().toString()), OptionalLong.of(42), reference.createdAt())));
	}

	private int count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("takeover did not complete");
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for takeover", interrupted);
		}
	}
}
