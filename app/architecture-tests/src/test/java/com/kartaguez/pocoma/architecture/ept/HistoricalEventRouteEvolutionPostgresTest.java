package com.kartaguez.pocoma.architecture.ept;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pot.event.PocomaEventTypes;
import com.kartaguez.pocoma.domain.pot.event.PotCreatedEvent;
import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.processing.event.materialization.ProjectionMaterializationPolicy;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreAccessAutoConfiguration;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreMigrationAutoConfiguration;
import com.kartaguez.pocoma.runtime.event.consumption.EventConsumptionProperties;
import com.kartaguez.pocoma.runtime.event.consumption.EventConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.event.consumption.PocomaProjectionMaterializationPolicy;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;

@Testcontainers
class HistoricalEventRouteEvolutionPostgresTest {
	private static final ProjectionType READ_POT = ReadPotProjectionDefinition.PROJECTION_TYPE;
	private static final ProjectionType POT_BALANCES = PotBalancesProjectionDefinition.PROJECTION_TYPE;

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void evolvedCurrentPolicyBackfillsOnlyTheNewHistoricalEventConsequence() {
		JdbcTemplate jdbc = jdbc();
		UUID potId = UUID.randomUUID();
		EventRow historicalEvent;
		SlotRow initialReadPotSlot;
		TaskRow initialReadPotTask;

		try (ConfigurableApplicationContext initial = eventContext(
				InitialPolicyConfiguration.class, "ept-6-3-initial-policy-worker")) {
			cleanDatabase(jdbc);
			assertCurrentPolicy(initial, Set.of(READ_POT));
			assertWorkerScope(initial);

			initial.getBean(JpaBusinessEventOutboxAdapter.class)
					.append(new PotCreatedEvent(PotId.of(potId), 1));
			historicalEvent = loadEvent(jdbc, potId);
			assertEquals("POT_CREATED", historicalEvent.eventType());
			assertEquals(potId, historicalEvent.aggregateId());
			assertEquals(1, historicalEvent.version());
			initial.getBean(ConsumptionPollingWorker.class).runOneCycle();

			initialReadPotSlot = requireSlot(jdbc, historicalEvent.eventId(), READ_POT);
			assertEquals("DONE", initialReadPotSlot.status());
			assertEquals("SUCCESS", initialReadPotSlot.terminalOutcome());
			assertEquals(1, claimCount(jdbc, historicalEvent.eventId(), READ_POT));
			assertEquals(1, successfulClaimCount(jdbc, historicalEvent.eventId(), READ_POT));

			initialReadPotTask = requireTask(jdbc, potId, historicalEvent.version(), READ_POT);
			assertEquals("POT", initialReadPotTask.targetObjectType());
			assertEquals(potId.toString(), initialReadPotTask.targetObjectId());
			assertEquals(1, taskCount(jdbc, potId, historicalEvent.version(), READ_POT));

			assertTrue(findSlots(jdbc, historicalEvent.eventId(), POT_BALANCES).isEmpty());
			assertEquals(0, claimCount(jdbc, historicalEvent.eventId(), POT_BALANCES));
			assertEquals(0, taskCount(jdbc, potId, historicalEvent.version(), POT_BALANCES));
		}

		try (ConfigurableApplicationContext enriched = eventContext(
				EnrichedPolicyConfiguration.class, "ept-6-3-enriched-policy-worker")) {
			assertCurrentPolicy(enriched, Set.of(READ_POT, POT_BALANCES));
			assertWorkerScope(enriched);
			assertEquals(historicalEvent, loadEvent(jdbc, potId));

			enriched.getBean(ConsumptionPollingWorker.class).runOneCycle();

			assertEquals(historicalEvent, loadEvent(jdbc, potId));
			SlotRow readPotSlot = requireSlot(jdbc, historicalEvent.eventId(), READ_POT);
			assertEquals(initialReadPotSlot.slotId(), readPotSlot.slotId());
			assertEquals("DONE", readPotSlot.status());
			assertEquals("SUCCESS", readPotSlot.terminalOutcome());
			assertEquals(1, claimCount(jdbc, historicalEvent.eventId(), READ_POT));
			assertEquals(1, successfulClaimCount(jdbc, historicalEvent.eventId(), READ_POT));

			TaskRow readPotTask = requireTask(jdbc, potId, historicalEvent.version(), READ_POT);
			assertEquals(initialReadPotTask.technicalId(), readPotTask.technicalId());
			assertEquals(1, taskCount(jdbc, potId, historicalEvent.version(), READ_POT));

			SlotRow balancesSlot = requireSlot(jdbc, historicalEvent.eventId(), POT_BALANCES);
			assertEquals("DONE", balancesSlot.status());
			assertEquals("SUCCESS", balancesSlot.terminalOutcome());
			assertEquals(1, claimCount(jdbc, historicalEvent.eventId(), POT_BALANCES));
			assertEquals(1, successfulClaimCount(jdbc, historicalEvent.eventId(), POT_BALANCES));

			TaskRow balancesTask = requireTask(jdbc, potId, historicalEvent.version(), POT_BALANCES);
			assertEquals("POT", balancesTask.targetObjectType());
			assertEquals(potId.toString(), balancesTask.targetObjectId());
			assertEquals(1, taskCount(jdbc, potId, historicalEvent.version(), POT_BALANCES));

			assertEquals(Set.of("READ_POT", "POT_BALANCES"), projectionTypes(jdbc, potId, historicalEvent.version()));
			assertEquals(2, eventMaterializationSlotCount(jdbc, historicalEvent.eventId()));
			assertEquals(2, eventMaterializationClaimCount(jdbc, historicalEvent.eventId()));
		}
	}

	private ConfigurableApplicationContext eventContext(Class<?> policyConfiguration, String workerId) {
		return new SpringApplicationBuilder(EventTestApplication.class, policyConfiguration)
				.web(WebApplicationType.NONE)
				.properties(Map.ofEntries(
						Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
						Map.entry("spring.datasource.username", POSTGRES.getUsername()),
						Map.entry("spring.datasource.password", POSTGRES.getPassword()),
						Map.entry("spring.datasource.driver-class-name", "org.postgresql.Driver"),
						Map.entry("spring.jpa.hibernate.ddl-auto", "validate"),
						Map.entry("spring.flyway.enabled", "true"),
						Map.entry("spring.flyway.locations", "classpath:db/migration"),
						Map.entry("pocoma.event-consumption.enabled", "false"),
						Map.entry("pocoma.event-consumption.projection-types", "READ_POT,POT_BALANCES"),
						Map.entry("pocoma.event-consumption.segment-index", "0"),
						Map.entry("pocoma.event-consumption.segment-count", "1"),
						Map.entry("pocoma.event-consumption.worker-id", workerId),
						Map.entry("pocoma.event-consumption.max-candidates-inspected", "10"),
						Map.entry("pocoma.event-consumption.max-consumptions-executed", "10"),
						Map.entry("pocoma.event-consumption.poll-interval", "1h")))
				.run();
	}

	private void assertCurrentPolicy(ConfigurableApplicationContext context, Set<ProjectionType> expected) {
		assertEquals(expected, context.getBean(ProjectionMaterializationPolicy.class)
				.materializations().get(PocomaEventTypes.POT_CREATED));
	}

	private void assertWorkerScope(ConfigurableApplicationContext context) {
		assertEquals(List.of("READ_POT", "POT_BALANCES"),
				context.getBean(EventConsumptionProperties.class).getProjectionTypes());
	}

	private void cleanDatabase(JdbcTemplate jdbc) {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims, "
				+ "projection_tasks, tasks_4_pipeline, business_event_outbox cascade");
	}

	private EventRow loadEvent(JdbcTemplate jdbc, UUID potId) {
		List<EventRow> rows = jdbc.query("select id, event_type, pot_id, pot_partition_hash, aggregate_id, version, "
				+ "payload_json, created_at from business_event_outbox where pot_id=?",
				(rs, row) -> new EventRow(
						rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getObject("pot_id", UUID.class),
						rs.getInt("pot_partition_hash"), rs.getObject("aggregate_id", UUID.class), rs.getLong("version"),
						rs.getString("payload_json"), rs.getTimestamp("created_at").toInstant()), potId);
		assertEquals(1, rows.size());
		return rows.getFirst();
	}

	private SlotRow requireSlot(JdbcTemplate jdbc, UUID eventId, ProjectionType projectionType) {
		List<SlotRow> slots = findSlots(jdbc, eventId, projectionType);
		assertEquals(1, slots.size());
		return slots.getFirst();
	}

	private List<SlotRow> findSlots(JdbcTemplate jdbc, UUID eventId, ProjectionType projectionType) {
		return jdbc.query("select slot_id, status, terminal_outcome from consumption_slots "
				+ "where consumable_type='EVENT' and consumable_components=cast(? as jsonb) "
				+ "and consumer_type='PROJECTION_TASK_MATERIALIZER' and consumer_components=cast(? as jsonb)",
				(rs, row) -> new SlotRow(rs.getObject("slot_id", UUID.class), rs.getString("status"),
						rs.getString("terminal_outcome")), json(List.of(eventId.toString())),
				json(List.of(projectionType.value())));
	}

	private int claimCount(JdbcTemplate jdbc, UUID eventId, ProjectionType projectionType) {
		return claimCount(jdbc, eventId, projectionType, "");
	}

	private int successfulClaimCount(JdbcTemplate jdbc, UUID eventId, ProjectionType projectionType) {
		return claimCount(jdbc, eventId, projectionType, " and claim.end_reason='SUCCESS' and claim.ended_at is not null");
	}

	private int claimCount(JdbcTemplate jdbc, UUID eventId, ProjectionType projectionType, String claimPredicate) {
		return jdbc.queryForObject("select count(*) from consumption_claims claim join consumption_slots slot "
				+ "on slot.slot_id=claim.slot_id where slot.consumable_type='EVENT' "
				+ "and slot.consumable_components=cast(? as jsonb) "
				+ "and slot.consumer_type='PROJECTION_TASK_MATERIALIZER' "
				+ "and slot.consumer_components=cast(? as jsonb)" + claimPredicate,
				Integer.class, json(List.of(eventId.toString())), json(List.of(projectionType.value())));
	}

	private TaskRow requireTask(JdbcTemplate jdbc, UUID potId, long version, ProjectionType projectionType) {
		List<TaskRow> tasks = jdbc.query("select id, target_object_type, target_object_id from projection_tasks "
				+ "where projection_type=? and target_object_type='POT' and target_object_id=? and target_version=?",
				(rs, row) -> new TaskRow(rs.getObject("id", UUID.class), rs.getString("target_object_type"),
						rs.getString("target_object_id")), projectionType.value(), potId.toString(), version);
		assertEquals(1, tasks.size());
		return tasks.getFirst();
	}

	private int taskCount(JdbcTemplate jdbc, UUID potId, long version, ProjectionType projectionType) {
		return jdbc.queryForObject("select count(*) from projection_tasks where projection_type=? "
				+ "and target_object_type='POT' and target_object_id=? and target_version=?",
				Integer.class, projectionType.value(), potId.toString(), version);
	}

	private Set<String> projectionTypes(JdbcTemplate jdbc, UUID potId, long version) {
		return Set.copyOf(jdbc.queryForList("select projection_type from projection_tasks "
				+ "where target_object_type='POT' and target_object_id=? and target_version=?",
				String.class, potId.toString(), version));
	}

	private int eventMaterializationSlotCount(JdbcTemplate jdbc, UUID eventId) {
		return jdbc.queryForObject("select count(*) from consumption_slots where consumable_type='EVENT' "
				+ "and consumable_components=cast(? as jsonb) and consumer_type='PROJECTION_TASK_MATERIALIZER'",
				Integer.class, json(List.of(eventId.toString())));
	}

	private int eventMaterializationClaimCount(JdbcTemplate jdbc, UUID eventId) {
		return jdbc.queryForObject("select count(*) from consumption_claims claim join consumption_slots slot "
				+ "on slot.slot_id=claim.slot_id where slot.consumable_type='EVENT' "
				+ "and slot.consumable_components=cast(? as jsonb) "
				+ "and slot.consumer_type='PROJECTION_TASK_MATERIALIZER'",
				Integer.class, json(List.of(eventId.toString())));
	}

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	private String json(List<String> values) {
		try {
			return json.writeValueAsString(values);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static ProjectionMaterializationPolicy policyForPotCreated(Set<ProjectionType> projectionTypes) {
		var materializations = new LinkedHashMap<>(
				PocomaProjectionMaterializationPolicy.policy().materializations());
		materializations.put(PocomaEventTypes.POT_CREATED, Set.copyOf(projectionTypes));
		return new ProjectionMaterializationPolicy(PocomaEventTypes.all(), materializations);
	}

	private record EventRow(UUID eventId, String eventType, UUID potId, int partitionHash,
			UUID aggregateId, long version, String payloadJson, Instant recordedAt) {}

	private record SlotRow(UUID slotId, String status, String terminalOutcome) {}

	private record TaskRow(UUID technicalId, String targetObjectType, String targetObjectId) {}

	@TestConfiguration(proxyBeanMethods = false)
	static class InitialPolicyConfiguration {
		@Bean @Primary
		ProjectionMaterializationPolicy initialProjectionMaterializationPolicy() {
			return policyForPotCreated(Set.of(READ_POT));
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class EnrichedPolicyConfiguration {
		@Bean @Primary
		ProjectionMaterializationPolicy enrichedProjectionMaterializationPolicy() {
			return policyForPotCreated(Set.of(READ_POT, POT_BALANCES));
		}
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = {ReadStoreAccessAutoConfiguration.class, ReadStoreMigrationAutoConfiguration.class})
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.repository")
	@Import({EventConsumptionRuntimeConfiguration.class, JpaBusinessEventOutboxAdapter.class})
	static class EventTestApplication {}
}
