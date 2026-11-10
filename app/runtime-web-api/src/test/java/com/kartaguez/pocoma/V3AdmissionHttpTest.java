package com.kartaguez.pocoma;

import static com.kartaguez.pocoma.V3ReadHttpFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.kartaguez.pocoma.supra.http.write.v3.*;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
class V3AdmissionHttpTest {
    @Container static final PostgreSQLContainer PG = container();
    V3ReadHttpFixture f;

    @BeforeEach
    void setup() throws Exception {
        f = new V3ReadHttpFixture(PG);
        f.enableAdmission();
    }

    @AfterEach
    void close() {
        if (f != null) f.close();
    }

    void start() {
        f.start(PocomaWebApiApplication.class);
    }

    String command(String label) {
        return "{\"operation\":\"CreatePot\",\"potId\":\""
                + POT
                + "\",\"observedVersion\":0,\"bindingToken\":\"opaque-canary\",\"payload\":{\"label\":\""
                + label
                + "\"}}";
    }

    HttpResponse<byte[]> post(String path, String subject, String key, String body)
            throws Exception {
        return f.request(
                "POST",
                path,
                f.jwt(subject),
                null,
                body.getBytes(StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/json", "Idempotency-Key", key));
    }

    long count(String table) {
        return f.admissionObserver.queryForObject("select count(*) from " + table, Long.class);
    }

    void empty() {
        for (String table :
                List.of(
                        "v3_command",
                        "v3_identity_request",
                        "v3_admission_association",
                        "consumption_slot",
                        "v3_pot_event",
                        "v3_identity_event")) assertEquals(0, count(table), table);
    }

    @Test
    void commandRegistrationUnlinkAccepted() throws Exception {
        start();
        assertEquals(202, post("/commands", "owner", "c", command("A")).statusCode());
        var r = post("/identity/requests", "new", "r", "{\"operation\":\"Registration\"}");
        assertEquals(202, r.statusCode());
        assertTrue(r.headers().firstValue("Location").orElseThrow().endsWith("/result"));
        assertEquals(
                202,
                post(
                                "/identity/requests",
                                "owner",
                                "u",
                                "{\"operation\":\"Unlink\",\"bindingToken\":\"unknown"
                                        + " opaque\",\"target\":{\"issuer\":\""
                                        + f.issuer
                                        + "\",\"subject\":\"other\"}}")
                        .statusCode());
        assertEquals(3, count("v3_admission_association"));
        assertEquals(0, count("consumption_slot"));
        var registry = f.context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
        var cmdCounter = registry.find("pocoma.v3.admission.requests").tag("endpoint", "command").tag("status", "202").counter();
        assertNotNull(cmdCounter, "command admission counter must exist");
        assertEquals(1.0, cmdCounter.count());
        var idCounter = registry.find("pocoma.v3.admission.requests").tag("endpoint", "identity").tag("status", "202").counter();
        assertNotNull(idCounter, "identity admission counter must exist");
        assertEquals(2.0, idCounter.count());
        var cmdTimer = registry.find("pocoma.v3.admission.http.duration").tag("endpoint", "command").tag("status", "202").timer();
        assertNotNull(cmdTimer, "command admission timer must exist");
        assertEquals(1, cmdTimer.count());
        var capGauge = registry.find("pocoma.v3.admission.capacity.available").tag("endpoint", "admission").gauge();
        assertNotNull(capGauge, "admission capacity gauge must exist");
        assertTrue(capGauge.value() >= 0);
        var poolTimer = registry.find("hikaricp.connections.acquire").tag("pool", "v3-http-admission").timer();
        assertNotNull(poolTimer, "hikari admission pool acquire timer must exist");
    }

    @Test
    void sameIdAnd409() throws Exception {
        start();
        var a = post("/commands", "owner", "key", command("A"));
        var b = post("/commands", "owner", "key", command("A"));
        assertEquals(202, a.statusCode());
        assertEquals(f.body(a), f.body(b));
        assertEquals(409, post("/commands", "owner", "key", command("B")).statusCode());
        assertEquals(1, count("v3_command"));
        var reordered = new com.fasterxml.jackson.databind.ObjectMapper().readTree(command("A"));
        assertEquals(
                f.body(a), f.body(post("/commands", "owner", "key", reordered.toPrettyString())));
        // Lost client response: physically send the entire request, close before receiving, then
        // retry.
        String raw = command("lost");
        var endpoint = URI.create(f.baseUrl);
        try (var socket = new java.net.Socket(endpoint.getHost(), endpoint.getPort())) {
            var out = socket.getOutputStream();
            String head =
                    "POST /api/v3/commands HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer "
                            + f.jwt("owner")
                            + "\r\n"
                            + "Idempotency-Key: lost-response\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: "
                            + raw.getBytes(StandardCharsets.UTF_8).length
                            + "\r\nConnection: close\r\n\r\n";
            out.write(head.getBytes(StandardCharsets.US_ASCII));
            out.write(raw.getBytes(StandardCharsets.UTF_8));
            out.flush();
            socket.shutdownOutput();
        }
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (count("v3_command") != 2 && System.nanoTime() < end) Thread.sleep(10);
        assertEquals(2, count("v3_command"));
        assertEquals(202, post("/commands", "owner", "lost-response", raw).statusCode());
        assertEquals(2, count("v3_command"));
    }

    @Test
    void authenticationAndCapturedRequester() throws Exception {
        start();
        var none =
                f.request(
                        "POST",
                        "/commands",
                        null,
                        null,
                        command("A").getBytes(StandardCharsets.UTF_8),
                        Map.of("Content-Type", "application/json", "Idempotency-Key", "k"));
        assertEquals(401, none.statusCode());
        empty();
        var a = post("/commands", "owner", "key", command("A"));
        var b = post("/commands", "other", "key", command("A"));
        assertEquals(202, a.statusCode());
        assertEquals(202, b.statusCode());
        assertNotEquals(f.body(a), f.body(b));
        assertEquals(
                Set.of("owner", "other"),
                new HashSet<>(
                        f.admissionObserver.queryForList(
                                "select requester_subject from v3_command", String.class)));
        assertEquals(
                400,
                post(
                                "/commands",
                                "owner",
                                "forged",
                                command("A")
                                        .replace(
                                                "\"payload\":", "\"requesterEui\":{},\"payload\":"))
                        .statusCode());
        assertEquals(2, count("v3_command"));
    }

    @Test
    void bodyKeyDepthTextBoundaries() throws Exception {
        start();
        assertEquals(
                202,
                post("/commands", "owner", "k".repeat(128), command("é".repeat(2048)))
                        .statusCode());
        assertEquals(400, post("/commands", "owner", "k".repeat(129), command("A")).statusCode());
        assertEquals(
                400, post("/commands", "owner", "text", command("é".repeat(2049))).statusCode());
        String body = command("A");
        String exact = body + " ".repeat(262144 - body.getBytes(StandardCharsets.UTF_8).length);
        assertEquals(202, post("/commands", "owner", "exact", exact).statusCode());
        assertEquals(413, post("/commands", "owner", "over", exact + " ").statusCode());
        String reg = "{\"operation\":\"Registration\"}";
        assertEquals(
                202,
                post(
                                "/identity/requests",
                                "new",
                                "identity-exact",
                                reg + " ".repeat(16384 - reg.length()))
                        .statusCode());
        assertEquals(
                413,
                post(
                                "/identity/requests",
                                "new",
                                "identity-over",
                                reg + " ".repeat(16385 - reg.length()))
                        .statusCode());
        var transport = f.context.getBean(V3AdmissionTransport.class);
        assertNotNull(
                transport.parse(
                        ("[".repeat(16) + "0" + "]".repeat(16)).getBytes(StandardCharsets.UTF_8)));
        assertThrows(
                com.kartaguez.pocoma.contract.writeadmission.AdmissionFailure.class,
                () ->
                        transport.parse(
                                ("[".repeat(17) + "0" + "]".repeat(17))
                                        .getBytes(StandardCharsets.UTF_8)));
        var chunk =
                HttpRequest.newBuilder(URI.create(f.baseUrl + "/api/v3/commands"))
                        .header("Authorization", "Bearer " + f.jwt("owner"))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", "chunk")
                        .POST(
                                HttpRequest.BodyPublishers.ofInputStream(
                                        () ->
                                                new java.io.ByteArrayInputStream(
                                                        (exact + " ")
                                                                .getBytes(StandardCharsets.UTF_8))))
                        .build();
        assertEquals(
                413, f.client.send(chunk, HttpResponse.BodyHandlers.ofByteArray()).statusCode());
        assertEquals(2, count("v3_command"));
    }

    @Test
    void mediaDuplicateUnknownAndForbiddenFields() throws Exception {
        start();
        for (var headers :
                List.of(
                        Map.of("Content-Type", "text/plain", "Idempotency-Key", "k"),
                        Map.of(
                                "Content-Type",
                                "application/json",
                                "Content-Encoding",
                                "gzip",
                                "Idempotency-Key",
                                "k")))
            assertEquals(
                    415,
                    f.request(
                                    "POST",
                                    "/commands",
                                    f.jwt("owner"),
                                    null,
                                    command("A").getBytes(StandardCharsets.UTF_8),
                                    headers)
                            .statusCode());
        for (String invalid :
                List.of(
                        command("A").replace("\"payload\":", "\"unknown\":1,\"payload\":"),
                        command("A")
                                .replace(
                                        "\"payload\":", "\"operation\":\"DeletePot\",\"payload\":"),
                        command("A") + "{}",
                        command("\\" + "uD800"),
                        command("\\" + "uDC00")))
            assertEquals(400, post("/commands", "owner", "k", invalid).statusCode());
        assertEquals(
                400,
                post(
                                "/identity/requests",
                                "owner",
                                "unicode",
                                "{\"operation\":\"Unlink\",\"bindingToken\":\"opaque\","
                                        + "\"target\":{\"issuer\":\"issuer\",\"subject\":\""
                                        + "\\" + "uD800" + "\"}}")
                        .statusCode());
        empty();
    }

    @Test
    void limitsChangedReplayOldContract() throws Exception {
        start();
        String body = command("historical label");
        var first = post("/commands", "owner", "same", body);
        assertEquals(202, first.statusCode());
        f.context.close();
        f.properties.put("pocoma.v3.http.admission.text-bytes", "1");
        start();
        assertEquals(f.body(first), f.body(post("/commands", "owner", "same", body)));
        assertEquals(400, post("/commands", "owner", "new", body).statusCode());
        f.context.close();
        f.properties.put("pocoma.v3.http.admission.command-bytes", "64");
        start();
        assertEquals(413, post("/commands", "owner", "same", body).statusCode());
        assertEquals(1, count("v3_command"));
        f.context.close();
        f.properties.put("pocoma.v3.http.admission.command-bytes", "262144");
        f.properties.put("pocoma.v3.http.admission.comparison-key-ids", "k2");
        f.properties.put("pocoma.v3.http.admission.comparison-current", "k2");
        f.properties.put(
                "pocoma.v3.http.admission.comparison-keys.k2",
                Base64.getEncoder().encodeToString(new byte[32]));
        start();
        assertEquals(503, post("/commands", "owner", "same", body).statusCode());
        assertEquals(1, count("v3_command"));
    }

    @Test
    void overloadAndPhysicalTimeoutRelease() throws Exception {
        start();
        var pool = f.context.getBean("v3AdmissionPool", HikariDataSource.class);
        var transport = f.context.getBean(V3AdmissionTransport.class);
        try (var gate = f.admissionObserver.getDataSource().getConnection();
                var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            gate.setAutoCommit(false);
            gate.createStatement()
                    .execute("lock table v3_admission_association in access exclusive mode");
            var first = threads.submit(() -> post("/commands", "owner", "held", command("A")));
            long end = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (transport.availableCapacity() != 0 && System.nanoTime() < end) Thread.sleep(5);
            assertEquals(0, transport.availableCapacity());
            assertEquals(503, post("/commands", "owner", "overload", command("A")).statusCode());
            assertEquals(503, first.get(5, TimeUnit.SECONDS).statusCode());
            assertEquals(1, transport.availableCapacity());
            assertEquals(0, pool.getHikariPoolMXBean().getActiveConnections());
            gate.rollback();
        }
        empty();
        assertEquals(202, post("/commands", "owner", "held", command("A")).statusCode());
    }

    @Test
    void noSecretOrWriteFallback() throws Exception {
        start();
        var r = post("/commands", "owner", "k", command("A"));
        String id = f.body(r).get("requestId").asText();
        assertEquals(404, f.get("/commands/" + id + "/result").statusCode());
        var bad = post("/commands", "owner", "k", command("DIFFERENT-CANARY"));
        String text = new String(bad.body(), StandardCharsets.UTF_8);
        assertFalse(text.contains("DIFFERENT-CANARY"));
        assertFalse(text.contains("opaque-canary"));
        assertFalse(text.contains("jdbc:"));
        assertEquals("no-store", bad.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(2, f.context.getBeansOfType(HikariDataSource.class).size());
        assertNotEquals(
                f.context.getBean("v3AdmissionPool", HikariDataSource.class).getUsername(),
                f.context.getBean("v3HttpReadPool", HikariDataSource.class).getUsername());
    }

    @Test
    void linkAndInvitationNotExposed() throws Exception {
        start();
        for (String operation : List.of("Link", "Invitation", "CreateInvitation"))
            assertEquals(
                    400,
                    post(
                                    "/identity/requests",
                                    "owner",
                                    "k",
                                    "{\"operation\":\"" + operation + "\"}")
                            .statusCode());
        empty();
    }
}
