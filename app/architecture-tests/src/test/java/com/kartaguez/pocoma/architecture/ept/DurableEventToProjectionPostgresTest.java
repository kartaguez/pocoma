package com.kartaguez.pocoma.architecture.ept;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;
import com.kartaguez.pocoma.domain.pot.event.ExpenseCreatedEvent;
import com.kartaguez.pocoma.domain.projection.pot.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.pot.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.projection.ProjectionKey;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.domain.projection.TargetObjectId;
import com.kartaguez.pocoma.domain.projection.TargetObjectType;
import com.kartaguez.pocoma.engine.produce.projectiontask.port.ProjectionMaterializationCandidate;
import com.kartaguez.pocoma.engine.consume.projectiontask.ProjectionTaskKeys;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaExpenseHeaderAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaExpenseSharesAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaPotHeaderAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaPotShareholdersAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.JpaBusinessEventOutboxAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection.JpaHistoricalPotBalanceSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection.JpaHistoricalPotSnapshotSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection.JpaProjectedExpenseAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection.JpaReadPotProjectionInputLoader;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreAccessAutoConfiguration;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreMigrationAutoConfiguration;
import com.kartaguez.pocoma.supra.consume.event.materialization.ProjectionMaterializationConsumptionKeys;
import com.kartaguez.pocoma.runtime.event.consumption.EventConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.task.consumption.CanonicalProjectionTaskRuntimeConfiguration;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker;

@Testcontainers
class DurableEventToProjectionPostgresTest {
	private static final TargetObjectType POT = new TargetObjectType("POT");
	private static final int MAX_EVENT_CYCLES = 2;
	private static final Duration TASK_TIMEOUT = Duration.ofSeconds(10);

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void durableEventOutputIsTheIndependentProjectionTaskRuntimeInput() throws Exception {
		JdbcTemplate jdbc = jdbc();
		UUID potId = UUID.randomUUID();
		UUID payerId = UUID.randomUUID();
		UUID shareholderId = UUID.randomUUID();
		UUID expenseId = UUID.randomUUID();
		EventRow event;
		List<TaskRow> tasks;

		try (ConfigurableApplicationContext eventContext = eventContext()) {
			cleanAndSeed(jdbc, potId, payerId, shareholderId, expenseId);
			eventContext.getBean(JpaBusinessEventOutboxAdapter.class).append(
					new ExpenseCreatedEvent(ExpenseId.of(expenseId), PotId.of(potId), 2));
			event = loadEvent(jdbc, potId, 2);

			assertEquals("EXPENSE_CREATED", event.eventType());
			assertEquals(0, count(jdbc, "select count(*) from projection_tasks "
					+ "where target_object_id=? and target_version=?",
					event.potId().toString(), event.version()));

			Set<ConsumptionKey> eventKeys = Set.of(
					eventConsumptionKey(event, ReadPotProjectionDefinition.PROJECTION_TYPE),
					eventConsumptionKey(event, PotBalancesProjectionDefinition.PROJECTION_TYPE));
			runUntilDone(eventContext.getBean(ConsumptionPollingWorker.class), jdbc, eventKeys, MAX_EVENT_CYCLES);

			tasks = loadProjectionTasks(jdbc, event.potId(), event.version());
			assertEquals(2, tasks.size());
			assertEquals(Set.of("READ_POT", "POT_BALANCES"),
					tasks.stream().map(task -> task.key().projectionType().value()).collect(java.util.stream.Collectors.toSet()));
			for (TaskRow task : tasks) {
				assertNotNull(task.technicalId());
				assertEquals(POT, task.key().targetObjectType());
				assertEquals(event.potId().toString(), task.key().targetObjectId().value());
				assertEquals(event.version(), task.key().targetVersion());
				assertEquals(event.recordedAt(), task.createdAt());
			}
		}

		assertFalse(tasks.isEmpty());
		try (ConfigurableApplicationContext ignored = taskContext()) {
			Set<ConsumptionKey> taskKeys = tasks.stream()
					.map(task -> ProjectionTaskKeys.consumptionKey(task.key()))
					.collect(java.util.stream.Collectors.toUnmodifiableSet());
			awaitDoneSuccess(jdbc, taskKeys, TASK_TIMEOUT);

			TaskRow readPotTask = task(tasks, "READ_POT");
			TaskRow balancesTask = task(tasks, "POT_BALANCES");
			assertProjectionRootAndReadPotArtifacts(jdbc, readPotTask, event.potId(), shareholderId, expenseId);
			assertProjectionRootAndBalance(jdbc, balancesTask, shareholderId);
		}
	}

	private ConfigurableApplicationContext eventContext() {
		return application(EventTestApplication.class)
				.properties(
						"pocoma.event-consumption.enabled=false",
						"pocoma.event-consumption.projection-types=READ_POT,POT_BALANCES",
						"pocoma.event-consumption.segment-index=0",
						"pocoma.event-consumption.segment-count=1",
						"pocoma.event-consumption.worker-id=ept-6-2-event-worker",
						"pocoma.event-consumption.max-candidates-inspected=10",
						"pocoma.event-consumption.max-consumptions-executed=10",
						"pocoma.event-consumption.poll-interval=1h")
				.run();
	}

	private ConfigurableApplicationContext taskContext() {
		return application(TaskTestApplication.class)
				.properties(
						"pocoma.projection-task-consumption.enabled=true",
						"pocoma.projection-task-consumption.catalog-projection-types=READ_POT,POT_BALANCES",
						"pocoma.projection-task-consumption.locator-projection-types=READ_POT,POT_BALANCES",
						"pocoma.projection-task-consumption.segment-index=0",
						"pocoma.projection-task-consumption.segment-count=1",
						"pocoma.projection-task-consumption.worker-id=ept-6-2-task-worker",
						"pocoma.projection-task-consumption.max-candidates-inspected=10",
						"pocoma.projection-task-consumption.max-consumptions-executed=10",
						"pocoma.projection-task-consumption.poll-interval=1h")
				.run();
	}

	private SpringApplicationBuilder application(Class<?> source) {
		return new SpringApplicationBuilder(source).web(WebApplicationType.NONE).properties(Map.of(
				"spring.datasource.url", POSTGRES.getJdbcUrl(),
				"spring.datasource.username", POSTGRES.getUsername(),
				"spring.datasource.password", POSTGRES.getPassword(),
				"spring.datasource.driver-class-name", "org.postgresql.Driver",
				"spring.jpa.hibernate.ddl-auto", "validate",
				"spring.flyway.enabled", "true",
				"spring.flyway.locations", "classpath:db/migration"));
	}

	private void cleanAndSeed(JdbcTemplate jdbc, UUID potId, UUID payerId, UUID shareholderId, UUID expenseId) {
		jdbc.execute("truncate table consumption_inputs, consumption_results, consumption_slots, consumption_claims, "
				+ "projection_tasks, business_event_outbox cascade");
		jdbc.execute("truncate table expense_shares, expense_headers, shareholders, pot_headers, "
				+ "pot_version_metadata, pot_global_versions cascade");
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
				+ "values (?, ?, ?, 2, null, ?, 10, 1, 'Dinner', false, ?)",
				UUID.randomUUID(), expenseId, potId, payerId, LocalDate.parse("2026-02-02"));
		jdbc.update("insert into expense_shares(id, expense_id, shareholder_id, pot_id, started_at_version, "
				+ "ended_at_version, weight_numerator, weight_denominator) "
				+ "values (?, ?, ?, ?, 2, null, 1, 1), (?, ?, ?, ?, 2, null, 1, 1)",
				UUID.randomUUID(), expenseId, payerId, potId,
				UUID.randomUUID(), expenseId, shareholderId, potId);
	}

	private EventRow loadEvent(JdbcTemplate jdbc, UUID potId, long version) {
		return jdbc.query("select id, event_type, pot_id, version, created_at from business_event_outbox "
				+ "where pot_id=? and version=?", (rs, row) -> new EventRow(
					rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getObject("pot_id", UUID.class),
					rs.getLong("version"), rs.getTimestamp("created_at").toInstant()), potId, version)
				.stream().reduce((first, second) -> { throw new AssertionError("several Events match the fixture"); })
				.orElseThrow();
	}

	private List<TaskRow> loadProjectionTasks(JdbcTemplate jdbc, UUID potId, long version) {
		return jdbc.query("select id, projection_type, target_object_type, target_object_id, target_version, "
				+ "partition_hash, created_at from projection_tasks where target_object_id=? and target_version=? "
				+ "order by projection_type", (rs, row) -> new TaskRow(
					rs.getObject("id", UUID.class),
					new ProjectionKey(new ProjectionType(rs.getString("projection_type")),
							new TargetObjectType(rs.getString("target_object_type")),
							new TargetObjectId(rs.getString("target_object_id")), rs.getLong("target_version")),
					rs.getInt("partition_hash"), rs.getTimestamp("created_at").toInstant()), potId.toString(), version);
	}

	private ConsumptionKey eventConsumptionKey(EventRow event, ProjectionType projectionType) {
		return ProjectionMaterializationConsumptionKeys.consumptionKey(new ProjectionMaterializationCandidate(
				event.eventId(), new com.kartaguez.pocoma.domain.event.EventType(event.eventType()), projectionType,
				POT, new TargetObjectId(event.potId().toString()), event.version(), event.recordedAt()));
	}

	private void runUntilDone(ConsumptionPollingWorker worker, JdbcTemplate jdbc,
			Set<ConsumptionKey> keys, int maxCycles) {
		for (int cycle = 0; cycle < maxCycles && !allDoneSuccess(jdbc, keys); cycle++) {
			worker.runOneCycle();
		}
		assertTrue(allDoneSuccess(jdbc, keys), () -> "Event slots did not finish: " + loadSlots(jdbc));
	}

	private void awaitDoneSuccess(JdbcTemplate jdbc, Set<ConsumptionKey> keys, Duration timeout)
			throws InterruptedException {
		Instant deadline = Instant.now().plus(timeout);
		while (Instant.now().isBefore(deadline) && !allDoneSuccess(jdbc, keys)) {
			Thread.sleep(20);
		}
		assertTrue(allDoneSuccess(jdbc, keys), () -> "Task slots did not finish: " + loadSlots(jdbc));
	}

	private boolean allDoneSuccess(JdbcTemplate jdbc, Set<ConsumptionKey> keys) {
		return keys.stream().allMatch(key -> count(jdbc, "select count(*) from consumption_slots "
				+ "where consumable_type=? and consumable_components=cast(? as jsonb) "
				+ "and consumer_type=? and consumer_components=cast(? as jsonb) "
				+ "and status='DONE' and terminal_outcome='SUCCESS'",
				key.consumable().type(), json(key.consumable().components()),
				key.consumer().type(), json(key.consumer().components())) == 1);
	}

	private List<Map<String, Object>> loadSlots(JdbcTemplate jdbc) {
		return jdbc.queryForList("select consumable_type, consumable_components, consumer_type, "
				+ "consumer_components, status, terminal_outcome from consumption_slots order by created_at");
	}

	private void assertProjectionRootAndReadPotArtifacts(JdbcTemplate jdbc, TaskRow task,
			UUID potId, UUID shareholderId, UUID expenseId) {
		long rootId = projectionRootId(jdbc, task.key());
		assertEquals(1, count(jdbc, "select count(*) from pocoma_read.projection_artifact "
				+ "where projection_root_id=? and artifact_type='POT' and artifact_key=?", rootId, potId.toString()));
		assertEquals(1, count(jdbc, "select count(*) from pocoma_read.projection_artifact "
				+ "where projection_root_id=? and artifact_type='SHAREHOLDER' and artifact_key=?",
				rootId, shareholderId.toString()));
		assertEquals(1, count(jdbc, "select count(*) from pocoma_read.projection_artifact "
				+ "where projection_root_id=? and artifact_type='EXPENSE' and artifact_key=?",
				rootId, expenseId.toString()));
	}

	private void assertProjectionRootAndBalance(JdbcTemplate jdbc, TaskRow task, UUID shareholderId) {
		long rootId = projectionRootId(jdbc, task.key());
		Map<String, Object> balance = jdbc.queryForMap("select artifact_key, payload #>> '{shareholderId}' as shareholder_id, "
				+ "payload #>> '{balance,numerator}' as numerator, payload #>> '{balance,denominator}' as denominator "
				+ "from pocoma_read.projection_artifact where projection_root_id=? and artifact_type='BALANCE' "
				+ "and artifact_key=?", rootId, shareholderId.toString());
		assertEquals(shareholderId.toString(), balance.get("artifact_key"));
		assertEquals(shareholderId.toString(), balance.get("shareholder_id"));
		assertEquals("-5", balance.get("numerator"));
		assertEquals("1", balance.get("denominator"));
	}

	private long projectionRootId(JdbcTemplate jdbc, ProjectionKey key) {
		List<Long> roots = jdbc.query("select id from pocoma_read.projection_root where projection_type=? "
				+ "and target_object_type=? and target_object_id=? and target_version=?",
				(rs, row) -> rs.getLong("id"), key.projectionType().value(), key.targetObjectType().value(),
				key.targetObjectId().value(), key.targetVersion());
		assertEquals(1, roots.size());
		return roots.getFirst();
	}

	private TaskRow task(List<TaskRow> tasks, String projectionType) {
		return tasks.stream().filter(task -> task.key().projectionType().value().equals(projectionType))
				.findFirst().orElseThrow();
	}

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	private int count(JdbcTemplate jdbc, String sql, Object... arguments) {
		return jdbc.queryForObject(sql, Integer.class, arguments);
	}

	private String json(List<String> values) {
		try {
			return json.writeValueAsString(values);
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private record EventRow(UUID eventId, String eventType, UUID potId, long version, Instant recordedAt) {}

	private record TaskRow(UUID technicalId, ProjectionKey key, int partitionHash, Instant createdAt) {}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = {ReadStoreAccessAutoConfiguration.class, ReadStoreMigrationAutoConfiguration.class})
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
	@Import({EventConsumptionRuntimeConfiguration.class, JpaBusinessEventOutboxAdapter.class})
	static class EventTestApplication {}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
	@Import({CanonicalProjectionTaskRuntimeConfiguration.class,
			JpaPotHeaderAdapter.class, JpaPotShareholdersAdapter.class,
			JpaExpenseHeaderAdapter.class, JpaExpenseSharesAdapter.class,
			JpaProjectedExpenseAdapter.class, JpaHistoricalPotSnapshotSourceAdapter.class,
			JpaHistoricalPotBalanceSourceAdapter.class, JpaReadPotProjectionInputLoader.class})
	static class TaskTestApplication {}
}
