package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.binding.pot.command.spring.PotCommandBindingConfiguration;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalOutcome;
import com.kartaguez.pocoma.engine.command.execution.ExecuteRecordedCommandUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.pot.command.decode.PotCommandTypes;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.command.JpaCommandConsumptionDiscoveryAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionClaimRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionInputRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionResultRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.locator.consumption.command.CommandConsumptionExecution;
import com.kartaguez.pocoma.locator.consumption.command.CommandConsumptionLocator;
import com.kartaguez.pocoma.locator.consumption.command.failure.CommandConsumptionFailurePolicy;
import com.kartaguez.pocoma.locator.consumption.command.failure.CommandConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.SequentialConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorkerObservation;
import com.kartaguez.pocoma.supra.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.supra.consumption.wait.ConditionConsumptionWaiter;
import com.kartaguez.pocoma.supra.http.rest.spring.filter.CommandRequestSizeFilter;

/**
 * Functional cross-runtime contract through committed PostgreSQL state. Both runtime roles are
 * assembled in one test JVM; Docker networking and independent process lifecycles are out of scope.
 */
@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true",
		"pocoma.query.balance.pipeline-version=2",
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test",
		"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.test/jwks"
})
@Import(PotCommandBindingConfiguration.class)
@Testcontainers
@DirtiesContext
class WriteSideClosurePostgresTest {

	private static final String ISSUER = "https://issuer.test";
	private static final String SUBJECT = "write-side-user";

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired WebApplicationContext context;
	@Autowired CommandRequestSizeFilter commandRequestSizeFilter;
	@Autowired ObjectMapper mapper;
	@Autowired JdbcTemplate jdbc;
	@Autowired TransactionRunner transactions;
	@Autowired ExecuteRecordedCommandUseCase commands;
	@Autowired JpaCommandConsumptionDiscoveryAdapter discovery;
	@Autowired JpaConsumptionSlotRepository slots;
	@Autowired JpaConsumptionClaimRepository claims;
	@Autowired JpaConsumptionInputRepository inputs;
	@Autowired JpaConsumptionResultRepository results;
	@Autowired Clock clock;
	private MockMvc http;
	private ConsumptionPollingWorker worker;

	@BeforeEach
	void cleanAndAssembleRuntimeRoles() {
		http = MockMvcBuilders.webAppContextSetup(context).addFilters(commandRequestSizeFilter)
				.apply(springSecurity()).build();
		jdbc.execute("truncate table external_identities, recorded_commands, consumption_inputs, "
				+ "consumption_results, consumption_slots, consumption_claims, business_event_outbox, "
				+ "expense_shares, expense_headers, shareholders, pot_headers, pot_global_versions cascade");
		worker = pollingWorker();
	}

	@AfterEach
	void stopWorker() throws InterruptedException {
		if (worker == null) return;
		CountDownLatch stopped = new CountDownLatch(1);
		worker.requestStop(stopped::countDown);
		assertTrue(stopped.await(5, TimeUnit.SECONDS), "polling worker did not stop");
	}

	@Test
	void committedHttpAdmissionIsConsumedThroughTheRealPollingLoop() throws Exception {
		UUID userId = UUID.randomUUID();
		String label = "write-side-closure-" + UUID.randomUUID();
		jdbc.update("insert into external_identities (issuer,subject,pocoma_user_id) values (?,?,?)",
				ISSUER, SUBJECT, userId);
		String payload = mapper.writeValueAsString(java.util.Map.of("label", label, "creatorId", userId));
		String body = mapper.writeValueAsString(java.util.Map.of(
				"commandType", PotCommandTypes.POT_CREATE_V1.value(),
				"payload", mapper.readTree(payload)));

		String response = http.perform(post("/api/v1/commands")
				.with(jwt().jwt(token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andReturn().getResponse().getContentAsString();
		UUID commandId = UUID.fromString(mapper.readTree(response).path("commandId").asText());

		assertEquals(1, count("recorded_commands", "command_id", commandId));
		worker.start();
		await(() -> count("pot_headers", "label", label) == 1);

		assertEquals(1, count("pot_headers", "label", label));
		assertEquals(1, jdbc.queryForObject("select count(*) from business_event_outbox", Integer.class));
		assertEquals("DONE", jdbc.queryForObject(
				"select status from consumption_slots where consumable_type='COMMAND'", String.class));
		assertEquals(TerminalOutcome.SUCCESS.name(), jdbc.queryForObject(
				"select terminal_outcome from consumption_slots where consumable_type='COMMAND'", String.class));
		assertEquals(0, jdbc.queryForObject(
				"select count(*) from consumption_slots where current_claim_id is not null", Integer.class));
	}

	private ConsumptionPollingWorker pollingWorker() {
		var lifecycle = new JpaConsumptionLifecycleAdapter(slots, claims, mapper);
		var provenance = new JpaConsumptionProvenanceAdapter(inputs, results);
		var acquire = new TransactionalAcquireConsumptionUseCase(
				new AcquireConsumptionService(lifecycle, clock), transactions);
		var execute = new TransactionalExecuteConsumptionUseCase(
				new ExecuteConsumptionService(lifecycle, provenance, clock), transactions);
		var failure = new TransactionalHandleConsumptionFailureUseCase(
				new HandleConsumptionFailureService(
						lifecycle, lifecycle, new CommandConsumptionFailurePolicy(), clock), transactions);
		var locator = new CommandConsumptionLocator(
				discovery, new CommandConsumptionExecution(commands),
				new CommandConsumptionTechnicalFailureClassifier(clock), clock);
		var orchestrator = new SequentialConsumptionOrchestrator(locator, acquire, execute, failure);
		var settings = new ConsumptionWorkerSettings(true, new WorkerId("write-side-closure-test"),
				new ClaimLease(Duration.ofSeconds(30)), new ConsumptionOrchestrationBudget(100, 10),
				Duration.ofMillis(20), Duration.ofMillis(20));
		return new ConsumptionPollingWorker(orchestrator, settings, clock,
				new ConditionConsumptionWaiter(), ConsumptionPollingWorkerObservation.noop());
	}

	private Jwt token() {
		Instant issuedAt = Instant.now().minusSeconds(10);
		return Jwt.withTokenValue("test-token").header("alg", "none")
				.issuer(ISSUER).subject(SUBJECT).issuedAt(issuedAt).expiresAt(Instant.now().plusSeconds(300))
				.claim("auth_time", issuedAt.getEpochSecond())
				.claim("scope", "pocoma:pot:create")
				.build();
	}

	private int count(String table, String column, Object value) {
		return jdbc.queryForObject(
				"select count(*) from " + table + " where " + column + "=?", Integer.class, value);
	}

	private static void await(BooleanSupplier condition) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("test interrupted", exception);
			}
		}
		assertTrue(condition.getAsBoolean(), "Command was not consumed before timeout");
	}
}
