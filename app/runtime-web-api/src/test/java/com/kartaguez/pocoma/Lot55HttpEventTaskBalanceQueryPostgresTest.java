package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.key.ConsumableIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumerIdentity;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.balance.PotBalancesCalculator;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.query.PotBalancesQueryPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.projection.balance.CalculatePotBalancesAtVersionService;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.taskcreation.CreateTasksForEventService;
import com.kartaguez.pocoma.engine.service.taskcreation.PlanTasksForEventService;
import com.kartaguez.pocoma.engine.service.taskcreation.TaskCreationStrategyRegistry;
import com.kartaguez.pocoma.engine.service.taskexecution.ExecuteTaskService;
import com.kartaguez.pocoma.engine.service.taskexecution.RecordedTaskExecutionMapperRegistry;
import com.kartaguez.pocoma.engine.service.taskexecution.TaskExecutionHandlerRegistry;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline.JpaTaskCreationAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event.JpaEventConsumptionDiscoveryAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event.JpaEventPort;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.task.JpaTaskConsumptionDiscoveryAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.task.JpaTaskPort;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaHistoricalPotBalanceSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaImmutableBalanceProjectionAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionClaimRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionInputRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionResultRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.locator.consumption.event.EventConsumptionLocator;
import com.kartaguez.pocoma.locator.consumption.event.failure.EventConsumptionFailurePolicy;
import com.kartaguez.pocoma.locator.consumption.event.failure.EventConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.locator.consumption.task.TaskConsumptionLocator;
import com.kartaguez.pocoma.locator.consumption.task.failure.TaskConsumptionFailurePolicy;
import com.kartaguez.pocoma.locator.consumption.task.failure.TaskConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.SequentialConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationInput;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationResult;
import com.kartaguez.pocoma.pipeline.balance.BalancePipeline;
import com.kartaguez.pocoma.pipeline.balance.BalanceTaskCreationStrategy;
import com.kartaguez.pocoma.pipeline.balance.ComputeBalancesRecordedTaskMapper;
import com.kartaguez.pocoma.pipeline.balance.ExecuteBalanceProjectionTaskHandler;
import com.kartaguez.pocoma.supra.http.rest.spring.security.UserContextFactory;

@SpringBootTest(properties = {
		"pocoma.query.balance.pipeline-id=balance-projection",
		"pocoma.query.balance.pipeline-version=2",
		"pocoma.projection.worker.enabled=false",
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test",
		"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.test/jwks"
})
@ActiveProfiles("postgres")
@Testcontainers
class Lot55HttpEventTaskBalanceQueryPostgresTest {
	private static final PipelineDefinition PIPELINE =
			new PipelineDefinition(PipelineId.of(BalancePipeline.PIPELINE_ID), 2);
	private static final Clock CLOCK = Clock.systemUTC();

	@Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired WebApplicationContext webApplicationContext;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper objectMapper;
	@Autowired TransactionRunner transactions;
	@Autowired PotBalancesQueryPort balancesQuery;
	@Autowired JpaConsumptionSlotRepository slots;
	@Autowired JpaConsumptionClaimRepository claims;
	@Autowired JpaConsumptionInputRepository inputs;
	@Autowired JpaConsumptionResultRepository results;
	@Autowired JpaEventConsumptionDiscoveryAdapter eventDiscovery;
	@Autowired JpaEventPort events;
	@Autowired JpaTaskCreationAdapter taskCreation;
	@Autowired JpaTaskConsumptionDiscoveryAdapter taskDiscovery;
	@Autowired JpaTaskPort tasks;
	@Autowired JpaHistoricalPotBalanceSourceAdapter historicalSources;
	@Autowired JpaImmutableBalanceProjectionAdapter projections;

	private JpaConsumptionLifecycleAdapter lifecycle;
	private JpaConsumptionProvenanceAdapter provenance;
	private AcquireConsumptionUseCase acquire;
	private ExecuteConsumptionUseCase execute;
	private MockMvc http;

	@BeforeEach
	void cleanAndAssembleConsumptionEngines() {
		http = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims, "
				+ "balance_projection_entries, balance_projection_artifacts, tasks_4_pipeline, "
				+ "event_4_pipeline_materialization_status, business_event_outbox, projection_tasks, "
				+ "expense_shares, expense_headers, shareholders, pot_headers, pot_global_versions cascade");
		lifecycle = new JpaConsumptionLifecycleAdapter(slots, claims, objectMapper);
		provenance = new JpaConsumptionProvenanceAdapter(inputs, results);
		acquire = new TransactionalAcquireConsumptionUseCase(
				new AcquireConsumptionService(lifecycle, CLOCK), transactions);
		execute = new TransactionalExecuteConsumptionUseCase(
				new ExecuteConsumptionService(lifecycle, provenance, CLOCK), transactions);
	}

	@Test
	void currentHttpCommandPathReachesTheImmutableBalanceQueryWithoutLegacyWorkers() throws Exception {
		UUID userId = UUID.randomUUID();
		String scopes = "pot:create;shareholder:create;pot:view;balance:view";

		String createResponse = http.perform(post("/api/pots")
					.header(UserContextFactory.USER_ID_HEADER, userId)
					.header(UserContextFactory.USER_SCOPES_HEADER, scopes)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"label\":\"Lot 5.5 Pot\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version").value(1))
				.andReturn().getResponse().getContentAsString();
		UUID potId = UUID.fromString(objectMapper.readTree(createResponse).path("id").asText());

		String shareholderResponse = http.perform(post("/api/pots/{potId}/shareholders", potId)
					.header(UserContextFactory.USER_ID_HEADER, userId)
					.header(UserContextFactory.USER_SCOPES_HEADER, scopes)
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"shareholders":[{"name":"Alice","weightNumerator":1,"weightDenominator":1}],
							 "expectedVersion":1}
							"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.version").value(2))
				.andReturn().getResponse().getContentAsString();
		UUID shareholderId = UUID.fromString(objectMapper.readTree(shareholderResponse)
				.path("shareholders").get(0).path("id").asText());

		assertDurableCommandResults(potId, userId, shareholderId);
		List<UUID> eventIds = jdbc.queryForList(
				"select id from business_event_outbox where pot_id=? order by version", UUID.class, potId);

		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				eventOrchestrator().run(input("lot-5.5-event-worker")));
		List<UUID> taskIds = assertEventConsumptionsAndTasks(potId, eventIds);

		assertInstanceOf(ConsumptionOrchestrationResult.Idle.class,
				taskOrchestrator().run(input("lot-5.5-task-worker")));
		assertTaskConsumptionsAndProjection(potId, shareholderId, taskIds);
		assertEquals(0, jdbc.queryForObject("select count(*) from pot_balance_versions where pot_id=?",
				Integer.class, potId));
		assertEquals(0, jdbc.queryForObject("select count(*) from pot_balances where pot_id=?",
				Integer.class, potId));

		var balances = balancesQuery.loadAtVersion(PotId.of(potId), 2);
		assertEquals(2, balances.version());
		assertEquals(Fraction.ZERO, balances.balances().get(ShareholderId.of(shareholderId)).value());

		http.perform(get("/api/pots/{potId}/balances?version=2", potId)
					.header(UserContextFactory.USER_ID_HEADER, userId)
					.header(UserContextFactory.USER_SCOPES_HEADER, scopes))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.potId").value(potId.toString()))
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.balances[0].shareholderId").value(shareholderId.toString()))
				.andExpect(jsonPath("$.balances[0].value.numerator").value(0))
				.andExpect(jsonPath("$.balances[0].value.denominator").value(1));
	}

	private void assertDurableCommandResults(UUID potId, UUID userId, UUID shareholderId) {
		assertEquals(2L, jdbc.queryForObject("select version from pot_global_versions where pot_id=?", Long.class, potId));
		assertEquals(1, jdbc.queryForObject("select count(*) from pot_headers where pot_id=? and label='Lot 5.5 Pot' "
				+ "and creator_id=? and started_at_version=1", Integer.class, potId, userId));
		assertEquals(1, jdbc.queryForObject("select count(*) from shareholders where pot_id=? and shareholder_id=? "
				+ "and name='Alice' and started_at_version=2", Integer.class, potId, shareholderId));
		assertEquals(1, jdbc.queryForObject("select count(*) from business_event_outbox where pot_id=? and version=1 "
				+ "and event_type='PotCreatedEvent' and payload_json::jsonb->>'potId'=?", Integer.class,
				potId, potId.toString()));
		assertEquals(1, jdbc.queryForObject("select count(*) from business_event_outbox where pot_id=? and version=2 "
				+ "and event_type='PotShareholdersAddedEvent' "
				+ "and payload_json::jsonb->'shareholderIds'=jsonb_build_array(?::text)", Integer.class,
				potId, shareholderId.toString()));
	}

	private List<UUID> assertEventConsumptionsAndTasks(UUID potId, List<UUID> eventIds) {
		assertEquals(2, eventIds.size());
		for (UUID eventId : eventIds) {
			var slot = lifecycle.findSlot(eventKey(eventId)).orElseThrow();
			assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
			assertEquals(eventId.toString(), provenance.findInputs(slot.slotId()).getFirst().subjectId());
			assertEquals(1, provenance.findResults(slot.slotId()).size());
		}
		assertEquals(2, jdbc.queryForObject("select count(*) from tasks_4_pipeline task "
				+ "join business_event_outbox event on event.id=task.event_id where event.pot_id=? "
				+ "and pipeline_id='balance-projection' and pipeline_version=2 "
				+ "and task_type='COMPUTE_BALANCES_FOR_VERSION' and partition_key=? "
				+ "and (task_payload::jsonb->>'targetVersion')::bigint=target_version",
				Integer.class, potId, potId.toString()));
		return jdbc.queryForList("select id from tasks_4_pipeline where pipeline_id='balance-projection' "
				+ "and pipeline_version=2 order by target_version", UUID.class);
	}

	private void assertTaskConsumptionsAndProjection(UUID potId, UUID shareholderId, List<UUID> taskIds) {
		assertEquals(2, taskIds.size());
		for (UUID taskId : taskIds) {
			var slot = lifecycle.findSlot(taskKey(taskId)).orElseThrow();
			assertEquals(TerminalOutcome.SUCCESS, slot.terminalOutcome().orElseThrow());
			assertEquals(potId.toString(), provenance.findInputs(slot.slotId()).getFirst().subjectId());
			assertEquals("BALANCE_PROJECTION", provenance.findResults(slot.slotId()).getFirst().space());
		}
		UUID projectionId = jdbc.queryForObject("select projection_id from balance_projection_artifacts "
				+ "where projection_type='POT_BALANCES' and pipeline_id='balance-projection' and pipeline_version=2 "
				+ "and pot_id=? and pot_version=2", UUID.class, potId);
		assertEquals(1, jdbc.queryForObject("select count(*) from balance_projection_entries where projection_id=? "
				+ "and shareholder_id=? and value_numerator=0 and value_denominator=1", Integer.class,
				projectionId, shareholderId));
	}

	private ConsumptionOrchestrator eventOrchestrator() {
		var strategy = new BalanceTaskCreationStrategy(PIPELINE, objectMapper);
		var createTasks = new CreateTasksForEventService(
				new PlanTasksForEventService(new TaskCreationStrategyRegistry(List.of(strategy))), taskCreation);
		var locator = new EventConsumptionLocator(PIPELINE, WorkerSegment.single(), eventDiscovery, events, createTasks,
				new EventConsumptionTechnicalFailureClassifier(CLOCK), CLOCK);
		return new SequentialConsumptionOrchestrator(locator, acquire, execute,
				new TransactionalHandleConsumptionFailureUseCase(
						new HandleConsumptionFailureService(lifecycle, lifecycle,
								new EventConsumptionFailurePolicy(), CLOCK), transactions));
	}

	private ConsumptionOrchestrator taskOrchestrator() {
		var mapper = new ComputeBalancesRecordedTaskMapper(PIPELINE, objectMapper);
		var handler = new ExecuteBalanceProjectionTaskHandler(PIPELINE,
				new CalculatePotBalancesAtVersionService(historicalSources, new PotBalancesCalculator()), projections);
		var locator = new TaskConsumptionLocator(PIPELINE, WorkerSegment.single(), Set.of(BalancePipeline.TASK_TYPE),
				taskDiscovery, tasks, new RecordedTaskExecutionMapperRegistry(List.of(mapper)),
				new ExecuteTaskService(new TaskExecutionHandlerRegistry(List.of(handler))),
				new TaskConsumptionTechnicalFailureClassifier(CLOCK), CLOCK);
		return new SequentialConsumptionOrchestrator(locator, acquire, execute,
				new TransactionalHandleConsumptionFailureUseCase(
						new HandleConsumptionFailureService(lifecycle, lifecycle,
								new TaskConsumptionFailurePolicy(), CLOCK), transactions));
	}

	private static ConsumptionOrchestrationInput input(String workerId) {
		return new ConsumptionOrchestrationInput(new WorkerId(workerId), new ClaimLease(Duration.ofSeconds(30)),
				new ConsumptionOrchestrationBudget(20, 10));
	}

	private static ConsumptionKey eventKey(UUID eventId) {
		return new ConsumptionKey(new ConsumableIdentity("EVENT", List.of(eventId.toString())),
				new ConsumerIdentity("PIPELINE", List.of("balance-projection", "2")));
	}

	private static ConsumptionKey taskKey(UUID taskId) {
		return new ConsumptionKey(new ConsumableIdentity("TASK", List.of(taskId.toString())),
				new ConsumerIdentity("TASK_EXECUTOR", List.of()));
	}
}
