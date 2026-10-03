package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.engine.registration.ImmutableRegistrationResult;
import com.kartaguez.pocoma.engine.registration.RegistrationOutcome;
import com.kartaguez.pocoma.engine.registration.RegistrationResultStore;

@SpringBootTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.test",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://issuer.test/jwks"
})
@ActiveProfiles("postgres")
@Testcontainers
class RegistrationAdmissionPostgresTest {
    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("pocoma").withUsername("pocoma").withPassword("pocoma");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired WebApplicationContext context;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired RegistrationResultStore results;
    MockMvc http;

    @BeforeEach void clean() {
        http = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.execute("truncate table registration_requests cascade");
    }

    @Test void authenticatedRequestCommitsExactlyTheAttestedIdentityBefore202AndReplayIsNew() throws Exception {
        UUID first = submit("subject-1");
        assertEquals(1, count("registration_requests"));
        var row = jdbc.queryForMap("select issuer,subject,payload::text as payload from registration_requests where request_id=?", first);
        assertEquals("https://issuer.test", row.get("issuer"));
        assertEquals("subject-1", row.get("subject"));
        assertEquals("{}", row.get("payload"));
        UUID second = submit("subject-1");
        assertNotEquals(first, second);
        assertEquals(2, count("registration_requests"));
        for (String table : new String[] {"users", "external_identities", "external_identity_binding_facts",
                "business_event_outbox", "registration_outcomes", "user_created_facts", "command_outcomes"})
            assertEquals(0, count(table), table);
    }

    @Test void unauthenticatedAndSpoofedPayloadLeaveNoRequest() throws Exception {
        http.perform(post("/api/v1/registrations").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        Jwt withoutAuthenticationTime = Jwt.withTokenValue("test-token").header("alg", "none")
                .issuer("https://issuer.test").subject("real")
                .issuedAt(Instant.now().minusSeconds(10)).expiresAt(Instant.now().plusSeconds(300)).build();
        http.perform(post("/api/v1/registrations").with(jwt().jwt(withoutAuthenticationTime))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        http.perform(post("/api/v1/registrations").with(jwt().jwt(token("real")))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"issuer\":\"attacker\",\"subject\":\"attacker\"}"))
                .andExpect(status().isBadRequest());
        assertEquals(0, count("registration_requests"));
    }

    @Test void sqlRejectsMutation() throws Exception {
        UUID id = submit("immutable");
        assertThrows(Exception.class, () -> jdbc.update("update registration_requests set subject='attacker' where request_id=?", id));
        assertEquals("immutable", jdbc.queryForObject("select subject from registration_requests where request_id=?", String.class, id));
    }

    @Test void resultGetIsOpaqueAndOwnedByHistoricalRequestIssuer() throws Exception {
        UUID id = submit("owner");
        String path = "/api/v1/registrations/" + id + "/result";
        http.perform(get(path).with(jwt().jwt(token("owner")))).andExpect(status().isNotFound());
        results.ensureResult(new ImmutableRegistrationResult(
                new ExternalIdentity("https://issuer.test", "owner"), new RegistrationOutcome.Rejected(id)));
        http.perform(get(path).with(jwt().jwt(token("owner")))).andExpect(status().isOk());
        http.perform(get(path).with(jwt().jwt(token("other")))).andExpect(status().isNotFound());
        assertEquals("REJECTED", json.readTree(http.perform(get(path).with(jwt().jwt(token("owner"))))
                .andReturn().getResponse().getContentAsString()).path("status").asText());
    }

    private UUID submit(String subject) throws Exception {
        String body = http.perform(post("/api/v1/registrations").with(jwt().jwt(token(subject)))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).path("requestId").asText());
    }

    private static Jwt token(String subject) {
        Instant issued = Instant.now().minusSeconds(10);
        return Jwt.withTokenValue("test-token").header("alg", "none").issuer("https://issuer.test")
                .subject(subject).issuedAt(issued).expiresAt(Instant.now().plusSeconds(300))
                .claim("auth_time", issued.getEpochSecond()).build();
    }

    private int count(String table) { return jdbc.queryForObject("select count(*) from " + table, Integer.class); }
}
