package com.kartaguez.pocoma.eventconsumption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.task.creation.TaskDescriptor;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.outbox.JpaBusinessEventOutboxRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.pipeline.JpaPipelineTaskRepository;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;

@SpringBootTest(properties = {
		"pocoma.event-consumption.enabled=false",
		"pocoma.event-consumption.pipeline-id=balances",
		"pocoma.event-consumption.pipeline-version=1",
		"spring.jpa.hibernate.ddl-auto=validate"
})
@Import(EventConsumptionRuntimePostgresTest.StrategyConfiguration.class)
class EventConsumptionRuntimePostgresTest {
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
	@Autowired private JpaConsumptionLifecycleAdapter lifecycle;
	@Autowired private JpaConsumptionProvenanceAdapter provenance;
	@Autowired private ConsumptionOrchestrator orchestrator;

	@Test
	void eventTasksProvenanceAndTerminalSlotAreCommittedTogether() {
		PotId potId = PotId.of(java.util.UUID.randomUUID());
		outbox.append(new PotCreatedEvent(potId, 1));
		var eventId = events.findAll().getFirst().id();

		var result = orchestrator.run(new ConsumptionOrchestrationInput(
				new com.kartaguez.pocoma.domain.consumption.claim.WorkerId("test-worker"),
				new com.kartaguez.pocoma.domain.consumption.claim.ClaimLease(java.time.Duration.ofSeconds(30)),
				new com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrationBudget(10, 2)));

		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class, result);
		assertEquals(1, tasks.count());
		var key = new ConsumptionKey(new ConsumableIdentity("EVENT", List.of(eventId.toString())),
				new ConsumerIdentity("PIPELINE", List.of("balances", "1")));
		var slot = lifecycle.findSlot(key).orElseThrow();
		assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
		assertEquals(1, provenance.findInputs(slot.slotId()).size());
		assertEquals(1, provenance.findResults(slot.slotId()).size());
		assertEquals(tasks.findAll().getFirst().id().toString(),
				provenance.findResults(slot.slotId()).getFirst().objectId());
	}

	@TestConfiguration
	static class StrategyConfiguration {
		@Bean
		TaskCreationStrategy balancesStrategy() {
			return new TaskCreationStrategy() {
				@Override public PipelineDefinition definition() {
					return new PipelineDefinition(PipelineId.of("balances"), 1);
				}
				@Override public boolean supports(com.kartaguez.pocoma.domain.pot.event.BusinessEvent event) {
					return true;
				}
				@Override public List<TaskDescriptor> createTasks(
						com.kartaguez.pocoma.domain.pot.event.BusinessEvent event) {
					return List.of(new TaskDescriptor("COMPUTE_BALANCES", "balances-" + event.version(),
							"{}", event.potId().value().toString()));
				}
			};
		}
	}
}
