package com.kartaguez.pocoma;
import com.kartaguez.pocoma.contract.readquery.ReadPotV3Result;

import static org.junit.jupiter.api.Assertions.*;
import static com.kartaguez.pocoma.V3ReadHttpFixture.*;
import com.fasterxml.jackson.databind.*;
import com.kartaguez.pocoma.supra.http.read.v3.*;
import com.kartaguez.pocoma.engine.read.pot.v3.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.jwk.gen.*;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

@Testcontainers
class V3ReadHttpContractTest {
    @Container static final PostgreSQLContainer PG = container();
    V3ReadHttpFixture f;
    long setupSqlCalls;
    long totalSqlCalls() { return f.sqlStats().stream().mapToLong(row -> ((Number) row.get("calls")).longValue()).sum(); }
    long maintenanceStatusCalls() {
        return f.sqlStats().stream().filter(row -> row.get("query").toString().replaceAll("\\s+", " ").trim().equalsIgnoreCase(
                "SELECT state, phase, target_prefix::text AS target_prefix_text, operator_id, operation_id, started_at, updated_at, error_detail FROM public.read_maintenance_state WHERE id = $1"))
                .mapToLong(row -> ((Number) row.get("calls")).longValue()).sum();
    }
    long sqlCalls() { return totalSqlCalls() - maintenanceStatusCalls(); }
    @BeforeAll static void retainClasspath() throws IOException { Files.writeString(Path.of("target/i1-classpath.txt"), System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))); }
    @BeforeEach void fixture() throws Exception { f = new V3ReadHttpFixture(PG); f.start(PocomaWebApiApplication.class); setupSqlCalls = sqlCalls(); }
    @AfterEach void close() throws Exception { try { if (f != null) f.evidence("HTTP-" + f.schema, Map.of("sql", f.sqlStats(), "activeBackends", f.activeBackends())); } finally { if (f != null) f.close(); } }
    void status(int expected, HttpResponse<byte[]> response) throws Exception {
        assertEquals(expected, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
        assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
        if (expected != 200) { var body = f.body(response); assertEquals(Set.of("code", "message", "traceId"), names(body)); assertTrue(response.body().length <= 1024); assertTrue(body.get("traceId").asText().matches("[a-f0-9-]{36}")); }
    }
    static Set<String> names(JsonNode value) { Set<String> names = new HashSet<>(); value.fieldNames().forEachRemaining(names::add); return names; }
    HttpResponse<byte[]> bearer(String subject, String token, String path) throws Exception { return f.request("GET", path, f.jwt(subject), token, null, Map.of()); }
    void unauthorized(String jwt) throws Exception { status(401, f.request("GET", "/me/binding", jwt, null, null, Map.of())); assertEquals(0, f.sqlStats().stream().filter(s -> s.get("query").toString().startsWith("select") && s.get("query").toString().contains("read_")).count()); }
    @Test void missingBearer() throws Exception {
        for (String path : List.of("/me/binding", "/pots/" + POT, f.path(POT, 1), f.path(POT, 1) + "/balance", f.path(POT, 1) + "/expenses/query", "/me/pots", "/commands/" + id(401) + "/result", "/identity/requests/" + id(403) + "/result"))
            status(401, f.request(path.endsWith("query") ? "POST" : "GET", path, null, TOKEN, null, Map.of()));
        assertEquals(setupSqlCalls, sqlCalls(), () -> "zero SQL after pool initialization: " + f.sqlStats());
    }
    @Test void forgedSignature() throws Exception {
        var now = Instant.now(); unauthorized(f.jwt("owner", f.issuer, "pocoma-i1", now.minusSeconds(1), now.plusSeconds(60), now.minusSeconds(2), new RSAKeyGenerator(2048).generate(), JWSAlgorithm.RS256));
        unauthorized(f.jwt("owner", f.issuer, "pocoma-i1", now.minusSeconds(1), now.plusSeconds(60), now.minusSeconds(2), f.key, JWSAlgorithm.RS512));
    }
    @Test void wrongIssuer() throws Exception {
        var now = Instant.now(); unauthorized(f.jwt("owner", "http://untrusted.invalid", "pocoma-i1", now.minusSeconds(1), now.plusSeconds(60), now.minusSeconds(2), f.key, JWSAlgorithm.RS256));
    }
    @Test void wrongAudience() throws Exception {
        var now = Instant.now(); for (String audience : Arrays.asList("wrong", null)) unauthorized(f.jwt("owner", f.issuer, audience, now.minusSeconds(1), now.plusSeconds(60), now.minusSeconds(2), f.key, JWSAlgorithm.RS256));
    }
    @Test void expiredJwt() throws Exception { var now = Instant.now(); unauthorized(f.jwt("owner", f.issuer, "pocoma-i1", now.minusSeconds(60), now.minusSeconds(1), now.minusSeconds(61), f.key, JWSAlgorithm.RS256)); }
    @Test void notYetValidJwt() throws Exception { var now = Instant.now(); unauthorized(f.jwt("owner", f.issuer, "pocoma-i1", now.minusSeconds(1), now.plusSeconds(600), now.plusSeconds(120), f.key, JWSAlgorithm.RS256)); }
    @Test void missingEui() throws Exception {
        var now = Instant.now(); for (String sub : Arrays.asList(null, "", " ")) unauthorized(f.jwt(sub, f.issuer, "pocoma-i1", now.minusSeconds(1), now.plusSeconds(60), now.minusSeconds(2), f.key, JWSAlgorithm.RS256));
        unauthorized(f.jwt("owner", null, "pocoma-i1", now.minusSeconds(1), now.plusSeconds(60), now.minusSeconds(2), f.key, JWSAlgorithm.RS256));
        for (String missing : List.of("auth_time", "iat", "exp")) {
            var claims = new LinkedHashMap<>(com.nimbusds.jwt.SignedJWT.parse(f.jwt("owner")).getJWTClaimsSet().toJSONObject()); claims.remove(missing);
            var token = new com.nimbusds.jwt.SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("i1-local").build(), com.nimbusds.jwt.JWTClaimsSet.parse(claims)); token.sign(new com.nimbusds.jose.crypto.RSASSASigner(f.key)); unauthorized(token.serialize());
        }
        for (Object invalid : List.of("invalid", now.plusSeconds(300).getEpochSecond())) {
            var claims = new LinkedHashMap<>(com.nimbusds.jwt.SignedJWT.parse(f.jwt("owner")).getJWTClaimsSet().toJSONObject()); claims.put("auth_time", invalid);
            var token = new com.nimbusds.jwt.SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("i1-local").build(), com.nimbusds.jwt.JWTClaimsSet.parse(claims)); token.sign(new com.nimbusds.jose.crypto.RSASSASigner(f.key)); unauthorized(token.serialize());
        }
    }
    @Test void ownBindingAndResults() throws Exception {
        var active = bearer("owner", null, "/me/binding"); status(200, active); assertEquals(Set.of("euiVersion", "active", "bindingToken"), names(f.body(active))); assertEquals(TOKEN, f.body(active).get("bindingToken").asText());
        var inactive = bearer("inactive", null, "/me/binding"); status(200, inactive); assertFalse(f.body(inactive).get("active").booleanValue()); assertTrue(f.body(inactive).get("bindingToken").isNull());
        status(404, bearer("absent", null, "/me/binding?minVersion=999")); status(503, f.get("/me/binding?minVersion=2"));
        status(400, f.get("/me/binding?eui=other")); status(400, f.get("/me/binding?userId=" + OWNER));
        f.results(); status(200, f.get("/commands/" + id(401) + "/result"));
        var registry = f.context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
        var readTimer = registry.find("pocoma.v3.read.http.duration").tag("endpoint", "binding").tag("status", "200").timer();
        assertNotNull(readTimer, "read http duration timer for binding must exist");
        assertTrue(readTimer.count() >= 1);
    }
    @Test void bindingTokenAndForeignEui() throws Exception {
        f.seed(POT, 1, 0, Set.of(OWNER), false, true, true, "private");
        for (String token : List.of("wrong", OTHER_TOKEN, "old")) status(404, bearer("owner", token, "/pots/" + POT));
        status(404, bearer("other", OTHER_TOKEN, "/pots/" + POT)); status(404, bearer("inactive", TOKEN, "/pots/" + POT)); status(404, bearer("absent", TOKEN, "/pots/" + POT));
        status(200, bearer("same", "I1-SAME-USER-CANARY", "/pots/" + POT)); status(404, bearer("same", TOKEN, "/pots/" + POT));
        status(400, bearer("owner", null, "/pots/" + POT));
    }
    @Test void terminalResultsOwnership() throws Exception {
        f.results();
        for (String route : List.of("/commands/", "/identity/requests/")) {
            long first = route.contains("identity") ? 403 : 401;
            for (long id : List.of(first, first + 1)) {
                var own = bearer("owner", null, route + id(id) + "/result"); status(200, own); assertEquals(id == first ? "COMPLETED" : "REJECTED", f.body(own).get("outcome").asText());
                var absent = f.body(bearer("owner", null, route + id(999) + "/result"));
                for (String sub : List.of("same", "other")) { var foreign = bearer(sub, null, route + id(id) + "/result"); status(404, foreign); var foreignBody = f.body(foreign); assertEquals(absent.get("code"), foreignBody.get("code")); assertEquals(absent.get("message"), foreignBody.get("message")); }
            }
        }
    }
    @Test void terminalResultsWithoutBinding() throws Exception {
        f.results(); for (long version : List.of(2L, 3L)) { f.binding("owner", version, version == 2 ? null : OTHER, version == 2 ? null : "new-token");
            status(200, bearer("owner", null, "/commands/" + id(401) + "/result")); status(200, bearer("owner", null, "/identity/requests/" + id(403) + "/result")); }
    }
    @Test void historicalAccessAndDeletion() throws Exception {
        f.seed(POT, 1, 2, Set.of(OWNER), false, true, true, "history"); f.seed(POT, 2, 2, Set.of(OTHER), false, true, true, "lost right");
        status(200, f.get(f.path(POT, 1))); status(200, f.get(f.path(POT, 1) + "/balance")); status(200, f.post(f.path(POT, 1) + "/expenses/query", f.batch(1, 1)));
        status(404, f.get("/pots/" + POT));
        f.seed(POT, 3, 2, Set.of(OWNER), true, false, true, "terminal");
        status(404, f.get("/pots/" + POT + "?minVersion=999")); status(404, f.get(f.path(POT, 3))); status(404, f.get(f.path(POT, 3) + "/balance")); status(200, f.get(f.path(POT, 1)));
        f.admin.update("insert into read_user_pot values (?,?,2,true)", OWNER, FORBIDDEN);
        f.seed(FORBIDDEN, 1, 0, Set.of(OTHER), false, true, true, "foreign"); status(404, f.get("/pots/" + FORBIDDEN));
    }
    @Test void minimumAndReadDependencies() throws Exception {
        f.seed(POT, 1, 1, Set.of(OWNER), false, true, true, "available"); status(503, f.get("/pots/" + POT + "?minVersion=2")); status(200, f.get("/pots/" + POT + "?minVersion=0"));
        f.seed(POT, 2, 1, Set.of(OWNER), false, false, true, "missing balance"); status(503, f.get(f.path(POT, 2) + "/balance")); status(503, f.get("/pots/" + POT));
        f.seed(POT, 3, 1, Set.of(OWNER), false, true, true, "future balance"); status(503, f.get(f.path(POT, 2) + "/balance"));
        status(404, bearer("other", OTHER_TOKEN, "/pots/" + POT + "?minVersion=9223372036854775807")); status(404, f.get("/pots/" + id(9999)));
    }
    @Test void coherentSnapshotDuringAdvance() throws Exception {
        f.seed(POT, 1, 2, Set.of(OWNER), false, true, true, "snapshot-1");
        f.seed(POT, 2, 3, Set.of(OWNER), false, true, true, "snapshot-2");
        f.admin.execute("alter table read_current_pot rename to i1_current_data");
        f.admin.execute("create function i1_pause() returns boolean language plpgsql volatile as $$ begin perform pg_advisory_xact_lock(910014); return true; end $$");
        f.admin.execute("create view read_current_pot as select * from i1_current_data where i1_pause()"); f.admin.execute("grant select on read_current_pot to " + f.role);
        for (boolean commit : List.of(false, true)) {
            f.admin.update("update i1_current_data set pot_version=1 where pot_id=?", POT);
            try (var gate = f.admin.getDataSource().getConnection(); var writer = f.admin.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
                gate.setAutoCommit(false); gate.createStatement().execute("select pg_advisory_xact_lock(910014)");
                var response = executor.submit(() -> f.get("/pots/" + POT)); awaitSql(f, "advisory");
                writer.setAutoCommit(false);
                try (var statement = writer.prepareStatement("update i1_current_data set pot_version=2 where pot_id=?")) { statement.setObject(1, POT); statement.executeUpdate(); }
                try (var statement = writer.prepareStatement("update read_current_binding set user_id=?,binding_token='snapshot-new-token' where subject='owner'")) { statement.setObject(1, OTHER); statement.executeUpdate(); }
                if (commit) writer.commit();
                gate.commit(); var actual = response.get(5, TimeUnit.SECONDS); status(200, actual);
                assertEquals(1, f.body(actual).get("potVersion").longValue()); assertEquals("snapshot-1", f.body(actual).get("label").asText()); assertEquals(2, f.body(actual).get("expenses").size());
                if (!commit) writer.rollback();
            }
            if (commit) status(404, f.get("/pots/" + POT));
        }
    }

    @Test void completeManifestAndExactBatches() throws Exception {
        for (int count : List.of(0, 200, 2000)) {
            UUID pot = id(500 + count); f.seed(pot, 1, count, count == 2000 ? 100 : 20, Set.of(OWNER), false, true, true, "é\\\"-" + count);
            var response = f.get(f.path(pot, 1)); status(200, response); var body = f.body(response); assertEquals(count, body.get("expenses").size()); assertEquals("é\\\"-" + count, body.get("label").asText()); assertEquals(count == 2000 ? 100 : 20, body.get("shareholders").size());
            for (int n = 0; n < count; n++) assertEquals(id(1000 + n).toString(), body.get("expenses").get(n).get("id").asText());
            if (count > 0) for (int batch : List.of(1, 49, 50)) { var values = f.post(f.path(pot, 1) + "/expenses/query", f.batch(batch, 1)); status(200, values); assertEquals(batch, f.body(values).get("expenses").size()); assertEquals("1", f.body(values).at("/expenses/0/amount/n").asText()); assertEquals("3", f.body(values).at("/expenses/0/amount/d").asText()); }
        }
        f.seedProfile(id(899), 1, 1, 2, Set.of(OWNER), false, true, true, "rationnels", 1024, true);
        var exact = f.get(f.path(id(899), 1)); status(200, exact);
        String numerator = "1" + "0".repeat(1022) + "1", denominator = "1" + "0".repeat(1022) + "3";
        assertEquals(numerator, f.body(exact).at("/shareholders/0/defaultWeight/n").asText()); assertEquals(denominator, f.body(exact).at("/shareholders/0/defaultWeight/d").asText());
        var expense = f.post(f.path(id(899), 1) + "/expenses/query", f.batch(1, 1)); status(200, expense);
        assertEquals(numerator, f.body(expense).at("/expenses/0/amount/n").asText()); assertEquals(denominator, f.body(expense).at("/expenses/0/amount/d").asText());
    }
    @Test void batchCardinalityAndMalformedRefs() throws Exception {
        String path = f.path(POT, 1) + "/expenses/query";
        for (String body : List.of(f.batch(0, 1), f.batch(51, 1), "null", "{\"references\":null}", "{\"references\":[null]}", "{\"references\":[" + ref(1000, 1) + "," + ref(1000, 1) + "]}", f.batch(1, 0), f.batch(1, 1).replace("\"headerVersion\":1", "\"headerVersion\":9223372036854775808"), f.batch(1, 1).replace("\"headerVersion\":1", "\"headerVersion\":1e0"))) status(400, f.post(path, body));
        assertEquals(setupSqlCalls, sqlCalls(), () -> "zero SQL after pool initialization: " + f.sqlStats());
    }
    @Test void foreignRefsAndMissingSubversions() throws Exception {
        f.seed(POT, 1, 2, Set.of(OWNER), false, true, false, "missing details"); String path = f.path(POT, 1) + "/expenses/query";
        status(404, f.post(path, "{\"references\":[" + ref(9000, 1) + "]}")); status(404, f.post(path, f.batch(1, 2))); status(503, f.post(path, f.batch(1, 1)));
    }
    @Test void navigationCursorAndBindingChange() throws Exception {
        f.navigation(205); String cursor = ""; Set<String> union = new HashSet<>();
        for (int size : List.of(100, 100, 5)) { var page = f.get("/me/pots?limit=100" + cursor); status(200, page); var body = f.body(page); assertEquals(size, body.get("potIds").size()); for (var id : body.get("potIds")) assertTrue(union.add(id.asText())); cursor = "&cursor=" + body.get("nextCursor").asText(); }
        assertEquals(205, union.size()); assertEquals(20, f.body(f.get("/me/pots")).get("potIds").size());
        for (String query : List.of("limit=0", "limit=101", "cursor=not-a-cursor", "cursor=" + Base64.getUrlEncoder().encodeToString("2:bad".getBytes(StandardCharsets.UTF_8)))) status(400, f.get("/me/pots?" + query));
        f.binding("owner", 2, OTHER, "new-token"); status(404, f.get("/me/pots")); var changed = bearer("owner", "new-token", "/me/pots?limit=100"); status(200, changed); assertEquals(0, f.body(changed).get("potIds").size());
    }
    @Test void navigationMutationsAndPlans() throws Exception {
        f.navigation(2000); for (int n = 1; n <= 2000; n++) f.admin.update("insert into read_user_pot values (?,?,1,true)", OTHER, id(20000 + n));
        var page = f.body(f.get("/me/pots?limit=100")); String cursor = page.get("nextCursor").asText();
        f.admin.update("insert into read_user_pot values (?,?,1,true)", OWNER, id(9999)); f.admin.update("update read_user_pot set visible=false where user_id=? and pot_id=?", OWNER, id(10101));
        var foreignCursor = bearer("other", OTHER_TOKEN, "/me/pots?limit=100&cursor=" + cursor); status(200, foreignCursor);
        assertEquals(id(20001).toString(), f.body(foreignCursor).at("/potIds/0").asText());
        for (var otherId : f.body(foreignCursor).get("potIds")) assertTrue(otherId.asText().compareTo(id(20001).toString()) >= 0);
        var next = f.body(f.get("/me/pots?limit=100&cursor=" + cursor)); assertEquals(id(10102).toString(), next.at("/potIds/0").asText());
        try (var connection = f.pool().getConnection(); var statement = connection.prepareStatement("explain (analyze,buffers,format json) select pot_id from read_user_pot where user_id=? and visible and pot_id>? order by pot_id limit 100")) {
            statement.setObject(1, OWNER); statement.setObject(2, id(10100)); try (var rows = statement.executeQuery()) { assertTrue(rows.next()); String plan = rows.getString(1); assertTrue(plan.contains("Index"), plan); f.evidence("H19-plan", Map.of("plan", plan)); }
        }
        assertTrue(f.sqlStats().stream().noneMatch(row -> row.get("query").toString().toLowerCase(Locale.ROOT).contains("count(")));
    }
    @Test void inputByteBoundaries() throws Exception {
        f.seed(POT, 1, 1, Set.of(OWNER), false, true, true, "input"); String body = f.batch(1, 1), path = f.path(POT, 1) + "/expenses/query";
        for (int size : List.of(65535, 65536, 65537)) { var response = f.post(path, body + " ".repeat(size - body.getBytes(StandardCharsets.UTF_8).length)); status(size > 65536 ? 413 : 200, response); }
        // Unknown-length publishers exercise chunked framing at all three byte boundaries.
        for (int size : List.of(65535, 65536, 65537)) {
            byte[] chunked = (body + " ".repeat(size-body.length())).getBytes(StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(URI.create(f.baseUrl + "/api/v3" + path)).header("Authorization", "Bearer " + f.jwt("owner")).header("X-Pocoma-Binding-Token", TOKEN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(chunked))).build(); status(size > 65536 ? 413 : 200, f.client.send(request, HttpResponse.BodyHandlers.ofByteArray()));
        }
        long beforeMalformed = sqlCalls();
        String headers = "Host: 127.0.0.1\r\nAuthorization: Bearer " + f.jwt("owner") + "\r\nX-Pocoma-Binding-Token: " + TOKEN + "\r\nConnection: close\r\n";
        assertTrue(raw("POST /api/v3" + path + " HTTP/1.1\r\n" + headers + "Content-Type: application/json\r\nContent-Length: 1\r\n\r\n" + body).startsWith("HTTP/1.1 400"));
        assertTrue(raw("GET /api/v3/me/binding HTTP/1.1\r\n" + headers + "X-Large: " + "x".repeat(16385) + "\r\n\r\n").startsWith("HTTP/1.1 400"));
        assertEquals(beforeMalformed, sqlCalls(), "framing/header refusal before READ SQL");

        status(400, f.get("/me/binding?padding=" + "x".repeat(2050)));
    }
    String raw(String request) throws Exception {
        var uri = URI.create(f.baseUrl);
        try (var socket = new java.net.Socket(uri.getHost(), uri.getPort())) {
            socket.setSoTimeout(6000); socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); socket.getOutputStream().flush();
            var firstLine = new ByteArrayOutputStream(); int value;
            while ((value=socket.getInputStream().read()) != -1 && value != '\n') firstLine.write(value);
            return firstLine.toString(StandardCharsets.US_ASCII);
        }
    }
    static volatile Object boundaryDto;
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false) static class BoundaryProbe {
        @Bean ServletRegistrationBean<HttpServlet> boundaryProbe(V3ReadTransport transport) {
            return new ServletRegistrationBean<>(new HttpServlet() { @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException { transport.write(response, boundaryDto); } }, "/api/v3/transport-boundary");
        }
    }
    void boundaryServer() { f.context.close(); f.context = null; f.start(PocomaWebApiApplication.class, BoundaryProbe.class); }
    static V3ReadDtos.Pot dto(String label, List<ReadPotV3Result.ExpenseRef> refs) { return new V3ReadDtos.Pot(1, POT, label, 1, 1, List.of(), Map.of(), refs); }
    @Test void outputByteBoundaries() throws Exception {
        boundaryServer();
        String prefix = "{\"potVersion\":1,\"id\":\"" + POT + "\",\"label\":\"", suffix = "\",\"headerVersion\":1,\"detailsVersion\":1,\"shareholders\":[],\"balances\":{},\"expenses\":[]}";
        int overhead = (prefix + suffix).getBytes(StandardCharsets.UTF_8).length;
        for (int size : List.of(4194303, 4194304, 4194305)) {
            boundaryDto = dto("x".repeat(size - overhead), List.of()); var response = f.get("/transport-boundary"); status(size > 4194304 ? 503 : 200, response);
            if (size <= 4194304) { assertEquals(size, response.body().length); assertEquals("[]", f.body(response).get("expenses").toString()); assertEquals(size, Integer.parseInt(response.headers().firstValue("content-length").orElseThrow())); }
        }
        // JDBC path keeps its existing conservative internal budget, rather than raising it for this oracle.
        f.seed(POT, 1, 0, Set.of(OWNER), false, true, true, "x".repeat(4_194_304)); status(503, f.get("/pots/" + POT));
        f.evidence("H21-boundaries", Map.of("acceptedBytes", List.of(4194303, 4194304), "refusedBytes", 4194305, "jdbcLargeLabel", "503 INTERNAL_BUDGET"));
    }
    @Test void lateSerializationFailure() throws Exception {
        boundaryServer(); boundaryDto = dto("UTF8-é-\\\"", new AbstractList<>() { public int size() { return 2; } public ReadPotV3Result.ExpenseRef get(int index) { if (index == 1) throw new IllegalStateException("SECRET-CANARY"); return new ReadPotV3Result.ExpenseRef(id(1000), 1, 1, 1, false); } });
        var response = f.get("/transport-boundary"); status(503, response); assertFalse(new String(response.body(), StandardCharsets.UTF_8).contains("CANARY")); assertEquals(2, f.context.getBean(V3ReadTransport.class).availableCapacity());
        boundaryDto = dto("recovered", List.of()); status(200, f.get("/transport-boundary"));
    }
    @Test void exactDtoAndSecretLogs() throws Exception {
        f.seed(POT, 1, 2, Set.of(OWNER), false, true, true, "é-\\\""); f.results();
        var response = f.get("/pots/" + POT); status(200, response); assertEquals(Set.of("potVersion", "id", "label", "headerVersion", "detailsVersion", "shareholders", "balances", "expenses"), names(f.body(response)));
        assertEquals(Set.of("n", "d"), names(f.body(response).at("/balances/" + S1))); assertEquals("1", f.body(response).at("/balances/" + S1 + "/n").asText());
        for (var route : List.of("/pots/" + POT, f.path(POT, 1) + "/balance", "/commands/" + id(401) + "/result", "/identity/requests/" + id(403) + "/result")) {
            String bytes = new String(f.get(route).body(), StandardCharsets.UTF_8); for (String forbidden : List.of("userId", "authorizedUserIds", "attachedActiveUserIds", "requesterEui", TOKEN, OTHER_TOKEN, OWNER.toString())) assertFalse(bytes.contains(forbidden), forbidden);
        }
        String logs = PG.getLogs(); assertFalse(logs.contains(TOKEN)); assertFalse(logs.contains(OTHER_TOKEN)); assertFalse(logs.contains("Bearer "));
        f.evidence("H23-logs", Map.of("postgresLogBytes", logs.getBytes(StandardCharsets.UTF_8).length, "canariesAbsent", true, "channels", List.of("PostgreSQL container", "HTTP raw bytes"), "applicationLogging", "WARN; no request logging configured"));
    }
    @Test void mediaAndUnknownFields() throws Exception {
        String path = f.path(POT, 1) + "/expenses/query";
        status(415, f.request("POST", path, f.jwt("owner"), TOKEN, f.batch(1, 1).getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "text/plain")));
        status(415, f.request("POST", path, f.jwt("owner"), TOKEN, new byte[0], Map.of("Content-Type", "application/json", "Content-Encoding", "gzip")));
        for (String bad : List.of("{\"references\":[],\"eui\":\"other\"}", "{\"references\":[],\"references\":[]}", "{\"references\":[[[[[[[[[]]]]]]]]]}")) status(400, f.post(path, bad));
        for (String query : List.of("/me/binding?minVersion=1&minVersion=2", "/me/pots?eui=other", "/pots/0-0-0-0-1", "/me/binding?minVersion=1.0", "/me/binding?minVersion=9223372036854775808")) status(400, f.get(query));
        status(400, f.request("GET", "/me/binding", f.jwt("owner"), null, new byte[] {32}, Map.of()));
    }
    static void awaitSql(V3ReadHttpFixture fixture, String event) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (fixture.admin.queryForObject("select count(*) from pg_stat_activity where usename=? and wait_event=?", Integer.class, fixture.role, event) == 0 && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(fixture.admin.queryForObject("select count(*) from pg_stat_activity where usename=? and wait_event=?", Integer.class, fixture.role, event) > 0, "physical SQL barrier");
    }
}
