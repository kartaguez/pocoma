package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.sql.Timestamp;
import java.util.List;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.supra.http.rest.spring.filter.CommandRequestSizeFilter;

@SpringBootTest(properties = {
		"spring.jpa.hibernate.ddl-auto=validate",
		"pocoma.projection.worker.enabled=false",
		"pocoma.query.balance.pipeline-id=balance-projection",
		"pocoma.query.balance.pipeline-version=2",
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
			.withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");

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
		jdbc.execute("truncate table external_identities, recorded_commands, consumption_inputs, "
				+ "consumption_results, consumption_slots, consumption_claims, business_event_outbox, "
				+ "expense_shares, expense_headers, shareholders, pot_headers, pot_global_versions cascade");
	}

	@Test
	void authenticatedProvisionedIdentityDurablyAcceptsWithoutAnySynchronousEffects() throws Exception {
		UUID userId = UUID.randomUUID();
		jdbc.update("insert into external_identities (issuer,subject,pocoma_user_id) values (?,?,?)",
				ISSUER, SUBJECT, userId);
		Instant issuedAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS);
		Instant beforeSubmission = Instant.now().minusSeconds(1);

		String response = http.perform(post("/api/v1/commands")
				.with(jwt().jwt(token(issuedAt, expiresAt, SUBJECT)))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"commandType\":\"FUTURE_COMMAND_V1\",\"payload\":{\"business\":\"invalid-but-opaque\"}}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("ACCEPTED"))
				.andReturn().getResponse().getContentAsString();

		UUID commandId = UUID.fromString(objectMapper.readTree(response).path("commandId").asText());
		var row = jdbc.queryForMap("select * from recorded_commands where command_id=?", commandId);
		assertEquals("FUTURE_COMMAND_V1", row.get("command_type"));
		assertEquals("{\"business\":\"invalid-but-opaque\"}", row.get("payload_json"));
		assertEquals(userId, row.get("auth_user_id"));
		assertEquals(ISSUER, row.get("auth_issuer"));
		assertEquals(issuedAt, ((Timestamp) row.get("auth_authenticated_at")).toInstant());
		assertEquals(issuedAt, ((Timestamp) row.get("auth_issued_at")).toInstant());
		assertEquals(expiresAt, ((Timestamp) row.get("auth_valid_until")).toInstant());
		Instant submittedAt = ((Timestamp) row.get("submitted_at")).toInstant();
		assertTrue(!submittedAt.isBefore(beforeSubmission) && !submittedAt.isAfter(Instant.now().plusSeconds(1)));
		assertTrue(row.get("auth_permissions_json").toString().contains("POT"));
		assertTrue(row.get("auth_permissions_json").toString().contains("EXPENSE"));
		assertTrue(!row.get("auth_permissions_json").toString().contains("future"));
		assertEquals(0, count("consumption_slots"));
		assertEquals(0, count("consumption_claims"));
		assertEquals(0, count("business_event_outbox"));
		assertEquals(0, count("pot_headers"));
	}

	@Test
	void rejectsMissingAuthenticationAndUnprovisionedIdentitiesWithoutRecording() throws Exception {
		http.perform(post("/api/v1/commands").contentType(MediaType.APPLICATION_JSON)
				.content("{\"commandType\":\"TYPE\",\"payload\":{}}"))
				.andExpect(status().isUnauthorized());
		http.perform(post("/api/v1/commands")
				.with(jwt().jwt(token(Instant.now().minusSeconds(10), Instant.now().plusSeconds(300), "unknown")))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"commandType\":\"TYPE\",\"payload\":{}}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("USER_NOT_PROVISIONED"));
		assertEquals(0, count("recorded_commands"));
	}

	@Test
	void rejectsAnAuthenticatedTokenThatDoesNotMeetThePocomaAuthTimeProfile() throws Exception {
		Instant issuedAt = Instant.now().minusSeconds(10);
		Jwt withoutAuthTime = Jwt.withTokenValue("test-token").header("alg", "none")
				.issuer(ISSUER).subject(SUBJECT).issuedAt(issuedAt)
				.expiresAt(Instant.now().plusSeconds(300)).claim("scope", "pocoma:pot:create").build();

		http.perform(post("/api/v1/commands").with(jwt().jwt(withoutAuthTime))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"commandType\":\"TYPE\",\"payload\":{}}"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_AUTHENTICATED_PRINCIPAL"));
		assertEquals(0, count("recorded_commands"));
	}

	@Test
	void rejectsAnOversizedRequestBeforeAdmission() throws Exception {
		String content = "{\"commandType\":\"TYPE\",\"payload\":{\"value\":\"" + "x".repeat(600) + "\"}}";
		http.perform(post("/api/v1/commands")
				.with(jwt().jwt(token(Instant.now().minusSeconds(10), Instant.now().plusSeconds(300), SUBJECT)))
				.contentType(MediaType.APPLICATION_JSON).content(content))
				.andExpect(status().isPayloadTooLarge())
				.andExpect(jsonPath("$.code").value("COMMAND_PAYLOAD_TOO_LARGE"));
		assertEquals(0, count("recorded_commands"));
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
}
