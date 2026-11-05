package com.kartaguez.pocoma;

import static com.kartaguez.pocoma.V3CompositionHttpFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.kartaguez.pocoma.runtime.consumption.v3.V3ConsumptionWorkerSettings;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.*;
import java.util.*;

/** CURRENT WRITE/READ fixture only. Its ephemeral keys are never external environment keys. */
@Testcontainers
abstract class V3InvitationFixture {
    @Container static final PostgreSQLContainer PG = V3AdmissionFlowPostgresTest.PG;

    @BeforeAll
    static void databases() {
        initializeWorkerDatabase(PG);
    }

    V3AdmissionFlowPostgresTest f;

    @BeforeEach
    void setupInvitation(TestInfo test) throws Exception {
        f = new V3AdmissionFlowPostgresTest();
        f.setup(test);
        Flyway.configure()
                .dataSource(f.worker.writeUrl, PG.getUsername(), PG.getPassword())
                .schemas(f.worker.schema)
                .defaultSchema(f.worker.schema)
                .locations(
                        "classpath:db/v3-pot/migration",
                        "classpath:db/v3-pot-execution/migration",
                        "classpath:db/v3-consumption-write/migration",
                        "classpath:db/v3-identity/migration",
                        "classpath:db/v3-admission/migration",
                        "classpath:db/v3-invitation/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                PG.getDatabaseName(),
                                "expectedSchema",
                                f.worker.schema,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                f.worker.writeRole))
                .load()
                .migrate();
        Flyway.configure()
                .dataSource(f.worker.readUrl, PG.getUsername(), PG.getPassword())
                .schemas(f.worker.schema)
                .defaultSchema(f.worker.schema)
                .locations(
                        "classpath:db/v3-consumption-read/migration",
                        "classpath:db/v3-read-business/migration",
                        "classpath:db/v3-read-invitation/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                "i5_read",
                                "expectedSchema",
                                f.worker.schema,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                f.worker.readRole))
                .load()
                .migrate();
        byte[] key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        f.http.properties.put("pocoma.v3.invitation.current-key", "fixture");
        f.http.properties.put("pocoma.v3.invitation.key-ids", "fixture");
        f.http.properties.put(
                "pocoma.v3.invitation.keys.fixture", Base64.getEncoder().encodeToString(key));
        f.worker.select(V3ConsumptionWorkerSettings.READ_NAMES.subList(0, 3), true);
        f.worker.properties.put(
                "pocoma.v3.worker.consumption-limits",
                V3ConsumptionWorkerSettings.READ_NAMES.subList(0, 3).stream()
                                .map(n -> n + "=1")
                                .collect(java.util.stream.Collectors.joining(","))
                        + ",Command=4,IdentityRequest=1");
        f.worker.properties.put("pocoma.v3.worker.min-poll", "5");
        f.worker.properties.put("pocoma.v3.worker.max-poll", "25");
        f.worker.properties.put("pocoma.v3.worker.lifecycle.retry-delay-ms", "0");
    }

    @AfterEach
    void closeInvitation() throws Exception {
        if (f != null) f.close();
    }

    void startPot() throws Exception {
        startRoots();
        f.register("owner");
        f.execute("CreatePot", Map.of("label", "Invitation"));
        f.execute(
                "AddShareholders",
                Map.of(
                        "targets",
                        List.of(
                                Map.of(
                                        "id",
                                        f.share.toString(),
                                        "name",
                                        "Free",
                                        "attachment",
                                        "FREE",
                                        "weight",
                                        f.fraction(1, 1)),
                                Map.of(
                                        "id",
                                        new UUID(4, 2).toString(),
                                        "name",
                                        "Other free",
                                        "attachment",
                                        "FREE",
                                        "weight",
                                        f.fraction(1, 1)))));
    }

    void startRoots() {
        f.start();
        f.worker.select(V3ConsumptionWorkerSettings.READ_NAMES.subList(3, 7), false);
        f.worker.root = PocomaTaskConsumptionWorkerApplication.class;
        f.worker.start(Map.of("pocoma.v3.worker.worker-name", "i4-projection"));
    }

    Object invitationBody(Instant expiry) {
        var body =
                new LinkedHashMap<>(
                        f.body("CreateInvitation", Map.of("expiresAt", expiry.toString())));
        body.put("observedVersion", 0);
        return body;
    }

    UUID create(Instant expiry) throws Exception {
        UUID id =
                f.accepted(
                        f.post(
                                "/commands",
                                "owner",
                                UUID.randomUUID().toString(),
                                invitationBody(expiry)));
        var result = f.poll("/commands/" + id + "/result", "owner", null);
        assertEquals("COMPLETED", result.get("outcome").asText(), result.toString());
        assertTrue(
                result.get("resultingPotVersion") == null
                        || result.get("resultingPotVersion").isNull());
        return id;
    }

    JsonNode link(UUID id) throws Exception {
        return f.poll("/invitations/by-creation-request/" + id, "owner", null);
    }

    String invitationToken(UUID id) throws Exception {
        return link(id).get("link").asText().substring("pocoma://invitation/".length());
    }

    String binding(String actor) throws Exception {
        f.register(actor);
        return f.get("/me/binding", actor, null).get("bindingToken").asText();
    }

    void awaitTwoFinalWriteWaiters() throws Exception {
        // An independent backend observes actual final WRITE waits, after both preparations.
        ReadMonolithI8Fixture.await(
                () ->
                        f.worker.write.queryForObject(
                                "select count(*)>=2 from pg_stat_activity where usename=? and"
                                    + " wait_event_type='Lock' and (query like '%v3_pot_identity%'"
                                    + " or query like '%v3_eui_authority%' or query like"
                                    + " '%pg_advisory_xact_lock%')",
                                Boolean.class, f.worker.writeRole));
    }

    Object attachBody(String binding, String secret, UUID shareholder) {
        return Map.of(
                "operation",
                "AttachShareholderViaInvitation",
                "potId",
                f.pot.toString(),
                "observedVersion",
                0,
                "bindingToken",
                binding,
                "payload",
                Map.of("invitationToken", secret, "shareholderId", shareholder.toString()));
    }

    JsonNode attach(String actor, String binding, String secret, UUID shareholder, String key)
            throws Exception {
        UUID id =
                f.accepted(
                        f.post("/commands", actor, key, attachBody(binding, secret, shareholder)));
        return f.poll("/commands/" + id + "/result", actor, null);
    }

    java.net.http.HttpResponse<byte[]> places(String actor, String binding, String secret)
            throws Exception {
        return f.http.request(
                "POST",
                "/invitations/available-shareholders/query",
                f.http.jwt(actor),
                binding,
                f.json.writeValueAsBytes(Map.of("token", secret)),
                Map.of("Content-Type", "application/json"));
    }
}
