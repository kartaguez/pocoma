package com.kartaguez.pocoma.architecture.ccr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.CommandResultReadConfiguration;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.PocomaUserId;
import com.kartaguez.pocoma.engine.command.port.out.EventAppendPort;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.engine.command.result.GetCommandResult;
import com.kartaguez.pocoma.engine.command.result.GetCommandResultUseCase;
import com.kartaguez.pocoma.engine.pot.command.decode.PotCommandTypes;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.JpaPotGlobalVersionAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.command.JpaCommandConsumptionDiscoveryAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.command.JpaRecordedCommandAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.command.JdbcCommandOutcomeAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.context.JpaExpenseContextAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.context.JpaPotContextAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.core.JpaExpenseHeaderAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.core.JpaExpenseSharesAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.core.JpaPotHeaderAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.core.JpaPotShareholdersAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.identity.JpaExternalIdentityResolverAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.outbox.JpaPotCommandEventAppendAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaHistoricalPotBalanceSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaHistoricalPotSnapshotSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaProjectedExpenseAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaReadPotProjectionInputLoader;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JdbcCommandResultProjectionInputLoader;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreAccessAutoConfiguration;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreMigrationAutoConfiguration;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.JpaCommandConsumptionDiscoveryRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.command.JpaRecordedCommandRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.identity.ExternalIdentityJdbcRepository;
import com.kartaguez.pocoma.orchestrator.command.admission.AuthorizationSnapshotFactory;
import com.kartaguez.pocoma.orchestrator.command.admission.ExternalAuthorityPermissionTranslator;
import com.kartaguez.pocoma.orchestrator.command.admission.SubmitRecordedCommandService;
import com.kartaguez.pocoma.orchestrator.command.admission.model.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.orchestrator.command.admission.model.CommandAuthorizationTtl;
import com.kartaguez.pocoma.orchestrator.command.admission.model.SubmitRecordedCommandInput;
import com.kartaguez.pocoma.orchestrator.command.admission.port.out.ExternalIdentityResolverPort;
import com.kartaguez.pocoma.runtime.command.consumption.CommandConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.event.consumption.EventConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.task.consumption.CanonicalProjectionTaskRuntimeConfiguration;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.supra.http.rest.spring.controller.CommandResultController;

@Testcontainers
class CommandCompletionE2EPostgresTest {
	private static final String ISSUER = "https://ccr.e2e.test";
	private static final String SUBJECT = "ccr-e2e-user";
	private static final Instant BASE_TIME = Instant.now().minusSeconds(5);

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@Test
	void admissionThroughCommandEventTaskAndExactReadProducesAllTerminalResultsDurably() throws Exception {
		JdbcTemplate jdbc = jdbc();
		UUID userId = UUID.randomUUID();
		String appliedLabel = "ccr-applied-" + UUID.randomUUID();
		String rejectedLabel = "ccr-rejected-" + UUID.randomUUID();
		String failedLabel = "ccr-failed-" + UUID.randomUUID();
		CommandId applied;
		CommandId rejected;
		CommandId failed;
		UUID appliedPotId;

		try (ConfigurableApplicationContext commandContext = commandContext()) {
			jdbc.update("insert into external_identities (issuer,subject,pocoma_user_id) values (?,?,?)",
					ISSUER, SUBJECT, userId);
			applied = admit(commandContext, UUID.randomUUID(), BASE_TIME,
					payload(appliedLabel, userId), Set.of("pocoma:pot:create"));
			rejected = admit(commandContext, UUID.randomUUID(), BASE_TIME.plusMillis(1),
					payload(rejectedLabel, userId), Set.of());
			failed = admit(commandContext, UUID.randomUUID(), BASE_TIME.plusMillis(2),
					payload(failedLabel, userId), Set.of("pocoma:pot:create"));

			assertEquals(3, count(jdbc, "select count(*) from recorded_commands"));
			assertEquals(0, count(jdbc, "select count(*) from command_outcomes"));
			assertEquals(0, count(jdbc, "select count(*) from command_terminal_events"));
			assertEquals(0, count(jdbc, "select count(*) from projection_tasks"));

			commandContext.getBean(ConsumptionPollingWorker.class).runOneCycle();

			appliedPotId = jdbc.queryForObject("select pot_id from pot_headers where label=?",
					UUID.class, appliedLabel);
			assertEquals(1, count(jdbc, "select count(*) from pot_headers where label=?", appliedLabel));
			assertEquals(0, count(jdbc, "select count(*) from pot_headers where label in (?,?)",
					rejectedLabel, failedLabel));
			assertEquals(1, count(jdbc, "select count(*) from business_event_outbox"));
			assertOutcome(jdbc, applied, "APPLIED", "COMMAND_APPLIED", TerminalOutcome.SUCCESS);
			assertOutcome(jdbc, rejected, "REJECTED", "COMMAND_REJECTED", TerminalOutcome.REJECTED);
			assertOutcome(jdbc, failed, "FAILED", "COMMAND_FAILED", TerminalOutcome.FAILED);
			assertEquals(appliedPotId, jdbc.queryForObject(
					"select pot_id from command_outcomes where command_id=?", UUID.class, applied.value()));
			assertEquals(1L, jdbc.queryForObject(
					"select resulting_version from command_outcomes where command_id=?", Long.class,
					applied.value()));
		}

		try (ConfigurableApplicationContext firstEventContext = eventContext()) {
			firstEventContext.getBean(ConsumptionPollingWorker.class).runOneCycle();
		}
		assertTerminalTasks(jdbc, applied, rejected, failed);
		try (ConfigurableApplicationContext restartedEventContext = eventContext()) {
			restartedEventContext.getBean(ConsumptionPollingWorker.class).runOneCycle();
		}
		assertEquals(3, count(jdbc, "select count(*) from projection_tasks where projection_type='COMMAND_RESULT'"));

		try (ConfigurableApplicationContext taskContext = taskContext()) {
			taskContext.getBean(ConsumptionPollingWorker.class).runOneCycle();
			await(() -> count(jdbc, "select count(*) from pocoma_read.projection_root "
					+ "where projection_type='COMMAND_RESULT'") == 3);
		}
		try (ConfigurableApplicationContext restartedTaskContext = taskContext()) {
			restartedTaskContext.getBean(ConsumptionPollingWorker.class).runOneCycle();
		}
		assertEquals(3, count(jdbc, "select count(*) from pocoma_read.projection_root "
				+ "where projection_type='COMMAND_RESULT'"));
		assertEquals(3, count(jdbc, "select count(*) from pocoma_read.projection_artifact artifact "
				+ "join pocoma_read.projection_root root on root.id=artifact.projection_root_id "
				+ "where root.projection_type='COMMAND_RESULT'"));

		try (ConfigurableApplicationContext readContext = readContext()) {
			GetCommandResultUseCase results = readContext.getBean(GetCommandResultUseCase.class);
			CommandResultController controller = new CommandResultController(results,
					identity -> Optional.of(new PocomaUserId(userId)), directTransactions());
			var principal = principal(Set.of());

			var appliedResponse = controller.get(applied.value(), principal);
			assertEquals(HttpStatus.OK, appliedResponse.getStatusCode());
			assertEquals("APPLIED", appliedResponse.getBody().status());
			assertEquals(appliedPotId, appliedResponse.getBody().potId());
			assertEquals(1L, appliedResponse.getBody().resultingVersion());
			assertInstanceOf(GetCommandResult.Applied.class, results.get(applied, userId));

			var rejectedResponse = controller.get(rejected.value(), principal);
			assertEquals(HttpStatus.OK, rejectedResponse.getStatusCode());
			assertEquals("REJECTED", rejectedResponse.getBody().status());
			assertInstanceOf(GetCommandResult.Rejected.class, results.get(rejected, userId));

			var failedResponse = controller.get(failed.value(), principal);
			assertEquals(HttpStatus.OK, failedResponse.getStatusCode());
			assertEquals("FAILED", failedResponse.getBody().status());
			assertInstanceOf(GetCommandResult.Failed.class, results.get(failed, userId));
		}
	}

	private CommandId admit(ConfigurableApplicationContext context, UUID commandId, Instant submittedAt,
			String serializedPayload, Set<String> authorities) {
		var service = new SubmitRecordedCommandService(
				context.getBean(ExternalIdentityResolverPort.class),
				context.getBean(RecordedCommandPort.class),
				() -> new CommandId(commandId),
				new AuthorizationSnapshotFactory(new CommandAuthorizationTtl(Duration.ofMinutes(15)),
						new ExternalAuthorityPermissionTranslator()),
				Clock.fixed(submittedAt, ZoneOffset.UTC), context.getBean(TransactionRunner.class));
		return service.submit(new SubmitRecordedCommandInput(PotCommandTypes.POT_CREATE_V1,
				serializedPayload, principal(authorities))).commandId();
	}

	private static AuthenticatedExternalPrincipal principal(Set<String> authorities) {
		return new AuthenticatedExternalPrincipal(ISSUER, SUBJECT, BASE_TIME.minusSeconds(1),
				BASE_TIME.minusSeconds(1), BASE_TIME.plusSeconds(600), authorities);
	}

	private static String payload(String label, UUID creatorId) {
		return "{\"label\":\"%s\",\"creatorId\":\"%s\"}".formatted(label, creatorId);
	}

	private ConfigurableApplicationContext commandContext() {
		return application(CommandTestApplication.class, TerminalFailureAppendConfiguration.class)
				.properties("pocoma.command-consumption.enabled=false",
						"pocoma.command-consumption.max-consumptions-executed=10").run();
	}

	private ConfigurableApplicationContext eventContext() {
		return application(EventTestApplication.class).properties(
				"pocoma.event-consumption.enabled=false",
				"pocoma.event-consumption.projection-types=COMMAND_RESULT",
				"pocoma.event-consumption.max-consumptions-executed=10").run();
	}

	private ConfigurableApplicationContext taskContext() {
		return application(TaskTestApplication.class).properties(
				"pocoma.projection-task-consumption.enabled=true",
				"pocoma.projection-task-consumption.catalog-projection-types=COMMAND_RESULT",
				"pocoma.projection-task-consumption.locator-projection-types=COMMAND_RESULT",
				"pocoma.projection-task-consumption.poll-interval=20ms").run();
	}

	private ConfigurableApplicationContext readContext() {
		return application(ReadTestApplication.class)
				.properties("pocoma.command-result-read.enabled=true").run();
	}

	private SpringApplicationBuilder application(Class<?>... sources) {
		return new SpringApplicationBuilder(sources).web(WebApplicationType.NONE).properties(Map.of(
				"spring.datasource.url", POSTGRES.getJdbcUrl(),
				"spring.datasource.username", POSTGRES.getUsername(),
				"spring.datasource.password", POSTGRES.getPassword(),
				"spring.datasource.driver-class-name", "org.postgresql.Driver",
				"spring.jpa.hibernate.ddl-auto", "validate",
				"spring.flyway.enabled", "true",
				"spring.flyway.locations", "classpath:db/migration"));
	}

	private JdbcTemplate jdbc() {
		return new JdbcTemplate(new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
	}

	private static void assertOutcome(JdbcTemplate jdbc, CommandId commandId, String outcome,
			String eventType, TerminalOutcome terminalOutcome) {
		assertEquals(1, count(jdbc, "select count(*) from command_outcomes where command_id=?",
				commandId.value()));
		assertEquals(outcome, jdbc.queryForObject(
				"select outcome_type from command_outcomes where command_id=?", String.class, commandId.value()));
		assertEquals(1, count(jdbc, "select count(*) from command_terminal_events where command_id=?",
				commandId.value()));
		assertEquals(eventType, jdbc.queryForObject(
				"select event_type from command_terminal_events where command_id=?", String.class, commandId.value()));
		assertEquals(terminalOutcome.name(), jdbc.queryForObject("""
				select terminal_outcome from consumption_slots
				where consumable_type='COMMAND'
				  and consumable_components=cast(? as jsonb)
				""", String.class, "[\"" + commandId.value() + "\"]"));
	}

	private static void assertTerminalTasks(JdbcTemplate jdbc, CommandId... commands) {
		for (CommandId command : commands) {
			assertEquals(1, count(jdbc, "select count(*) from projection_tasks "
					+ "where projection_type='COMMAND_RESULT' and target_object_type='COMMAND' "
					+ "and target_object_id=? and target_version=1", command.value().toString()));
		}
	}

	private static TransactionRunner directTransactions() {
		return new TransactionRunner() {
			@Override public <T> T runInTransaction(Supplier<T> action) { return action.get(); }
			@Override public void runAfterCommit(Runnable action) { action.run(); }
		};
	}

	private static int count(JdbcTemplate jdbc, String sql, Object... arguments) {
		return jdbc.queryForObject(sql, Integer.class, arguments);
	}

	private static void await(Supplier<Boolean> condition) throws InterruptedException {
		Instant deadline = Instant.now().plusSeconds(10);
		while (!condition.get() && Instant.now().isBefore(deadline)) Thread.sleep(20);
		assertEquals(true, condition.get());
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = {ReadStoreAccessAutoConfiguration.class,
			ReadStoreMigrationAutoConfiguration.class})
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.jpa.repository")
	@Import({CommandConsumptionRuntimeConfiguration.class, JpaRecordedCommandAdapter.class,
			JpaCommandConsumptionDiscoveryAdapter.class, JdbcCommandOutcomeAdapter.class,
			JpaRecordedCommandRepository.class, JpaCommandConsumptionDiscoveryRepository.class,
			ExternalIdentityJdbcRepository.class,
			JpaExternalIdentityResolverAdapter.class, JpaPotGlobalVersionAdapter.class,
			JpaPotContextAdapter.class, JpaExpenseContextAdapter.class, JpaPotHeaderAdapter.class,
			JpaPotShareholdersAdapter.class, JpaExpenseHeaderAdapter.class, JpaExpenseSharesAdapter.class,
			JpaPotCommandEventAppendAdapter.class})
	static class CommandTestApplication {}

	@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
	static class TerminalFailureAppendConfiguration {
		@Bean @Primary
		EventAppendPort failSecondAppend(JpaPotCommandEventAppendAdapter delegate) {
			AtomicInteger calls = new AtomicInteger();
			return events -> {
				if (calls.incrementAndGet() == 2) {
					throw new IllegalStateException("terminal failure after business mutation");
				}
				return delegate.appendAll(events);
			};
		}
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
	@Import(CommandResultReadConfiguration.class)
	static class ReadTestApplication {
		@Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
	}
}
