package com.kartaguez.pocoma;

import static com.kartaguez.pocoma.V3CompositionHttpFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.domain.useridentity.*;
import com.kartaguez.pocoma.domain.useridentity.v3.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.boot.*;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** New public admissions, delivered Worker participants and existing asynchronous READ results. */
@Testcontainers
class V3AdmissionFlowPostgresTest {
    @Container static final PostgreSQLContainer PG = container();

    @BeforeAll
    static void initialize() {
        initializeWorkerDatabase(PG);
    }

    ReadMonolithI8Fixture worker;
    V3CompositionHttpFixture http;
    final ObjectMapper json = new ObjectMapper();
    String token;
    String evidenceCase;
    long version;
    UUID pot = UUID.randomUUID(), share = UUID.randomUUID(), expense = UUID.randomUUID();

    @BeforeEach
    void setup(TestInfo test) throws Exception {
        evidenceCase = test.getTestMethod().orElseThrow().getName();
        worker = new ReadMonolithI8Fixture(PG);
        http = new V3CompositionHttpFixture(PG);
        Flyway.configure()
                .dataSource(worker.writeUrl, PG.getUsername(), PG.getPassword())
                .schemas(worker.schema)
                .defaultSchema(worker.schema)
                .locations(
                        "classpath:db/v3-pot/migration",
                        "classpath:db/v3-pot-execution/migration",
                        "classpath:db/v3-consumption-write/migration",
                        "classpath:db/v3-identity/migration",
                        "classpath:db/v3-admission/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                PG.getDatabaseName(),
                                "expectedSchema",
                                worker.schema,
                                "expectedAdmin",
                                PG.getUsername(),
                                "expectedRuntime",
                                worker.writeRole))
                .load()
                .migrate();
        String role = worker.schema + "_admission";
        worker.write.execute("create role " + role + " login password 'ephemeral-i2'");
        http.grantAdmission(worker.schema, role);
        http.admissionProperties(worker.writeUrl, worker.schema, role);
        http.admissionObserver = worker.write;
        worker.select(V3ConsumptionWorkerSettings.READ_NAMES, true);
    }

    void start() {
        var props = new LinkedHashMap<String, Object>(worker.properties);
        props.putAll(http.properties);
        props.put("pocoma.v3.worker.read-limits.navigation-page", "100");
        var app = new SpringApplication(PocomaRuntimeMonolithApplication.class);
        app.setAdditionalProfiles("v3-read-worker", "v3-http-read", "v3-http-admission");
        app.setDefaultProperties(props);
        app.addInitializers(
                ctx ->
                        ctx.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new org.springframework.core.env.MapPropertySource(
                                                "i2-monolith", props)));
        var context = app.run();
        worker.contexts.add(context);
        http.context = context;
        http.baseUrl =
                "http://127.0.0.1:"
                        + ((WebServerApplicationContext) context).getWebServer().getPort();
        assertEquals(5, context.getBeansOfType(HikariDataSource.class).size());
        assertEquals(
                1, context.getBean("v3AdmissionPool", HikariDataSource.class).getMaximumPoolSize());
    }

    @AfterEach
    void close() throws Exception {
        if (worker != null) worker.close();
        if (http != null) http.close();
    }

    HttpResponse<byte[]> post(String path, String subject, String key, Object body)
            throws Exception {
        return http.request(
                "POST",
                path,
                http.jwt(subject),
                null,
                json.writeValueAsBytes(body),
                Map.of("Content-Type", "application/json", "Idempotency-Key", key));
    }

    JsonNode get(String path, String subject, String binding) throws Exception {
        var r = http.request("GET", path, http.jwt(subject), binding, null, Map.of());
        assertEquals(200, r.statusCode(), path);
        return http.body(r);
    }

    JsonNode poll(String path, String subject, String binding) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        int calls = 0;
        HttpResponse<byte[]> last;
        do {
            last = http.request("GET", path, http.jwt(subject), binding, null, Map.of());
            calls++;
            if (last.statusCode() == 200) return http.body(last);
            assertEquals(404, last.statusCode(), path);
            Thread.sleep(50);
        } while (calls < 200 && System.nanoTime() < deadline);
        fail("bounded result polling deadline: " + path);
        return null;
    }

    UUID accepted(HttpResponse<byte[]> r) throws Exception {
        assertEquals(202, r.statusCode(), new String(r.body(), StandardCharsets.UTF_8));
        return UUID.fromString(http.body(r).get("requestId").asText());
    }

    UUID register(String subject) throws Exception {
        UUID id =
                accepted(
                        post(
                                "/identity/requests",
                                subject,
                                UUID.randomUUID().toString(),
                                Map.of("operation", "Registration")));
        var terminal = poll("/identity/requests/" + id + "/result", subject, null);
        assertEquals("COMPLETED", terminal.get("outcome").asText());
        long targetVersion = terminal.get("targetVersion").asLong();
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            var response =
                    http.request("GET", "/me/binding", http.jwt(subject), null, null, Map.of());
            assertTrue(response.statusCode() == 200 || response.statusCode() == 404);
            var binding = http.body(response);
            if (response.statusCode() == 200
                    && binding.get("active").asBoolean()
                    && binding.get("euiVersion").asLong() >= targetVersion) {
                if (subject.equals("owner")) token = binding.get("bindingToken").asText();
                return id;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < end);
        fail("CurrentBinding deadline");
        return null;
    }

    Map<String, String> fraction(int n, int d) {
        return Map.of("numerator", Integer.toString(n), "denominator", Integer.toString(d));
    }

    Map<String, Object> participant(UUID id) {
        var p = new LinkedHashMap<String, Object>();
        p.put("id", id.toString());
        p.put("weight", null);
        return p;
    }

    Map<String, Object> body(String operation, Object payload) {
        return Map.of(
                "operation",
                operation,
                "potId",
                pot.toString(),
                "observedVersion",
                operation.equals("CreatePot") ? 0 : version,
                "bindingToken",
                token,
                "payload",
                payload);
    }

    JsonNode execute(String operation, Object payload) throws Exception {
        String key = UUID.randomUUID().toString();
        Object body = body(operation, payload);
        UUID id = accepted(post("/commands", "owner", key, body));
        var result = poll("/commands/" + id + "/result", "owner", null);
        assertEquals("COMPLETED", result.get("outcome").asText(), result.toString());
        version = result.get("resultingPotVersion").asLong();
        assertEquals(id, accepted(post("/commands", "owner", key, body)));
        assertEquals(
                1,
                worker.write.queryForObject(
                        "select count(*) from v3_command_completion_event where subject_id=?",
                        Integer.class,
                        id));
        http.evidence(
                evidenceCase + "-I2-" + operation,
                Map.of(
                        "operation",
                        operation,
                        "requestId",
                        id.toString(),
                        "terminal",
                        result,
                        "version",
                        version));
        return result;
    }

    @Test
    void allElevenPotOperationsThroughWorker() throws Exception {
        start();
        register("owner");
        execute("CreatePot", Map.of("label", "Pot"));
        execute("UpdatePotDetails", Map.of("label", "Updated"));
        execute(
                "AddShareholders",
                Map.of(
                        "targets",
                        List.of(
                                Map.of(
                                        "id",
                                        share.toString(),
                                        "name",
                                        "Owner",
                                        "attachment",
                                        "SELF",
                                        "weight",
                                        fraction(1, 1)))));
        execute(
                "UpdateShareholderDetails",
                Map.of(
                        "targets",
                        List.of(
                                Map.of(
                                        "id",
                                        share.toString(),
                                        "name",
                                        "Renamed",
                                        "attachment",
                                        "KEEP"))));
        execute(
                "UpdateShareholderWeights",
                Map.of(
                        "targets",
                        List.of(Map.of("id", share.toString(), "weight", fraction(2, 1)))));
        execute(
                "CreateExpense",
                Map.of(
                        "id",
                        expense.toString(),
                        "label",
                        "Expense",
                        "date",
                        "2026-11-04",
                        "amount",
                        fraction(10, 3),
                        "payerId",
                        share.toString(),
                        "participants",
                        List.of(participant(share))));
        execute(
                "UpdateExpenseDetails",
                Map.of("id", expense.toString(), "label", "Edited", "date", "2026-11-05"));
        execute(
                "UpdateExpenseFinancials",
                Map.of(
                        "id",
                        expense.toString(),
                        "amount",
                        fraction(20, 3),
                        "payerId",
                        share.toString(),
                        "participants",
                        List.of(participant(share))));
        execute("DeleteExpense", Map.of("id", expense.toString()));
        execute("DeleteShareholder", Map.of("id", share.toString()));
        execute("DeletePot", Map.of());
        assertEquals(
                11,
                worker.write.queryForObject(
                        "select count(distinct operation) from v3_command", Integer.class));
    }

    void internalLink(String target) throws Exception {
        // Existing L3 controlled internal admission only; never an I3 public Link proof.
        var initiator = new ExternalIdentity(http.issuer, "owner");
        var other = new ExternalIdentity(http.issuer, target);
        UUID id = UUID.randomUUID();
        var now = Instant.now();
        var r =
                new IdentityRequest(
                        id,
                        initiator,
                        new IdentityRequest.Link(other),
                        new BindingToken(token),
                        now,
                        new IdentityRequest.AdmissionContext(
                                "i2-fixture",
                                "controlled-L3",
                                initiator,
                                other,
                                id.toString(),
                                id.toString(),
                                now,
                                Map.of(),
                                IdentityRequest.Origin.CONTROLLED_INTERNAL));
        var ds = new DriverManagerDataSource(worker.writeUrl, PG.getUsername(), PG.getPassword());
        var manager = new DataSourceTransactionManager(ds);
        manager.setNestedTransactionAllowed(false);
        var tx = new SpringAttemptTransactions(ds, manager, 5);
        var store = new JdbcIdentityRequestStore(ds, tx);
        tx.write(() -> store.insertPending(r));
        poll("/identity/requests/" + id + "/result", "owner", null);
    }

    @Test
    void registrationAndUnlinkThroughWorker() throws Exception {
        start();
        UUID first = register("owner");
        internalLink("other");
        UUID other =
                accepted(
                        post(
                                "/identity/requests",
                                "owner",
                                "unlink-other",
                                Map.of(
                                        "operation",
                                        "Unlink",
                                        "bindingToken",
                                        token,
                                        "target",
                                        Map.of("issuer", http.issuer, "subject", "other"))));
        assertEquals(
                "COMPLETED",
                poll("/identity/requests/" + other + "/result", "owner", null)
                        .get("outcome")
                        .asText());
        UUID self =
                accepted(
                        post(
                                "/identity/requests",
                                "owner",
                                "unlink-self",
                                Map.of(
                                        "operation",
                                        "Unlink",
                                        "bindingToken",
                                        token,
                                        "target",
                                        Map.of("issuer", http.issuer, "subject", "owner"))));
        assertEquals(
                "COMPLETED",
                poll("/identity/requests/" + self + "/result", "owner", null)
                        .get("outcome")
                        .asText());
        assertEquals(
                "COMPLETED",
                get("/identity/requests/" + first + "/result", "owner", null)
                        .get("outcome")
                        .asText());
        UUID again = register("owner");
        assertNotEquals(first, again);
        assertEquals(
                2,
                worker.write.queryForObject("select count(*) from v3_pocoma_user", Integer.class));
    }

    @Test
    void invalidBusinessIntentsAreAdmittedThenRejected() throws Exception {
        start();
        register("owner");
        version = 1;
        UUID id =
                accepted(
                        post(
                                "/commands",
                                "owner",
                                "missing",
                                body("UpdatePotDetails", Map.of("label", "absent"))));
        assertEquals(
                "REJECTED",
                poll("/commands/" + id + "/result", "owner", null).get("outcome").asText());
        assertEquals(
                0,
                worker.write.queryForObject("select count(*) from v3_pot_identity", Integer.class));
        var stale = new LinkedHashMap<>(body("UpdatePotDetails", Map.of("label", "binding")));
        stale.put("bindingToken", "not-current");
        UUID invalid = accepted(post("/commands", "owner", "binding", stale));
        assertEquals(
                "INVALID_BINDING",
                poll("/commands/" + invalid + "/result", "owner", null).get("rejection").asText());
    }

    @Test
    void selfUsesWriteAndSurvivesBindingChange() throws Exception {
        start();
        register("owner");
        execute("CreatePot", Map.of("label", "Self"));
        var targets = new ArrayList<Object>();
        for (int n = 0; n < 101; n++)
            targets.add(
                    Map.of(
                            "id",
                            new UUID(0, 1000 + n).toString(),
                            "name",
                            "Owner " + n,
                            "attachment",
                            "SELF",
                            "weight",
                            fraction(1, 1)));
        var result = execute("AddShareholders", Map.of("targets", targets));
        UUID request = UUID.fromString(result.get("requestId").asText());
        UUID oldUser =
                worker.write.queryForObject("select user_id from v3_pocoma_user", UUID.class);
        assertEquals(
                101,
                worker.write.queryForObject(
                        "select count(*) from v3_shareholder_details where user_id=?",
                        Integer.class,
                        oldUser));
        String durable =
                worker.write.queryForObject(
                        "select payload::text from v3_command where request_id=?",
                        String.class,
                        request);
        assertTrue(durable.contains("SELF"));
        assertFalse(durable.contains(oldUser.toString()));
        String oldToken = token;
        UUID unlink =
                accepted(
                        post(
                                "/identity/requests",
                                "owner",
                                "unlink",
                                Map.of(
                                        "operation",
                                        "Unlink",
                                        "bindingToken",
                                        token,
                                        "target",
                                        Map.of("issuer", http.issuer, "subject", "owner"))));
        poll("/identity/requests/" + unlink + "/result", "owner", null);
        register("owner");
        assertNotEquals(oldToken, token);
        assertEquals(
                "COMPLETED",
                get("/commands/" + request + "/result", "owner", null).get("outcome").asText());
        assertEquals(
                101,
                worker.write.queryForObject(
                        "select count(*) from v3_shareholder_details where user_id=?",
                        Integer.class,
                        oldUser));
        var stale =
                new LinkedHashMap<>(
                        body(
                                "UpdateShareholderDetails",
                                Map.of(
                                        "targets",
                                        List.of(
                                                Map.of(
                                                        "id",
                                                        new UUID(0, 1000).toString(),
                                                        "name",
                                                        "Stale",
                                                        "attachment",
                                                        "SELF")))));
        stale.put("bindingToken", oldToken);
        UUID denied = accepted(post("/commands", "owner", "stale-self", stale));
        assertEquals(
                "INVALID_BINDING",
                poll("/commands/" + denied + "/result", "owner", null).get("rejection").asText());
    }

    @Test
    void resultsAndReadProgressIndependently() throws Exception {
        worker.select(
                List.of(
                        "MaterializeCurrentBinding",
                        "MaterializeCommandResult",
                        "MaterializeIdentityRequestResult"),
                true);
        start();
        register("owner");
        var result = execute("CreatePot", Map.of("label", "result first"));
        assertEquals(
                404,
                http.request("GET", "/pots/" + pot, http.jwt("owner"), token, null, Map.of())
                        .statusCode());
        assertEquals(
                0,
                worker.read.queryForObject("select count(*) from read_pot_version", Integer.class));
        assertEquals(
                404,
                http.request(
                                "GET",
                                "/commands/" + result.get("requestId").asText() + "/result",
                                http.jwt("other"),
                                null,
                                null,
                                Map.of())
                        .statusCode());
    }

    @Test
    void brunoB2PublicProtocol() throws Exception {
        start();
        var repo = java.nio.file.Path.of("../..").toAbsolutePath().normalize();
        var output =
                java.nio.file.Path.of(
                                System.getProperty("pocoma.verification.reports", "target/reports"))
                        .getParent()
                        .resolve("bruno-b2");
        java.nio.file.Files.createDirectories(output);
        var inputs = brunoInputs(repo);
        java.nio.file.Files.writeString(
                output.resolve("inputs-before.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(inputs));
        var signal = output.resolve("controlled-internal-link");
        var receipts = output.resolve("requests.jsonl");
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var link =
                    threads.submit(
                            () -> {
                                try {
                                    long end =
                                            System.nanoTime() + Duration.ofSeconds(180).toNanos();
                                    while (!java.nio.file.Files.exists(signal)
                                            && System.nanoTime() < end) Thread.sleep(25);
                                    if (!java.nio.file.Files.exists(signal))
                                        throw new IllegalStateException(
                                                "B2 internal fixture handshake deadline");
                                    token =
                                            worker.write.queryForObject(
                                                    "select v.binding_token from v3_eui_authority a"
                                                            + " join v3_eui_version v on"
                                                            + " v.eui_id=a.eui_id and"
                                                            + " v.version=a.current_version where"
                                                            + " a.issuer=? and a.subject='owner'",
                                                    String.class,
                                                    http.issuer);
                                    internalLink("same");
                                    java.nio.file.Files.writeString(
                                            signal.resolveSibling(signal.getFileName() + ".ready"),
                                            "controlled-L3-link-completed");
                                    return true;
                                } catch (Exception failure) {
                                    throw new IllegalStateException(
                                            "B2 controlled L3 fixture failed");
                                }
                            });
            var command =
                    List.of(
                            repo.resolve("scripts/bruno-v3/node_modules/.bin/bru").toString(),
                            "run",
                            "-r",
                            "02 Admission and Commands",
                            "--env",
                            "Local",
                            "--sandbox",
                            "developer",
                            "--noproxy",
                            "--reporter-json",
                            output.resolve("bruno.json").toString(),
                            "--reporter-skip-all-headers",
                            "--reporter-skip-body");
            var process =
                    new ProcessBuilder(command)
                            .directory(repo.resolve("scripts/bruno-v3").toFile());
            var env = process.environment();
            env.put("POCOMA_I2_BASE_URL", http.baseUrl);
            env.put("POCOMA_I2_ISSUER", http.issuer);
            env.put("POCOMA_I2_bearerOwner", http.jwt("owner"));
            env.put("POCOMA_I2_bearerOther", http.jwt("other"));
            env.put("POCOMA_I2_LINK_SIGNAL", signal.toString());
            env.put("POCOMA_I2_REQUEST_RECEIPTS", receipts.toString());
            env.put(
                    "POCOMA_I2_HELPER",
                    repo.resolve("scripts/bruno-v3/lib/i2-admission.js").toString());
            Instant began = Instant.now();
            long monotonic = System.nanoTime();
            var running =
                    process.redirectErrorStream(true)
                            .redirectOutput(output.resolve("bruno.log").toFile())
                            .start();
            boolean finished = running.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                running.destroyForcibly();
                running.waitFor();
            }
            int exit = running.exitValue();
            var actual = new ArrayList<String>();
            int requests = 0;
            if (java.nio.file.Files.exists(receipts))
                for (String line : java.nio.file.Files.readAllLines(receipts)) {
                    var r = json.readTree(line);
                    actual.add(r.get("scenario").asText());
                    requests += r.get("requests").asInt();
                }
            actual.sort(String::compareTo);
            var expected =
                    java.util.stream.IntStream.rangeClosed(201, 224)
                            .mapToObj(n -> "B" + n)
                            .toList();
            java.nio.file.Files.writeString(
                    output.resolve("receipt.json"),
                    json.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(
                                    Map.of(
                                            "command",
                                            command,
                                            "cliVersion",
                                            "4.3.0",
                                            "startedAt",
                                            began.toString(),
                                            "finishedAt",
                                            Instant.now().toString(),
                                            "monotonicSeconds",
                                            (System.nanoTime() - monotonic) / 1e9,
                                            "exit",
                                            exit,
                                            "expected",
                                            expected,
                                            "actual",
                                            actual,
                                            "requests",
                                            requests,
                                            "provenance",
                                            "PUBLIC_ADMISSION_I2; internal Link fixture remains L3"
                                                    + " only")));
            if (exit != 0 || !actual.equals(expected)) {
                link.cancel(true);
                fail("B2 failed or incomplete; inspect retained reports");
            }
            assertTrue(link.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(inputs, brunoInputs(repo), "B2 executable inputs remained byte-identical");
            long
                    commands =
                            worker.write.queryForObject(
                                    "select count(*) from v3_command", Long.class),
                    identities =
                            worker.write.queryForObject(
                                    "select count(*) from v3_identity_request", Long.class),
                    associations =
                            worker.write.queryForObject(
                                    "select count(*) from v3_admission_association", Long.class);
            assertEquals(
                    commands + identities - 1,
                    associations,
                    "one controlled internal Link has no public association");
            assertEquals(
                    0,
                    worker.write.queryForObject(
                            "select count(*) from v3_admission_association a left join v3_command c"
                                + " on a.command_id=c.request_id left join v3_identity_request i on"
                                + " a.identity_request_id=i.request_id where c.request_id is null"
                                + " and i.request_id is null",
                            Integer.class));
            java.nio.file.Files.writeString(
                    output.resolve("sql-observer.json"),
                    json.writeValueAsString(
                            Map.of(
                                    "commands",
                                    commands,
                                    "identityRequests",
                                    identities,
                                    "associations",
                                    associations,
                                    "controlledInternalLink",
                                    1,
                                    "orphans",
                                    0)));
            for (var file :
                    java.nio.file.Files.list(output)
                            .filter(java.nio.file.Files::isRegularFile)
                            .toList()) {
                String content = java.nio.file.Files.readString(file);
                for (String secret :
                        List.of(
                                env.get("POCOMA_I2_bearerOwner"),
                                env.get("POCOMA_I2_bearerOther"),
                                token))
                    assertFalse(content.contains(secret), "B2 export contains sensitive material");
            }
        }
    }

    static Map<String, String> brunoInputs(java.nio.file.Path repo) throws Exception {
        var hashes = new TreeMap<String, String>();
        var root = repo.resolve("scripts/bruno-v3");
        try (var paths = java.nio.file.Files.walk(root)) {
            for (var p :
                    paths.filter(java.nio.file.Files::isRegularFile)
                            .filter(p -> !p.toString().contains("node_modules"))
                            .filter(
                                    p ->
                                            p.toString().endsWith(".bru")
                                                    || p.toString().endsWith(".js")
                                                    || p.toString().endsWith(".json"))
                            .toList())
                hashes.put(
                        repo.relativize(p).toString(),
                        HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(java.nio.file.Files.readAllBytes(p))));
        }
        return hashes;
    }
}
