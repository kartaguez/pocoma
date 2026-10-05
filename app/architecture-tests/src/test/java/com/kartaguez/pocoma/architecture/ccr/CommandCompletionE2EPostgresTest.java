package com.kartaguez.pocoma.architecture.ccr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
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
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.kartaguez.pocoma.CommandAdmissionConfiguration;
import com.kartaguez.pocoma.CommandResultReadConfiguration;
import com.kartaguez.pocoma.RegistrationAdmissionConfiguration;
import com.kartaguez.pocoma.supra.http.write.RegistrationController;
import com.kartaguez.pocoma.supra.http.read.RegistrationResultController;
import com.kartaguez.pocoma.RegistrationResultReadConfiguration;
import com.kartaguez.pocoma.PotReadConfiguration;
import com.kartaguez.pocoma.ProjectionReadConfiguration;
import com.kartaguez.pocoma.WebAuthorizationConfiguration;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.domain.authorization.ExternalAuthorityPermissionTranslator;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.contracts.command.CommandId;
import com.kartaguez.pocoma.contracts.command.CommandAuthenticationEvidence;
import com.kartaguez.pocoma.contracts.command.TargetCommandEnvelope;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.contracts.command.RecordedCommand;
import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.domain.useridentity.PocomaUserId;
import com.kartaguez.pocoma.engine.consume.command.port.out.EventAppendPort;
import com.kartaguez.pocoma.engine.consume.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.engine.admit.command.port.out.RecordedCommandInsertionPort;
import com.kartaguez.pocoma.engine.read.commandresult.GetCommandResult;
import com.kartaguez.pocoma.engine.read.commandresult.GetCommandResultUseCase;
import com.kartaguez.pocoma.engine.read.currentbinding.GetCurrentBindingUseCase;
import com.kartaguez.pocoma.engine.consume.command.pot.decode.PotCommandTypes;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.JpaPotGlobalVersionAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command.JpaCommandConsumptionDiscoveryAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command.JpaRecordedCommandAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command.JdbcCommandOutcomeAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.context.JpaExpenseContextAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.context.JpaPotContextAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaExpenseHeaderAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaExpenseSharesAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaPotHeaderAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.core.JpaPotShareholdersAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity.JpaExternalIdentityBindingAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.outbox.JpaPotCommandEventAppendAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection.JpaHistoricalPotBalanceSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection.JpaHistoricalPotSnapshotSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection.JpaProjectedExpenseAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection.JpaAuthProjectionInputLoader;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection.JpaReadPotProjectionInputLoader;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command.JdbcCommandResultStore;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command.JdbcCommandResultSource;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcRegistrationRequestStore;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcRegistrationOutcomeStore;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcRegistrationDiscovery;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcRegistrationResultStore;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcRegistrationResultDiscovery;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcRegistrationResultSource;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.registration.JdbcUserCreatedFactAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity.JpaUserAuthorityAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity.JdbcBindingFactDiscoveryAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.identity.JpaExternalIdentityBindingFactAdapter;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreAccessAutoConfiguration;
import com.kartaguez.pocoma.infra.read.persistence.ReadStoreMigrationAutoConfiguration;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.command.JpaCommandConsumptionDiscoveryRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.command.JpaRecordedCommandRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.ExternalIdentityBindingFactJdbcRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.identity.UserJdbcRepository;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunnerConfiguration;
import com.kartaguez.pocoma.runtime.command.consumption.CommandConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.event.consumption.EventConsumptionRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.result.CommandResultRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.registration.RegistrationRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.registrationresult.RegistrationResultRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.binding.BindingRuntimeConfiguration;
import com.kartaguez.pocoma.runtime.task.consumption.CanonicalProjectionTaskRuntimeConfiguration;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.supra.http.read.CommandResultController;
import com.kartaguez.pocoma.supra.http.read.CurrentBindingController;
import com.kartaguez.pocoma.supra.http.read.PotQueryController;
import com.kartaguez.pocoma.supra.http.write.AsyncCommandController;
import com.kartaguez.pocoma.runtime.web.authentication.WebApiSecurityConfiguration;

@Testcontainers
class CommandCompletionE2EPostgresTest {
	private static final String ISSUER = "https://ccr.e2e.test";
	private static final String SUBJECT = "ccr-e2e-user";
	private static final String TEST_TOKEN = "ccr-e2e-token";
	private static final Instant BASE_TIME = Instant.now().minusSeconds(5);
	private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma")
			.withCommand("postgres", "-c", "log_statement=all");

	@Test
	void admissionThroughCommandTerminalEventAndResultProducesAllTerminalResultsDurably() throws Exception {
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
			cleanDatabase(jdbc);
			BindingId bindingId = insertBinding(jdbc, userId);
			applied = admitTarget(commandContext, UUID.randomUUID(), BASE_TIME, bindingId,
					payload(appliedLabel, userId), Set.of("pocoma:pot:create"));
			rejected = admitTarget(commandContext, UUID.randomUUID(), BASE_TIME.plusMillis(1), bindingId,
					payload(rejectedLabel, userId), Set.of());
			failed = admitTarget(commandContext, UUID.randomUUID(), BASE_TIME.plusMillis(2), bindingId,
					payload(failedLabel, userId), Set.of("pocoma:pot:create"));

			assertEquals(3, count(jdbc, "select count(*) from recorded_commands"));
			assertEquals(3, count(jdbc,
					"select count(*) from recorded_commands where auth_subject is not null and binding_id is not null"));
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

		try (ConfigurableApplicationContext resultContext = resultContext()) {
			resultContext.getBean(ConsumptionPollingWorker.class).runOneCycle();
		}
		assertEquals(3, count(jdbc, "select count(*) from command_results"));
		try (ConfigurableApplicationContext restartedResultContext = resultContext()) {
			restartedResultContext.getBean(ConsumptionPollingWorker.class).runOneCycle();
		}
		assertEquals(3, count(jdbc, "select count(*) from command_results"));
		assertEquals(0, count(jdbc, "select count(*) from projection_tasks where projection_type='COMMAND_RESULT'"));
		assertEquals(0, count(jdbc, "select count(*) from pocoma_read.projection_root where projection_type='COMMAND_RESULT'"));
		UUID currentBindingId = jdbc.queryForObject(
				"select binding_id from external_identities where issuer=? and subject=?",
				UUID.class, ISSUER, SUBJECT);
		jdbc.update("""
				insert into pocoma_read.current_external_identity_binding
				(issuer,subject,binding_revision,binding_status,user_id,binding_id,source_event_id,projected_at)
				values (?, ?, 0, 'ATTACHED', ?, ?, ?, ?)
				on conflict (issuer,subject) do update set user_id=excluded.user_id,
				 binding_id=excluded.binding_id,binding_status='ATTACHED'
				""", ISSUER, SUBJECT, userId, currentBindingId, UUID.randomUUID(), java.sql.Timestamp.from(BASE_TIME));

		try (ConfigurableApplicationContext readContext = readContext()) {
			GetCommandResultUseCase results = readContext.getBean(GetCommandResultUseCase.class);
			CommandResultController controller = new CommandResultController(results);
			CurrentBindingController bindingController = new CurrentBindingController(
					readContext.getBean(GetCurrentBindingUseCase.class));
			var principal = principal(Set.of());
			String begin = "wa66_get_begin_" + UUID.randomUUID();
			String end = "wa66_get_end_" + UUID.randomUUID();
			jdbc.execute("select '" + begin + "'");

			var appliedResponse = controller.get(applied.value(), principal);
			assertEquals(HttpStatus.OK, appliedResponse.getStatusCode());
			assertEquals("APPLIED", appliedResponse.getBody().status());
			assertEquals(appliedPotId, appliedResponse.getBody().potId());
			assertEquals(1L, appliedResponse.getBody().resultingVersion());
			assertInstanceOf(GetCommandResult.Applied.class, results.get(applied, principal.identity()));

			var rejectedResponse = controller.get(rejected.value(), principal);
			assertEquals(HttpStatus.OK, rejectedResponse.getStatusCode());
			assertEquals("REJECTED", rejectedResponse.getBody().status());
			assertInstanceOf(GetCommandResult.Rejected.class, results.get(rejected, principal.identity()));

			var failedResponse = controller.get(failed.value(), principal);
			assertEquals(HttpStatus.OK, failedResponse.getStatusCode());
			assertEquals("FAILED", failedResponse.getBody().status());
			assertInstanceOf(GetCommandResult.Failed.class, results.get(failed, principal.identity()));
			assertEquals(HttpStatus.OK, bindingController.get(principal).getStatusCode());
			jdbc.execute("select '" + end + "'");

			String getSql = statementsBetween(begin, end).toLowerCase();
			assertTrue(getSql.contains("command_results"));
			assertTrue(getSql.contains("pocoma_read.current_external_identity_binding"));
			for (String forbidden : List.of("recorded_commands", "command_outcomes", "external_identities",
					"external_identity_binding_streams", "external_identity_binding_facts",
					"external_identity_binding_occurrences", "pot_headers",
					"business_event_outbox", "consumption_slots", "consumption_inputs", "consumption_results")) {
				assertFalse(getSql.contains(forbidden), "GET must not read primary table " + forbidden);
			}
		}
	}

	@Test
	void httpCreateAndUpdateTraverseWorkersAndReadAuthorizedExactPotVersions() throws Exception {
		JdbcTemplate jdbc = jdbc();
		UUID userId = UUID.randomUUID();
		String initialLabel = "pot-e2e-initial-" + UUID.randomUUID();
		String updatedLabel = "pot-e2e-updated-" + UUID.randomUUID();

		try (ConfigurableApplicationContext webContext = webContext()) {
			cleanDatabase(jdbc);
			BindingId bindingId = insertBinding(jdbc, userId);
			insertCurrentBinding(jdbc, userId, bindingId);
			int port = ((WebServerApplicationContext) webContext).getWebServer().getPort();
			HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
			String baseUrl = "http://127.0.0.1:" + port;
			ObjectMapper mapper = webContext.getBean(ObjectMapper.class);
			HttpResponse<String> selfService = get(http, baseUrl,
					"/api/v1/me/binding?issuer=https://attacker.invalid&subject=someone-else");
			assertEquals(200, selfService.statusCode(), selfService.body());
			JsonNode currentBinding = mapper.readTree(selfService.body());
			assertEquals(userId.toString(), currentBinding.path("userId").asText());
			assertEquals(bindingId.value().toString(), currentBinding.path("bindingId").asText());
			assertEquals(0, currentBinding.path("bindingRevision").asLong());
			assertEquals("ATTACHED", currentBinding.path("status").asText());

			UUID createCommandId = submit(http, baseUrl, mapper, bindingId,
					PotCommandTypes.POT_CREATE_V1.value(),
					Map.of("label", initialLabel, "creatorId", userId.toString()));
			assertEquals(SUBJECT, jdbc.queryForObject(
					"select auth_subject from recorded_commands where command_id=?",
					String.class, createCommandId));
			assertEquals(404, get(http, baseUrl, "/api/v1/command-results/" + createCommandId).statusCode());

			runPotPipeline();
			assertOutcome(jdbc, new CommandId(createCommandId), "APPLIED", "COMMAND_APPLIED",
					TerminalOutcome.SUCCESS);
			HttpResponse<String> createResult = get(http, baseUrl,
					"/api/v1/commands/" + createCommandId + "/result");
			assertEquals(200, createResult.statusCode());
			assertEquals("APPLIED", mapper.readTree(createResult.body()).get("status").asText());
			UUID potId = jdbc.queryForObject("select pot_id from pot_headers where label=?",
					UUID.class, initialLabel);
			assertPot(http, baseUrl, mapper, potId, 1, initialLabel);

			UUID updateCommandId = submit(http, baseUrl, mapper, bindingId,
					PotCommandTypes.POT_DETAILS_UPDATE_V1.value(),
					Map.of("potId", potId.toString(), "label", updatedLabel, "expectedVersion", 1));
			runPotPipeline();
			assertOutcome(jdbc, new CommandId(updateCommandId), "APPLIED", "COMMAND_APPLIED",
					TerminalOutcome.SUCCESS);
			assertEquals(200, get(http, baseUrl,
					"/api/v1/commands/" + updateCommandId + "/result").statusCode());
			assertPot(http, baseUrl, mapper, potId, 2, updatedLabel);
			assertPot(http, baseUrl, mapper, potId, 1, initialLabel);

			jdbc.update("""
					update pocoma_read.current_external_identity_binding
					set binding_status='DETACHED', user_id=null, binding_id=null
					where issuer=? and subject=?
					""", ISSUER, SUBJECT);
			assertEquals(404, get(http, baseUrl, "/api/v1/me/binding").statusCode());
			assertEquals(200, get(http, baseUrl,
					"/api/v1/commands/" + createCommandId + "/result").statusCode());
			jdbc.update("delete from pocoma_read.current_external_identity_binding where issuer=? and subject=?",
					ISSUER, SUBJECT);
			assertEquals(404, get(http, baseUrl, "/api/v1/me/binding").statusCode());
			UUID reboundUserId = UUID.randomUUID();
			insertCurrentBinding(jdbc, reboundUserId, new BindingId(UUID.randomUUID()));
			assertEquals(200, get(http, baseUrl,
					"/api/v1/commands/" + createCommandId + "/result").statusCode());
		}
	}

	@Test
	void registrationThroughBothReadConsumersFeedsFirstCommandAndRejectedRegistration() throws Exception {
		JdbcTemplate jdbc = jdbc();
		try (ConfigurableApplicationContext webContext = webContext()) {
			cleanDatabase(jdbc);
			int port = ((WebServerApplicationContext) webContext).getWebServer().getPort();
			String baseUrl = "http://127.0.0.1:" + port;
			HttpClient http = HttpClient.newBuilder().connectTimeout(HTTP_TIMEOUT).build();
			ObjectMapper mapper = webContext.getBean(ObjectMapper.class);
			UUID requestId = submitRegistration(http, baseUrl, mapper);
			assertEquals(0, count(jdbc, "select count(*) from registration_outcomes"));
			assertEquals(404, get(http, baseUrl, "/api/v1/registrations/" + requestId + "/result").statusCode());
			try (ConfigurableApplicationContext context = registrationContext()) {
				context.getBean(ConsumptionPollingWorker.class).runOneCycle();
			}
			assertEquals("REGISTERED", jdbc.queryForObject("select outcome_type from registration_outcomes where request_id=?",
					String.class, requestId));
			try (ConfigurableApplicationContext context = registrationResultContext()) {
				context.getBean(ConsumptionPollingWorker.class).runOneCycle();
			}
			HttpResponse<String> registrationResult = get(http, baseUrl,
					"/api/v1/registrations/" + requestId + "/result");
			assertEquals(200, registrationResult.statusCode(), registrationResult.body());
			JsonNode registered = mapper.readTree(registrationResult.body());
			assertEquals("REGISTERED", registered.path("status").asText());
			assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(baseUrl
					+ "/api/v1/registrations/" + requestId + "/result"))
					.header("Authorization", "Bearer other-e2e-token").GET().build(),
					HttpResponse.BodyHandlers.ofString()).statusCode());
			assertEquals(404, get(http, baseUrl, "/api/v1/me/binding").statusCode());
			try (ConfigurableApplicationContext context = bindingContext()) {
				context.getBean(ConsumptionPollingWorker.class).runOneCycle();
			}
			HttpResponse<String> currentResponse = get(http, baseUrl, "/api/v1/me/binding");
			assertEquals(200, currentResponse.statusCode(), currentResponse.body());
			JsonNode current = mapper.readTree(currentResponse.body());
			assertEquals("ATTACHED", current.path("status").asText());
			assertEquals(registered.path("bindingId").asText(), current.path("bindingId").asText());
			UUID userId = UUID.fromString(registered.path("userId").asText());
			BindingId bindingId = new BindingId(UUID.fromString(current.path("bindingId").asText()));
			UUID commandId = submit(http, baseUrl, mapper, bindingId,
					PotCommandTypes.POT_CREATE_V1.value(),
					Map.of("label", "registration-first-pot", "creatorId", userId.toString()));
			runPotPipeline();
			assertEquals(200, get(http, baseUrl, "/api/v1/command-results/" + commandId).statusCode());
			assertEquals("APPLIED", mapper.readTree(get(http, baseUrl,
					"/api/v1/command-results/" + commandId).body()).path("status").asText());

			int usersBefore = count(jdbc, "select count(*) from users");
			int bindingsBefore = count(jdbc, "select count(*) from external_identity_binding_occurrences");
			int attachedBefore = count(jdbc, "select count(*) from external_identity_binding_facts where fact_type='ATTACHED'");
			UUID rejectedId = submitRegistration(http, baseUrl, mapper);
			try (ConfigurableApplicationContext context = registrationContext()) {
				context.getBean(ConsumptionPollingWorker.class).runOneCycle();
			}
			try (ConfigurableApplicationContext context = registrationResultContext()) {
				context.getBean(ConsumptionPollingWorker.class).runOneCycle();
			}
			JsonNode rejected = mapper.readTree(get(http, baseUrl,
					"/api/v1/registrations/" + rejectedId + "/result").body());
			assertEquals("REJECTED", rejected.path("status").asText());
			assertEquals("EXTERNAL_IDENTITY_ALREADY_USED", rejected.path("code").asText());
			assertEquals(usersBefore, count(jdbc, "select count(*) from users"));
			assertEquals(bindingsBefore, count(jdbc, "select count(*) from external_identity_binding_occurrences"));
			assertEquals(attachedBefore, count(jdbc, "select count(*) from external_identity_binding_facts where fact_type='ATTACHED'"));
			try (ConfigurableApplicationContext context = registrationContext()) {
				var tx = context.getBean(TransactionRunner.class);
				var bindings = context.getBean(JpaExternalIdentityBindingAdapter.class);
				tx.runInTransaction(() -> { bindings.detach(new ExternalIdentity(ISSUER, SUBJECT), bindingId); return null; });
			}
			assertEquals(200, get(http, baseUrl, "/api/v1/registrations/" + requestId + "/result").statusCode());
            assertEquals(200, get(http, baseUrl, "/api/v1/command-results/" + commandId).statusCode());
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/registrations/" + requestId + "/result"))
                    .header("Authorization", "Bearer other-e2e-token").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/v1/command-results/" + commandId))
                    .header("Authorization", "Bearer other-e2e-token").GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
		}
	}

	private UUID submitRegistration(HttpClient http, String baseUrl, ObjectMapper mapper) throws Exception {
		HttpResponse<String> response = http.send(request(baseUrl, "/api/v1/registrations")
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
		assertEquals(202, response.statusCode(), response.body());
		return UUID.fromString(mapper.readTree(response.body()).path("requestId").asText());
	}

	private UUID submit(HttpClient http, String baseUrl, ObjectMapper mapper, BindingId bindingId,
			String commandType,
			Map<String, ?> payload)
			throws Exception {
		HttpResponse<String> response = http.send(request(baseUrl, "/api/v1/commands")
				.header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
				.POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(
						Map.of("commandType", commandType, "bindingId", bindingId.value(),
								"payload", payload)))).build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(202, response.statusCode(), response.body());
		return UUID.fromString(mapper.readTree(response.body()).path("commandId").asText());
	}

	private void assertPot(HttpClient http, String baseUrl, ObjectMapper mapper, UUID potId,
			long version, String label) throws Exception {
		HttpResponse<String> response = get(http, baseUrl,
				"/api/v1/pots/" + potId + "?version=" + version);
		assertEquals(200, response.statusCode(), response.body());
		JsonNode pot = mapper.readTree(response.body());
		assertEquals(potId.toString(), pot.path("potId").asText());
		assertEquals(version, pot.path("version").asLong());
		assertEquals(label, pot.path("label").asText());
	}

	private static HttpResponse<String> get(HttpClient http, String baseUrl, String path)
			throws IOException, InterruptedException {
		return http.send(request(baseUrl, path).GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private static HttpRequest.Builder request(String baseUrl, String path) {
		return HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(HTTP_TIMEOUT)
				.header("Authorization", "Bearer " + TEST_TOKEN);
	}

	private void runPotPipeline() {
		try (ConfigurableApplicationContext context = cleanCommandContext()) {
			context.getBean(ConsumptionPollingWorker.class).runOneCycle();
		}
		try (ConfigurableApplicationContext context = resultContext()) {
			context.getBean(ConsumptionPollingWorker.class).runOneCycle();
		}
		try (ConfigurableApplicationContext context = potEventContext()) {
			context.getBean(ConsumptionPollingWorker.class).runOneCycle();
		}
		try (ConfigurableApplicationContext context = potTaskContext()) {
			ConsumptionPollingWorker worker = context.getBean(ConsumptionPollingWorker.class);
			worker.runOneCycle();
			worker.runOneCycle();
			worker.runOneCycle();
		}
	}

	private CommandId admitTarget(ConfigurableApplicationContext context, UUID commandId,
			Instant submittedAt, BindingId bindingId,
			String serializedPayload, Set<String> authorities) {
		CommandId id = new CommandId(commandId);
		var envelope = new TargetCommandEnvelope(new ExternalIdentity(ISSUER, SUBJECT), bindingId,
				new CommandAuthenticationEvidence(authorities, submittedAt.plusSeconds(600)));
		context.getBean(TransactionRunner.class).runInTransaction(() ->
				context.getBean(RecordedCommandInsertionPort.class).insert(new RecordedCommand(
						id, PotCommandTypes.POT_CREATE_V1, serializedPayload, submittedAt, envelope)));
		return id;
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

	private ConfigurableApplicationContext cleanCommandContext() {
		return application(CommandTestApplication.class).properties(
				"pocoma.command-consumption.enabled=false",
				"pocoma.command-consumption.max-consumptions-executed=10").run();
	}

	private ConfigurableApplicationContext resultContext() {
		return application(ResultTestApplication.class).properties(
				"pocoma.command-result-consumption.enabled=false",
				"pocoma.command-result-consumption.max-consumptions-executed=10").run();
	}

	private ConfigurableApplicationContext registrationContext() {
		return application(RegistrationTestApplication.class).properties(
				"pocoma.registration-consumption.enabled=false").run();
	}

	private ConfigurableApplicationContext registrationResultContext() {
		return application(RegistrationResultTestApplication.class).properties(
				"pocoma.registration-result-consumption.enabled=false").run();
	}

	private ConfigurableApplicationContext bindingContext() {
		return application(BindingTestApplication.class).properties(
				"pocoma.binding-consumption.enabled=false").run();
	}

	private ConfigurableApplicationContext potEventContext() {
		return application(EventTestApplication.class).properties(
				"pocoma.event-consumption.enabled=false",
				"pocoma.event-consumption.projection-types=AUTH,READ_POT,POT_BALANCES",
				"pocoma.event-consumption.max-consumptions-executed=10").run();
	}

	private ConfigurableApplicationContext potTaskContext() {
		return application(TaskTestApplication.class).properties(
				"pocoma.projection-task-consumption.enabled=true",
				"pocoma.projection-task-consumption.catalog-projection-types=AUTH,READ_POT,POT_BALANCES",
				"pocoma.projection-task-consumption.locator-projection-types=AUTH,READ_POT,POT_BALANCES",
				"pocoma.projection-task-consumption.max-consumptions-executed=10",
				"pocoma.projection-task-consumption.poll-interval=1h").run();
	}

	private ConfigurableApplicationContext webContext() {
		return new SpringApplicationBuilder(WebTestApplication.class, JwtTestConfiguration.class)
				.web(WebApplicationType.SERVLET).properties(Map.ofEntries(
						Map.entry("server.port", "0"),
						Map.entry("spring.datasource.url", POSTGRES.getJdbcUrl()),
						Map.entry("spring.datasource.username", POSTGRES.getUsername()),
						Map.entry("spring.datasource.password", POSTGRES.getPassword()),
						Map.entry("spring.datasource.driver-class-name", "org.postgresql.Driver"),
						Map.entry("spring.jpa.hibernate.ddl-auto", "validate"),
						Map.entry("spring.flyway.enabled", "true"),
						Map.entry("spring.flyway.locations", "classpath:db/migration"),
						Map.entry("pocoma.command-admission.enabled", "true"),
						Map.entry("pocoma.registration-admission.enabled", "true"),
						Map.entry("pocoma.command-result-read.enabled", "true"),
						Map.entry("pocoma.pot-read.enabled", "true")))
				.run();
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

	private static int count(JdbcTemplate jdbc, String sql, Object... arguments) {
		return jdbc.queryForObject(sql, Integer.class, arguments);
	}

	private static void cleanDatabase(JdbcTemplate jdbc) {
		jdbc.execute("""
				truncate table external_identity_binding_facts, external_identity_binding_occurrences,
				 external_identity_binding_baseline_evidence, external_identity_binding_streams,
				 external_identities, users, recorded_commands, command_outcomes,
				 registration_results, registration_outcomes, user_created_facts, registration_requests,
				command_terminal_events, business_event_outbox, projection_tasks,
				consumption_inputs, consumption_results, consumption_slots, consumption_claims,
				expense_shares, expense_headers, shareholders, pot_headers,
				pot_version_metadata, pot_global_versions cascade
				""");
		if (jdbc.queryForObject("select to_regclass('pocoma_read.projection_root') is not null", Boolean.class)) {
			jdbc.execute("truncate table pocoma_read.projection_failure, pocoma_read.projection_artifact, "
					+ "pocoma_read.projection_root cascade");
		}
		if (jdbc.queryForObject("select to_regclass('pocoma_read.current_external_identity_binding') is not null",
				Boolean.class)) {
			jdbc.execute("truncate table pocoma_read.current_external_identity_binding");
		}
	}

	private static BindingId insertBinding(JdbcTemplate jdbc, UUID userId) {
		BindingId bindingId = new BindingId(UUID.randomUUID());
		jdbc.update("insert into users (user_id) values (?)", userId);
		jdbc.update("insert into external_identity_binding_streams values (?,?,0)", ISSUER, SUBJECT);
		jdbc.update("insert into external_identity_binding_occurrences values (?,?,?,?,0,now())",
				bindingId.value(), ISSUER, SUBJECT, userId);
		jdbc.update("insert into external_identities (issuer,subject,user_id,binding_id) values (?,?,?,?)",
				ISSUER, SUBJECT, userId, bindingId.value());
		return bindingId;
	}

	private static void insertCurrentBinding(JdbcTemplate jdbc, UUID userId, BindingId bindingId) {
		jdbc.update("""
				insert into pocoma_read.current_external_identity_binding
				(issuer,subject,binding_revision,binding_status,user_id,binding_id,source_event_id,projected_at)
				values (?, ?, 0, 'ATTACHED', ?, ?, ?, ?)
				""", ISSUER, SUBJECT, userId, bindingId.value(), UUID.randomUUID(), java.sql.Timestamp.from(BASE_TIME));
	}

	private static void await(Supplier<Boolean> condition) throws InterruptedException {
		Instant deadline = Instant.now().plusSeconds(10);
		while (!condition.get() && Instant.now().isBefore(deadline)) Thread.sleep(20);
		assertEquals(true, condition.get());
	}

	private static String statementsBetween(String begin, String end) {
		String logs = POSTGRES.getLogs();
		int start = logs.lastIndexOf(begin);
		int finish = logs.lastIndexOf(end);
		assertTrue(start >= 0 && finish > start, "SQL capture markers were not found in PostgreSQL logs");
		return logs.substring(start, finish);
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = {ReadStoreAccessAutoConfiguration.class,
			ReadStoreMigrationAutoConfiguration.class})
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
	@Import({CommandConsumptionRuntimeConfiguration.class, JpaRecordedCommandAdapter.class,
			JpaCommandConsumptionDiscoveryAdapter.class, JdbcCommandOutcomeAdapter.class,
			JpaRecordedCommandRepository.class, JpaCommandConsumptionDiscoveryRepository.class,
			ExternalIdentityJdbcRepository.class,
			JpaExternalIdentityBindingAdapter.class,
			JpaPotGlobalVersionAdapter.class,
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
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
	@Import(EventConsumptionRuntimeConfiguration.class)
	static class EventTestApplication {}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = {ReadStoreAccessAutoConfiguration.class,
			ReadStoreMigrationAutoConfiguration.class})
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
	@Import({CommandResultRuntimeConfiguration.class, JdbcCommandResultStore.class, JdbcCommandResultSource.class})
	static class ResultTestApplication {}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = {ReadStoreAccessAutoConfiguration.class,
			ReadStoreMigrationAutoConfiguration.class})
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
	@Import({RegistrationRuntimeConfiguration.class, JdbcRegistrationRequestStore.class,
			JdbcRegistrationOutcomeStore.class, JdbcRegistrationDiscovery.class,
			JdbcUserCreatedFactAdapter.class, JpaUserAuthorityAdapter.class,
			JpaExternalIdentityBindingAdapter.class, ExternalIdentityJdbcRepository.class,
			UserJdbcRepository.class,
			ExternalIdentityBindingFactJdbcRepository.class})
	static class RegistrationTestApplication {}

	@SpringBootConfiguration
	@EnableAutoConfiguration(exclude = {ReadStoreAccessAutoConfiguration.class,
			ReadStoreMigrationAutoConfiguration.class})
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
	@Import({RegistrationResultRuntimeConfiguration.class, JdbcRegistrationRequestStore.class,
			JdbcRegistrationOutcomeStore.class, JdbcRegistrationResultStore.class,
			JdbcRegistrationResultDiscovery.class, JdbcRegistrationResultSource.class})
	static class RegistrationResultTestApplication {}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
	@Import({BindingRuntimeConfiguration.class, JdbcBindingFactDiscoveryAdapter.class,
			JpaExternalIdentityBindingFactAdapter.class, ExternalIdentityBindingFactJdbcRepository.class})
	static class BindingTestApplication {}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
	@Import({CanonicalProjectionTaskRuntimeConfiguration.class, JdbcCommandOutcomeAdapter.class,
			JpaPotHeaderAdapter.class,
			JpaPotShareholdersAdapter.class, JpaExpenseHeaderAdapter.class, JpaExpenseSharesAdapter.class,
			JpaProjectedExpenseAdapter.class, JpaHistoricalPotSnapshotSourceAdapter.class,
			JpaHistoricalPotBalanceSourceAdapter.class, JpaAuthProjectionInputLoader.class,
			JpaReadPotProjectionInputLoader.class})
	static class TaskTestApplication {}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({ProjectionReadConfiguration.class, CommandResultReadConfiguration.class,
			JdbcCommandResultStore.class})
	static class ReadTestApplication {
		@Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
	}

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@EntityScan(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.entity")
	@EnableJpaRepositories(basePackages = "com.kartaguez.pocoma.infra.persistence.primary.jpa.repository")
	@Import({CommandAdmissionConfiguration.class, CommandResultReadConfiguration.class,
			RegistrationAdmissionConfiguration.class, RegistrationResultReadConfiguration.class,
			ProjectionReadConfiguration.class, PotReadConfiguration.class, WebAuthorizationConfiguration.class,
			SpringTransactionRunnerConfiguration.class, JpaRecordedCommandAdapter.class,
			JdbcRegistrationRequestStore.class, JdbcRegistrationResultStore.class,
			JpaRecordedCommandRepository.class,
			JdbcCommandResultStore.class,
			ExternalIdentityJdbcRepository.class,
			AsyncCommandController.class, CommandResultController.class, CurrentBindingController.class,
			RegistrationController.class, RegistrationResultController.class,
			PotQueryController.class,
			WebApiSecurityConfiguration.class})
	static class WebTestApplication {
		@Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
	}

	@org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
	static class JwtTestConfiguration {
		@Bean JwtDecoder jwtDecoder() {
			return token -> org.springframework.security.oauth2.jwt.Jwt.withTokenValue(token)
					.header("alg", "none").issuer(ISSUER).subject(token.equals("other-e2e-token") ? "other" : SUBJECT)
					.issuedAt(BASE_TIME).expiresAt(BASE_TIME.plusSeconds(600))
					.claim("auth_time", BASE_TIME.minusSeconds(1).getEpochSecond())
					.claim("scope", "pocoma:pot:create pocoma:pot:update pocoma:pot:view").build();
		}
	}
}
