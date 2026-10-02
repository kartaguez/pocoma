package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"pocoma.command-admission.authorization-ttl=PT15M",
		"pocoma.command-admission.max-request-bytes=512",
		"spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test",
		"spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.test/jwks"
})
@ActiveProfiles("postgres")
@Testcontainers
class CommandAdmissionPostgresTest {
	private static final String ISSUER = "https://issuer.test";
	private static final String SUBJECT = "external-subject";

	@Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma")
			.withCommand("postgres", "-c", "log_statement=all");

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@Autowired private WebApplicationContext context;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private ObjectMapper objectMapper;
	@Autowired private CommandRequestSizeFilter commandRequestSizeFilter;
	private MockMvc http;

	@BeforeEach
	void cleanDatabase() {
		http = MockMvcBuilders.webAppContextSetup(context).addFilters(commandRequestSizeFilter)
				.apply(springSecurity()).build();
		jdbc.execute("truncate table external_identity_binding_facts, external_identity_binding_occurrences, external_identity_binding_streams, external_identities, users, recorded_commands, consumption_inputs, "
				+ "consumption_results, consumption_slots, consumption_claims, business_event_outbox, "
				+ "command_outcomes, command_terminal_events, projection_tasks, "
				+ "expense_shares, expense_headers, shareholders, pot_headers, pot_global_versions cascade");
	}

	@Test
	void knownIdentityAndCurrentBindingPersistExactTargetEnvelopeWithoutPrimarySelect() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID bindingId = UUID.randomUUID();
		jdbc.update("insert into users (user_id) values (?)", userId);
		reserveCurrentBinding(userId, bindingId);
		jdbc.update("insert into external_identities (issuer,subject,user_id,binding_id) values (?,?,?,?)",
				ISSUER, SUBJECT, userId, bindingId);
		Instant issuedAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);
		String begin = "wa5_begin_" + UUID.randomUUID();
		String end = "wa5_end_" + UUID.randomUUID();
		jdbc.queryForObject("select ?", String.class, begin);

		UUID commandId = submit(SUBJECT, bindingId, issuedAt, expiresAt,
				"{\"commandType\":\"FUTURE_COMMAND_V1\",\"bindingId\":\"" + bindingId
						+ "\",\"externalIdentity\":{\"issuer\":\"attacker\",\"subject\":\"attacker\"},"
						+ "\"payload\":{\"business\":\"invalid-but-opaque\"}}");

		jdbc.queryForObject("select ?", String.class, end);
		String admissionSql = statementsBetween(begin, end).toLowerCase();
		assertFalse(admissionSql.contains(" from users"), admissionSql);
		assertFalse(admissionSql.contains(" from external_identities"), admissionSql);
		assertFalse(admissionSql.contains(" from pot_"), admissionSql);
		assertFalse(admissionSql.contains(" from business_event_outbox"), admissionSql);
		assertFalse(admissionSql.contains(" from consumption_"), admissionSql);
		assertFalse(admissionSql.contains(" from projection_"), admissionSql);
		assertFalse(admissionSql.contains(" from command_outcomes"), admissionSql);
		assertFalse(admissionSql.contains(" from command_terminal_events"), admissionSql);

		var row = jdbc.queryForMap("select * from recorded_commands where command_id=?", commandId);
		assertEquals("FUTURE_COMMAND_V1", row.get("command_type"));
		assertEquals("{\"business\":\"invalid-but-opaque\"}", row.get("payload_json"));
		assertEquals(2, ((Number) row.get("envelope_version")).intValue());
		assertNull(row.get("auth_user_id"));
		assertEquals(ISSUER, row.get("auth_issuer"));
		assertEquals(SUBJECT, row.get("auth_subject"));
		assertEquals(bindingId, row.get("binding_id"));
		assertNull(row.get("auth_permissions_json"));
		assertNull(row.get("auth_authenticated_at"));
		assertNull(row.get("auth_issued_at"));
		assertEquals(expiresAt, ((Timestamp) row.get("auth_valid_until")).toInstant());
		JsonNode authorities = objectMapper.readTree(row.get("auth_external_authorities_json").toString());
		assertEquals(Set.of("pocoma:pot:create", "pocoma:expense:update", "future:value"),
				objectMapper.convertValue(authorities,
						objectMapper.getTypeFactory().constructCollectionType(Set.class, String.class)));
		assertNoSynchronousEffects();
	}

	@Test
	void unknownFalseAndDetachedBindingsAreIndistinguishablyAcceptedAsTargetV2() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID currentBinding = UUID.randomUUID();
		jdbc.update("insert into users (user_id) values (?)", userId);
		reserveCurrentBinding(userId, currentBinding);
		jdbc.update("insert into external_identities (issuer,subject,user_id,binding_id) values (?,?,?,?)",
				ISSUER, SUBJECT, userId, currentBinding);
		Instant issuedAt = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);

		UUID falseBinding = UUID.randomUUID();
		UUID first = submit(SUBJECT, falseBinding, issuedAt, expiresAt, body(falseBinding));
		UUID detachedBinding = UUID.randomUUID();
		UUID second = submit("detached-subject", detachedBinding, issuedAt, expiresAt, body(detachedBinding));
		UUID unknownBinding = UUID.randomUUID();
		UUID third = submit("unknown-subject", unknownBinding, issuedAt, expiresAt, body(unknownBinding));

		assertEquals(3, count("recorded_commands"));
		assertTarget(first, SUBJECT, falseBinding);
		assertTarget(second, "detached-subject", detachedBinding);
		assertTarget(third, "unknown-subject", unknownBinding);
		assertNoSynchronousEffects();
	}

	@Test
	void missingEmptyAndMalformedBindingAreStructuralRejectionsWithoutDurableRow() throws Exception {
		Instant issuedAt = Instant.now().minusSeconds(10);
		Instant expiresAt = Instant.now().plusSeconds(300);
		http.perform(post("/api/v1/commands").with(jwt().jwt(token(issuedAt, expiresAt, SUBJECT)))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"commandType\":\"TYPE\",\"payload\":{}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_BINDING_ID"));
		for (String invalid : new String[] {"", "not-a-uuid"}) {
			http.perform(post("/api/v1/commands").with(jwt().jwt(token(issuedAt, expiresAt, SUBJECT)))
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"commandType\":\"TYPE\",\"bindingId\":\"" + invalid
							+ "\",\"payload\":{}}"))
					.andExpect(status().isBadRequest());
		}
		assertEquals(0, count("recorded_commands"));
	}

	@Test
	void rejectsMissingAuthenticationAndInvalidAuthenticatedPrincipalWithoutRecording() throws Exception {
		UUID bindingId = UUID.randomUUID();
		http.perform(post("/api/v1/commands").contentType(MediaType.APPLICATION_JSON)
				.content(body(bindingId)))
				.andExpect(status().isUnauthorized());
		Instant issuedAt = Instant.now().minusSeconds(10);
		Jwt withoutAuthTime = Jwt.withTokenValue("test-token").header("alg", "none")
				.issuer(ISSUER).subject(SUBJECT).issuedAt(issuedAt)
				.expiresAt(Instant.now().plusSeconds(300)).claim("scope", "pocoma:pot:create").build();
		http.perform(post("/api/v1/commands").with(jwt().jwt(withoutAuthTime))
				.contentType(MediaType.APPLICATION_JSON).content(body(bindingId)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_AUTHENTICATED_PRINCIPAL"));
		assertEquals(0, count("recorded_commands"));
	}

	@Test
	void rejectsAnOversizedRequestBeforeAdmission() throws Exception {
		UUID bindingId = UUID.randomUUID();
		String content = "{\"commandType\":\"TYPE\",\"bindingId\":\"" + bindingId
				+ "\",\"payload\":{\"value\":\"" + "x".repeat(600) + "\"}}";
		http.perform(post("/api/v1/commands")
				.with(jwt().jwt(token(Instant.now().minusSeconds(10), Instant.now().plusSeconds(300), SUBJECT)))
				.contentType(MediaType.APPLICATION_JSON).content(content))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.code").value("COMMAND_PAYLOAD_TOO_LARGE"));
		assertEquals(0, count("recorded_commands"));
	}

	private UUID submit(String subject, UUID bindingId, Instant issuedAt, Instant expiresAt, String body)
			throws Exception {
		String response = http.perform(post("/api/v1/commands")
				.with(jwt().jwt(token(issuedAt, expiresAt, subject)))
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andReturn().getResponse().getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).path("commandId").asText());
	}

	private static String body(UUID bindingId) {
		return "{\"commandType\":\"TYPE\",\"bindingId\":\"" + bindingId + "\",\"payload\":{}}";
	}

	private void assertTarget(UUID commandId, String subject, UUID bindingId) {
		var row = jdbc.queryForMap("select envelope_version, auth_subject, binding_id, auth_user_id, "
				+ "auth_permissions_json from recorded_commands where command_id=?", commandId);
		assertEquals(2, ((Number) row.get("envelope_version")).intValue());
		assertEquals(subject, row.get("auth_subject"));
		assertEquals(bindingId, row.get("binding_id"));
		assertNull(row.get("auth_user_id"));
		assertNull(row.get("auth_permissions_json"));
	}

	private void assertNoSynchronousEffects() {
		assertEquals(0, count("consumption_slots"));
		assertEquals(0, count("consumption_claims"));
		assertEquals(0, count("business_event_outbox"));
		assertEquals(0, count("command_outcomes"));
		assertEquals(0, count("command_terminal_events"));
		assertEquals(0, count("projection_tasks"));
		assertEquals(0, count("pot_headers"));
	}

	private String statementsBetween(String begin, String end) {
		String logs = POSTGRES.getLogs();
		int start = logs.lastIndexOf(begin);
		int finish = logs.indexOf(end, start + begin.length());
		assertTrue(start >= 0 && finish > start, "SQL capture markers were not found in PostgreSQL logs");
		return logs.substring(start + begin.length(), finish);
	}

	private Jwt token(Instant issuedAt, Instant expiresAt, String subject) {
		return Jwt.withTokenValue("test-token").header("alg", "none")
				.issuer(ISSUER).subject(subject).issuedAt(issuedAt).expiresAt(expiresAt)
				.claim("auth_time", issuedAt.getEpochSecond())
				.claim("scope", "pocoma:pot:create pocoma:expense:update future:value")
				.build();
	}

	private int count(String table) {
		return jdbc.queryForObject("select count(*) from " + table, Integer.class);
	}

	private void reserveCurrentBinding(UUID userId, UUID bindingId) {
		jdbc.update("insert into external_identity_binding_streams values (?,?,0)", ISSUER, SUBJECT);
		jdbc.update("insert into external_identity_binding_occurrences values (?,?,?,?,0,now())",
				bindingId, ISSUER, SUBJECT, userId);
	}
}
