package com.kartaguez.pocoma.architecture.ccr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.kartaguez.pocoma.infra.persistence.jpa.adapter.command.JdbcCommandOutcomeAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.core.JpaExpenseHeaderAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.core.JpaExpenseSharesAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.core.JpaPotHeaderAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.core.JpaPotShareholdersAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaHistoricalPotBalanceSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaHistoricalPotSnapshotSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaProjectedExpenseAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaReadPotProjectionInputLoader;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JdbcCommandResultProjectionInputLoader;
import com.kartaguez.pocoma.engine.command.result.CommandResultProjectionInputLoader;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreAccessAutoConfiguration;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreMigrationAutoConfiguration;
import com.kartaguez.pocoma.runtime.event.consumption.EventConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.task.consumption.CanonicalProjectionTaskRuntimeConfiguration;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;

@Testcontainers
class CommandResultProjectionChainPostgresTest {
	private static final Instant NOW = Instant.parse("2026-09-29T10:00:00Z");
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@Test
	void terminalCommandEventProducesTheExactCommandResultProjection() throws Exception {
		JdbcTemplate jdbc = jdbc();
		UUID commandId = UUID.randomUUID();
		UUID userId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();

		try (ConfigurableApplicationContext eventContext = eventContext()) {
			seedAppliedCommand(jdbc, commandId, userId, potId);
			eventContext.getBean(ConsumptionPollingWorker.class).runOneCycle();
			assertEquals(1, count(jdbc, "select count(*) from projection_tasks "
					+ "where projection_type='COMMAND_RESULT' and target_object_type='COMMAND' "
					+ "and target_object_id=? and target_version=1", commandId.toString()));
		}

		try (ConfigurableApplicationContext ignored = taskContext()) {
			awaitProjection(jdbc, commandId);
			Map<String, Object> payload = jdbc.queryForMap("""
					select artifact_key, payload #>> '{commandId}' as command_id,
					       payload #>> '{submittedByUserId}' as user_id,
					       payload #>> '{outcome}' as outcome,
					       payload #>> '{potId}' as pot_id,
					       payload #>> '{resultingVersion}' as resulting_version
					from pocoma_read.projection_artifact artifact
					join pocoma_read.projection_root root on root.id = artifact.projection_root_id
					where root.projection_type='COMMAND_RESULT' and root.target_object_type='COMMAND'
					  and root.target_object_id=? and root.target_version=1
					""", commandId.toString());
			assertEquals(commandId.toString(), payload.get("artifact_key"));
			assertEquals(commandId.toString(), payload.get("command_id"));
			assertEquals(userId.toString(), payload.get("user_id"));
			assertEquals("APPLIED", payload.get("outcome"));
			assertEquals(potId.toString(), payload.get("pot_id"));
			assertEquals("7", payload.get("resulting_version"));
		}
	}

	@Test
	void existingRetryingTargetV2TaskRecoversAndProjectsExactExternalIdentity() throws Exception {
		JdbcTemplate jdbc = jdbc();
		UUID commandId = UUID.randomUUID();
		UUID potId = UUID.randomUUID();
		String issuer = "https://issuer.example/" + commandId;
		String subject = "subject-" + commandId;
		try (ConfigurableApplicationContext eventContext = eventContext()) {
			seedAppliedTargetCommand(jdbc, commandId, issuer, subject, potId);
			eventContext.getBean(ConsumptionPollingWorker.class).runOneCycle();
		}
		jdbc.queryForObject("""
				select id from projection_tasks
				where projection_type='COMMAND_RESULT' and target_object_id=?
				""", UUID.class, commandId.toString());

		try (ConfigurableApplicationContext failing = failingTaskContext()) {
			failing.getBean(ConsumptionPollingWorker.class).runOneCycle();
		}
		assertEquals(1, count(jdbc, """
				select count(*) from consumption_slots
				where status='PENDING' and last_attempt_number>=1
				  and consumable_type='PROJECTION_TASK'
				  and consumable_components::text like ?
				""", "%" + commandId + "%"));

		try (ConfigurableApplicationContext ignored = taskContext()) {
			awaitProjection(jdbc, commandId);
		}
		Map<String, Object> payload = jdbc.queryForMap("""
				select payload #>> '{visibility}' as visibility,
				       payload #>> '{visibleToExternalIdentity,issuer}' as issuer,
				       payload #>> '{visibleToExternalIdentity,subject}' as subject,
				       payload #>> '{submittedByUserId}' as legacy_user,
				       payload #>> '{outcome}' as outcome
				from pocoma_read.projection_artifact artifact
				join pocoma_read.projection_root root on root.id=artifact.projection_root_id
				where root.projection_type='COMMAND_RESULT' and root.target_object_id=?
				""", commandId.toString());
		assertEquals("EXACT_EXTERNAL_IDENTITY", payload.get("visibility"));
		assertEquals(issuer, payload.get("issuer"));
		assertEquals(subject, payload.get("subject"));
		assertEquals(null, payload.get("legacy_user"));
		assertEquals("APPLIED", payload.get("outcome"));
	}

	private ConfigurableApplicationContext eventContext() {
		return application(EventTestApplication.class).properties(
				"pocoma.event-consumption.enabled=false",
				"pocoma.event-consumption.projection-types=COMMAND_RESULT",
				"pocoma.event-consumption.segment-index=0",
				"pocoma.event-consumption.segment-count=1",
				"pocoma.event-consumption.max-candidates-inspected=10",
				"pocoma.event-consumption.max-consumptions-executed=10").run();
	}

	private ConfigurableApplicationContext taskContext() {
		return application(TaskTestApplication.class).properties(
				"pocoma.projection-task-consumption.enabled=true",
				"pocoma.projection-task-consumption.catalog-projection-types=COMMAND_RESULT",
				"pocoma.projection-task-consumption.locator-projection-types=COMMAND_RESULT",
				"pocoma.projection-task-consumption.segment-index=0",
				"pocoma.projection-task-consumption.segment-count=1",
				"pocoma.projection-task-consumption.poll-interval=20ms").run();
	}

	private ConfigurableApplicationContext failingTaskContext() {
		return application(FailingTaskTestApplication.class).properties(
				"pocoma.projection-task-consumption.enabled=true",
				"pocoma.projection-task-consumption.catalog-projection-types=COMMAND_RESULT",
				"pocoma.projection-task-consumption.locator-projection-types=COMMAND_RESULT",
				"pocoma.projection-task-consumption.segment-index=0",
				"pocoma.projection-task-consumption.segment-count=1",
				"pocoma.projection-task-consumption.claim-lease=100ms",
				"pocoma.projection-task-consumption.poll-interval=1h").run();
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

	private void seedAppliedCommand(JdbcTemplate jdbc, UUID commandId, UUID userId, UUID potId) {
		jdbc.update("""
				insert into recorded_commands (
				  command_id, command_type, payload_json, submitted_at, auth_user_id, auth_issuer,
				  auth_authenticated_at, auth_issued_at, auth_valid_until, auth_permissions_json
				) values (?, 'TEST_V1', '{}', ?, ?, 'test', ?, ?, ?, '[]'::jsonb)
				""", commandId, timestamp(NOW), userId, timestamp(NOW), timestamp(NOW),
				timestamp(NOW.plusSeconds(60)));
		jdbc.update("""
				insert into command_outcomes
				  (command_id, outcome_type, pot_id, resulting_version, public_code, resolved_at)
				values (?, 'APPLIED', ?, 7, null, ?)
				""", commandId, potId, timestamp(NOW));
		jdbc.update("""
				insert into command_terminal_events
				  (event_id, event_type, command_id, command_partition_hash, trace_id, recorded_at)
				values (?, 'COMMAND_APPLIED', ?, 0, null, ?)
				""", UUID.randomUUID(), commandId, timestamp(NOW));
	}

	private void seedAppliedTargetCommand(JdbcTemplate jdbc, UUID commandId, String issuer,
			String subject, UUID potId) {
		jdbc.update("""
				insert into recorded_commands (
				  command_id, command_type, payload_json, submitted_at, envelope_version,
				  auth_issuer, auth_subject, binding_id, auth_valid_until,
				  auth_external_authorities_json
				) values (?, 'TEST_V2', '{}', ?, 2, ?, ?, ?, ?, '[]'::jsonb)
				""", commandId, timestamp(NOW), issuer, subject, UUID.randomUUID(),
				timestamp(NOW.plusSeconds(60)));
		jdbc.update("""
				insert into command_outcomes
				  (command_id, outcome_type, pot_id, resulting_version, public_code, resolved_at)
				values (?, 'APPLIED', ?, 7, null, ?)
				""", commandId, potId, timestamp(NOW));
		jdbc.update("""
				insert into command_terminal_events
				  (event_id, event_type, command_id, command_partition_hash, trace_id, recorded_at)
				values (?, 'COMMAND_APPLIED', ?, 0, null, ?)
				""", UUID.randomUUID(), commandId, timestamp(NOW));
	}

	private void awaitProjection(JdbcTemplate jdbc, UUID commandId) throws InterruptedException {
		Instant deadline = Instant.now().plus(TIMEOUT);
		while (Instant.now().isBefore(deadline) && count(jdbc,
				"select count(*) from pocoma_read.projection_root where projection_type='COMMAND_RESULT' "
						+ "and target_object_id=? and target_version=1", commandId.toString()) == 0) {
			Thread.sleep(20);
		}
		assertTrue(count(jdbc,
				"select count(*) from pocoma_read.projection_root where projection_type='COMMAND_RESULT' "
						+ "and target_object_id=? and target_version=1", commandId.toString()) == 1);
	}

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	private static java.sql.Timestamp timestamp(Instant instant) {
		return java.sql.Timestamp.from(instant);
	}

	private static int count(JdbcTemplate jdbc, String sql, Object... arguments) {
		return jdbc.queryForObject(sql, Integer.class, arguments);
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = {ReadStoreAccessAutoConfiguration.class,
			ReadStoreMigrationAutoConfiguration.class})
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.repository")
	@Import(EventConsumptionRuntimeConfiguration.class)
	static class EventTestApplication {}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.repository")
	@Import({CanonicalProjectionTaskRuntimeConfiguration.class, JdbcCommandOutcomeAdapter.class,
			JdbcCommandResultProjectionInputLoader.class, JpaPotHeaderAdapter.class,
			JpaPotShareholdersAdapter.class, JpaExpenseHeaderAdapter.class, JpaExpenseSharesAdapter.class,
			JpaProjectedExpenseAdapter.class, JpaHistoricalPotSnapshotSourceAdapter.class,
			JpaHistoricalPotBalanceSourceAdapter.class, JpaReadPotProjectionInputLoader.class})
	static class TaskTestApplication {}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.repository")
	@Import({CanonicalProjectionTaskRuntimeConfiguration.class, JdbcCommandOutcomeAdapter.class,
			JpaPotHeaderAdapter.class, JpaPotShareholdersAdapter.class, JpaExpenseHeaderAdapter.class,
			JpaExpenseSharesAdapter.class, JpaProjectedExpenseAdapter.class,
			JpaHistoricalPotSnapshotSourceAdapter.class, JpaHistoricalPotBalanceSourceAdapter.class,
			JpaReadPotProjectionInputLoader.class})
	static class FailingTaskTestApplication {
		@Bean CommandResultProjectionInputLoader commandResultProjectionInputLoader() {
			return key -> { throw new IllegalStateException("historical V2 loader failure"); };
		}
	}
}
