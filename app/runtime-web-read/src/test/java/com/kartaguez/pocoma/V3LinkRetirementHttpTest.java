package com.kartaguez.pocoma;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class V3LinkRetirementHttpTest {
    @Container
    static final PostgreSQLContainer PG = V3ReadHttpFixture.container();

    @Test
    void retiredRoutesAndDirectAdmissionHaveNoEffects() throws Exception {
        try (var fixture = new V3ReadHttpFixture(PG)) {
            fixture.enableAdmission();
            fixture.start(PocomaWebReadApplication.class);
            var token = fixture.jwt("owner");
            var path = "/identity/link-requests";
            var id = UUID.randomUUID();
            var json = Map.of("Content-Type", "application/json");
            assertEquals(404, fixture.request("POST", path, token, null, null, json).statusCode());
            assertEquals(404, fixture.request("GET", path + "/" + id, token, null, null, Map.of()).statusCode());
            assertEquals(404, fixture.request("POST", path + "/" + id + "/accept", token, null, null, json).statusCode());
            var direct = fixture.request("POST", "/identity/requests", token, null,
                    "{\"operation\":\"Link\"}".getBytes(StandardCharsets.UTF_8),
                    Map.of("Content-Type", "application/json", "Idempotency-Key", id.toString()));
            assertEquals(400, direct.statusCode());
            assertEquals(0, fixture.admissionObserver.queryForObject("select count(*) from v3_identity_request", Integer.class));
            assertEquals(0, fixture.admissionObserver.queryForObject("select count(*) from v3_link_proposal", Integer.class));
        }
    }
}
