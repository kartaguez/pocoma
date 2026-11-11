package com.kartaguez.pocoma;

import static com.kartaguez.pocoma.V3ReadHttpFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.InvitationCryptography;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;

/** Standalone WEB: one READ pool and no WRITE fallback, independent of the producer roots. */
@Testcontainers
class V3InvitationStandaloneWebPostgresTest {
    @Container static final PostgreSQLContainer PG = container();
    V3ReadHttpFixture f;
    UUID request;
    String secret;

    @BeforeEach
    void setup() throws Exception {
        f = new V3ReadHttpFixture(PG);
        Flyway.configure()
                .dataSource(f.url, PG.getUsername(), PG.getPassword())
                .schemas(f.schema)
                .defaultSchema(f.schema)
                .locations(
                        "classpath:db/v3-consumption-read/migration",
                        "classpath:db/v3-read-business/migration",
                        "classpath:db/v3-read-invitation/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                "i1_read",
                                "expectedSchema",
                                f.schema,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                f.role))
                .load()
                .migrate();
        f.admin.execute("revoke all on read_current_invitation from " + f.role);
        f.admin.execute("grant select on read_current_invitation to " + f.role);
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var crypto = new InvitationCryptography("old", Map.of("old", key));
        secret = crypto.newToken();
        request = UUID.randomUUID();
        var i =
                new Invitation(
                        UUID.randomUUID(),
                        1,
                        request,
                        f.issuer,
                        "owner",
                        POT,
                        Instant.parse("2030-01-01T00:00:00Z"),
                        InvitationTokenHash.of(secret),
                        "unsealed");
        var protectedInvitation =
                new Invitation(
                        i.invitationId(),
                        1,
                        request,
                        i.creatorIssuer(),
                        i.creatorSubject(),
                        POT,
                        i.expiresAt(),
                        i.tokenHash(),
                        crypto.encrypt(i, "pocoma://invitation/" + secret));
        f.injector.write(
                () -> {
                    new com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.JdbcInvitationStore(
                                    f.admin.getDataSource())
                            .put(protectedInvitation);
                    return null;
                });
        f.properties.put("pocoma.v3.invitation.current-key", "old");
        f.properties.put("pocoma.v3.invitation.key-ids", "old");
        f.properties.put("pocoma.v3.invitation.keys.old", Base64.getEncoder().encodeToString(key));
        f.seed(POT, 1, 0, Set.of(OWNER), false, true, true, "Standalone");
        f.start(PocomaWebApiApplication.class);
    }

    @AfterEach
    void close() throws Exception {
        if (f != null) f.close();
    }

    @Test
    void exactEuiBindingIndependenceAndMissingKey() throws Exception {
        var own = f.get("/invitations/by-creation-request/" + request);
        assertEquals(200, own.statusCode());
        assertTrue(f.body(own).get("link").asText().endsWith(secret));
        assertEquals(
                404,
                f.request(
                                "GET",
                                "/invitations/by-creation-request/" + request,
                                f.jwt("same"),
                                null,
                                null,
                                Map.of())
                        .statusCode(),
                "Sharing a User grants no link access");
        f.binding("owner", 2, null, null);
        assertEquals(200, f.get("/invitations/by-creation-request/" + request).statusCode());
        assertEquals(2, f.context.getBeansOfType(HikariDataSource.class).size());
            assertEquals(1, f.context.getBean("v3HttpReadMaintenancePool",HikariDataSource.class).getMaximumPoolSize());
            assertTrue(f.context.getBean("v3HttpReadMaintenancePool",HikariDataSource.class).isReadOnly());
        assertEquals(2, f.context.getBean("v3HttpReadPool", HikariDataSource.class).getMaximumPoolSize());
        f.context.close();
        byte[] replacement = new byte[32];
        new java.security.SecureRandom().nextBytes(replacement);
        f.properties.remove("pocoma.v3.invitation.keys.old");
        f.properties.put("pocoma.v3.invitation.current-key", "new");
        f.properties.put("pocoma.v3.invitation.key-ids", "new");
        f.properties.put(
                "pocoma.v3.invitation.keys.new", Base64.getEncoder().encodeToString(replacement));
        f.start(PocomaWebApiApplication.class);
        assertEquals(503, f.get("/invitations/by-creation-request/" + request).statusCode());
        var foreign =
                f.request(
                        "GET",
                        "/invitations/by-creation-request/" + request,
                        f.jwt("same"),
                        null,
                        null,
                        Map.of());
        assertEquals(404, foreign.statusCode());
        assertFalse(
                new String(foreign.body(), java.nio.charset.StandardCharsets.UTF_8)
                        .contains(secret));
    }

    @Test
    void restrictedPlacesWithoutWriteOrDecryption() throws Exception {
        var response =
                f.request(
                        "POST",
                        "/invitations/available-shareholders/query",
                        f.jwt("other"),
                        OTHER_TOKEN,
                        f.json.writeValueAsBytes(Map.of("token", secret)),
                        Map.of("Content-Type", "application/json"));
        assertEquals(200, response.statusCode());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(2, f.body(response).get("shareholders").size());
        assertEquals(
                404,
                f.request("GET", "/pots/" + POT, f.jwt("other"), OTHER_TOKEN, null, Map.of())
                        .statusCode());
        assertEquals(
                401,
                f.request("GET", "/invitations/by-creation-request/" + request, null, null, null, Map.of())
                        .statusCode());
        assertEquals(
                400,
                f.request(
                                "POST",
                                "/invitations/available-shareholders/query",
                                f.jwt("other"),
                                OTHER_TOKEN,
                                f.json.writeValueAsBytes(Map.of("token", secret, "userId", OWNER)),
                                Map.of("Content-Type", "application/json"))
                        .statusCode());
        assertEquals(2, f.context.getBeansOfType(HikariDataSource.class).size());
            assertEquals(1, f.context.getBean("v3HttpReadMaintenancePool",HikariDataSource.class).getMaximumPoolSize());
            assertTrue(f.context.getBean("v3HttpReadMaintenancePool",HikariDataSource.class).isReadOnly());
    }
}
