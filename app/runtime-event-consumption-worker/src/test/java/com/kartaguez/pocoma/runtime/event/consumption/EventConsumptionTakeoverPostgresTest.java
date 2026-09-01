package com.kartaguez.pocoma.runtime.event.consumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.claim.Claim;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimEndReason;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecution;
import com.kartaguez.pocoma.engine.port.in.consumption.input.AcquireConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.input.ExecuteConsumptionInput;
import com.kartaguez.pocoma.engine.port.in.consumption.result.AcquireResult;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineMaterializationRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineTaskRepository;
import com.kartaguez.pocoma.locator.consumption.event.EventConsumptionLocator;

@SpringBootTest(properties = {
		"pocoma.event-consumption.enabled=false",
		"pocoma.event-consumption.pipeline-id=takeover",
		"pocoma.event-consumption.pipeline-version=1",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@Import(EventConsumptionTakeoverPostgresTest.StrategyConfiguration.class)
class EventConsumptionTakeoverPostgresTest {
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
	@Autowired private JpaPipelineTaskRepository tasks;
	@Autowired private JpaPipelineMaterializationRepository materializations;
	@Autowired private JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired private JpaConsumptionProvenanceAdapter provenance;
	@Autowired private AcquireConsumptionUseCase acquire;
	@Autowired private ExecuteConsumptionUseCase execute;
	@Autowired private EventConsumptionLocator locator;
	@Autowired private JdbcTemplate jdbc;

	@BeforeEach
	void cleanDatabase() {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, "
				+ "consumption_claims, tasks_4_pipeline, event_4_pipeline_materialization_status, "
				+ "business_event_outbox cascade");
	}

	@Test
	void takeoverRollsBackStaleTasksMaterializationAndProvenance() throws Exception {
		outbox.append(new PotCreatedEvent(PotId.of(java.util.UUID.randomUUID()), 1));
		var eventId = events.findAll().getFirst().id();
		var located = locator.openSearch().next().orElseThrow();
		Claim staleClaim = acquired(acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),
				new WorkerId("worker-a"), new ClaimLease(Duration.ofMillis(1)))));
		CountDownLatch tasksWritten = new CountDownLatch(1);
		CountDownLatch allowFinalCas = new CountDownLatch(1);
		ConsumptionExecution blocked = context -> {
			var result = located.execution().execute(context);
			tasksWritten.countDown();
			try {
				if (!allowFinalCas.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("CAS was not released");
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
			assertTrue(tasksWritten.await(10, TimeUnit.SECONDS));
			while (Instant.now().isBefore(staleClaim.leaseUntil())) Thread.onSpinWait();
			Claim winner = acquired(acquire.acquire(new AcquireConsumptionInput(located.consumptionKey(),
					new WorkerId("worker-b"), new ClaimLease(Duration.ofSeconds(30)))));
			allowFinalCas.countDown();
			staleExecution.get(10, TimeUnit.SECONDS);

			assertEquals(0, tasks.count());
			assertEquals(0, materializations.count());
			assertEquals(List.of(), provenance.findInputs(staleClaim.slotId()));
			assertEquals(List.of(), provenance.findResults(staleClaim.slotId()));
			assertEquals(ClaimEndReason.TAKEN_OVER,
					lifecycle.findClaim(staleClaim.claimId()).orElseThrow().endReason().orElseThrow());

			execute.execute(new ExecuteConsumptionInput(winner.slotId(), winner.claimId(), located.execution()));
			assertEquals(1, tasks.count());
			assertEquals(1, materializations.count());
			assertEquals(1, provenance.findInputs(winner.slotId()).size());
			assertEquals(1, provenance.findResults(winner.slotId()).size());
			assertEquals(TerminalOutcome.SUCCESS,
					lifecycle.findSlot(winner.slotId()).orElseThrow().terminalOutcome().orElseThrow());
		}
	}

	private static Claim acquired(AcquireResult result) {
		return assertInstanceOf(AcquireResult.Acquired.class, result).claim();
	}

	@TestConfiguration
	static class StrategyConfiguration {
		@Bean
		TaskCreationStrategy strategy() {
			return new TaskCreationStrategy() {
				@Override public PipelineDefinition definition() {
					return new PipelineDefinition(PipelineId.of("takeover"), 1);
				}
				@Override public boolean supports(com.kartaguez.pocoma.domain.pot.event.BusinessEvent event) {
					return true;
				}
				@Override public List<TaskDescriptor> createTasks(
						com.kartaguez.pocoma.domain.pot.event.BusinessEvent event) {
					return List.of(new TaskDescriptor("TAKEOVER_TASK", "takeover-" + event.version(), "{}",
							event.potId().value().toString(), event.version()));
				}
			};
		}
	}
}
