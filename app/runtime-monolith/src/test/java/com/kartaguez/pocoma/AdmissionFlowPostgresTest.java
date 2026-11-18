package com.kartaguez.pocoma;
import com.kartaguez.pocoma.compose.polling.process.ProcessSettings;

import static com.kartaguez.pocoma.CompositionHttpFixture.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.domain.identity.*;
import com.kartaguez.pocoma.domain.identity.*;
import com.kartaguez.pocoma.infra.postgres.write.identity.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
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
class AdmissionFlowPostgresTest {
    @Container static final PostgreSQLContainer PG = container();

    @BeforeAll
    static void initialize() {
        initializeWorkerDatabase(PG);
    }

    ReadMonolithI8Fixture worker;
    CompositionHttpFixture http;
    final ObjectMapper json = new ObjectMapper();
    String token;
    String evidenceCase;
    long version;
    UUID pot = UUID.randomUUID(), share = UUID.randomUUID(), expense = UUID.randomUUID();

    @BeforeEach
    void setup(TestInfo test) throws Exception {
        evidenceCase = test.getTestMethod().orElseThrow().getName();
        worker = new ReadMonolithI8Fixture(PG);
        http = new CompositionHttpFixture(PG);
        Flyway.configure()
                .dataSource(worker.writeUrl, PG.getUsername(), PG.getPassword())
                .schemas(worker.schema)
                .defaultSchema(worker.schema)
                .locations(
                        "classpath:db/write/migration")
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
        worker.select(
                ProcessSettings.READ_NAMES.stream()
                        .filter(n -> !n.equals("MaterializeInvitation"))
                        .toList(),
                true);
    }

    void start() {
        http.useMaterializedRead(worker);
        var props = worker.compositionProperties(http.properties);
        var app = new SpringApplication(PocomaRuntimeMonolithApplication.class);
        app.setAdditionalProfiles("v3-read-worker", "v3-consumption-worker-dev", "v3-http-read", "v3-http-admission");
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
        assertEquals(3, context.getBeansOfType(HikariDataSource.class).size());
        assertNotSame(ReadMonolithI8Fixture.process(context).writeBusiness,
                context.getBean("admissionPool", HikariDataSource.class));
        assertEquals(
                1, context.getBean("admissionPool", HikariDataSource.class).getMaximumPoolSize());
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
        var binding = pollActiveBinding(subject, targetVersion);
        if (subject.equals("owner")) {
            token = binding.get("bindingToken").asText();
        }
        return id;
    }

    private JsonNode pollActiveBinding(String subject, long targetVersion) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        do {
            var response =
                    http.request("GET", "/me/binding", http.jwt(subject), null, null, Map.of());
            assertTrue(response.statusCode() == 200 || response.statusCode() == 404);
            var binding = response.statusCode() == 200 ? http.body(response) : null;
            if (binding != null && isActiveBindingReady(binding, targetVersion)) {
                return binding;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < end);
        fail("CurrentBinding deadline");
        return null;
    }

    private static boolean isActiveBindingReady(JsonNode binding, long targetVersion) {
        return binding.get("active").asBoolean()
                && binding.get("euiVersion").asLong() >= targetVersion;
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
        executePotAndShareholderLifecycle();
        executeExpenseLifecycle();
        execute("DeleteShareholder", Map.of("id", share.toString()));
        execute("DeletePot", Map.of());
        assertEquals(
                11,
                worker.write.queryForObject(
                        "select count(distinct operation) from v3_command", Integer.class));
    }

    private void executePotAndShareholderLifecycle() throws Exception {
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
    }

    private void executeExpenseLifecycle() throws Exception {
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
    }

    void internalLink(String target) throws Exception {
        // Seed an already completed historical occurrence for the public Unlink oracle.
        var initiator = new ExternalIdentity(http.issuer, "owner");
        var other = new ExternalIdentity(http.issuer, target);
        UUID id = UUID.randomUUID();
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
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
        var fixtureSql = new org.springframework.jdbc.core.JdbcTemplate(ds);
        var historical = java.nio.file.Files.readString(java.nio.file.Path.of(
                "../../scripts/testing/fixtures/complete-historical-link.sql"));
        tx.write(() -> {
            fixtureSql.execute(historical);
            fixtureSql.queryForList("select pg_temp.seed_historical_link(?,?,?,?,?,?,?,?::jsonb)",
                    r.requestId(), r.requesterEui().issuer(), r.requesterEui().subject(),
                    r.target().issuer(), r.target().subject(), r.bindingToken().value(),
                    java.sql.Timestamp.from(r.createdAt()), IdentityRequestJson.encode(r.admission()));
            fixtureSql.queryForList("select pg_temp.complete_historical_link(?)", id);
            return null;
        });
        poll("/identity/requests/" + id + "/result", "owner", null);
    }

    private record DepartureSnapshot(
            long historicalVersion,
            List<Map<String, Object>> identity,
            List<Map<String, Object>> expenses,
            List<Map<String, Object>> participants,
            List<Map<String, Object>> otherDetails) {}

    /** I4: qualify the delivered public DETACH contract, including the final affiliation. */
    @Test
    void voluntaryDeparturePreservesHistoryAndIdentity() throws Exception {
        start();
        register("owner");
        String creatorToken = token;
        execute("CreatePot", Map.of("label", "Departure"));
        UUID memberShare = UUID.randomUUID();
        execute(
                "AddShareholders",
                Map.of(
                        "targets",
                        List.of(
                                Map.of(
                                        "id",
                                        share.toString(),
                                        "name",
                                        "Creator",
                                        "attachment",
                                        "SELF",
                                        "weight",
                                        fraction(1, 1)),
                                Map.of(
                                        "id",
                                        memberShare.toString(),
                                        "name",
                                        "Member",
                                        "attachment",
                                        "FREE",
                                        "weight",
                                        fraction(1, 1)))));
        register("member");
        String memberToken = get("/me/binding", "member", null).get("bindingToken").asText();
        attachMemberViaControlledWriteFixture(memberShare);
        token = creatorToken;
        execute(
                "CreateExpense",
                Map.of(
                        "id",
                        expense.toString(),
                        "label",
                        "History",
                        "date",
                        "2026-11-05",
                        "amount",
                        fraction(10, 3),
                        "payerId",
                        memberShare.toString(),
                        "participants",
                        List.of(participant(share), participant(memberShare))));
        var snapshot = captureDepartureSnapshot();
        token = memberToken;
        verifyMemberDeparture(memberShare, snapshot);
        token = creatorToken;
        verifyFinalCreatorDeparture(snapshot);
    }

    private void attachMemberViaControlledWriteFixture(UUID memberShare) {
        // Controlled historical WRITE fixture only: ordinary SELF cannot admit an outsider.
        var fixtureSource =
                new DriverManagerDataSource(worker.writeUrl, PG.getUsername(), PG.getPassword());
        var fixtureManager = new DataSourceTransactionManager(fixtureSource);
        fixtureManager.setNestedTransactionAllowed(false);
        var fixtureOwner = new SpringAttemptTransactions(fixtureSource, fixtureManager, 5);
        var initialAffiliation =
                fixtureOwner.read(context -> computeMemberAttachment(fixtureSource, memberShare));
        fixtureOwner.write(
                () -> {
                    var potId = new com.kartaguez.pocoma.domain.pot.value.id.PotId(pot);
                    assertTrue(
                            new com.kartaguez.pocoma.infra.postgres.write.pot.JdbcPotCommandStore(
                                            fixtureSource, fixtureOwner)
                                    .protectPot(potId, version));
                    var revisions =
                            new com.kartaguez.pocoma.infra.postgres.write.pot.JdbcPotRevisionAdapter(
                                    fixtureSource);
                    revisions.recordVersion(potId, version, version + 1, Instant.now());
                    revisions.reviseShareholderDetails(potId, initialAffiliation);
                    return null;
                });
        version++;
    }

    @SuppressWarnings("unchecked")
    private com.kartaguez.pocoma.domain.pot.TransitionResult.Changed<
                    Map<
                            com.kartaguez.pocoma.domain.pot.value.id.ShareholderId,
                            com.kartaguez.pocoma.domain.pot.Versioned<
                                    com.kartaguez.pocoma.domain.pot.ShareholderDetails>>>
            computeMemberAttachment(DriverManagerDataSource fixtureSource, UUID memberShare) {
        var potId = new com.kartaguez.pocoma.domain.pot.value.id.PotId(pot);
        var shareholderId =
                new com.kartaguez.pocoma.domain.pot.value.id.ShareholderId(memberShare);
        var history =
                new com.kartaguez.pocoma.infra.postgres.write.pot.JdbcPotHistoryAdapter(fixtureSource);
        var member =
                new com.kartaguez.pocoma.domain.pot.value.UserId(
                        worker.write.queryForObject(
                                "select user_id from v3_eui_authority a join v3_eui_version v"
                                        + " on a.eui_id=v.eui_id and a.current_version=v.version"
                                        + " where a.issuer=? and a.subject=?",
                                UUID.class,
                                http.issuer,
                                "member"));
        var selected =
                history.current(
                                potId,
                                member,
                                new com.kartaguez.pocoma.engine.write.pot.port.persistence
                                        .PotHistoryPort.Selection(
                                        Set.of(shareholderId),
                                        Set.of(),
                                        false,
                                        true,
                                        false,
                                        false,
                                        false))
                        .orElseThrow();
        var target = selected.shareholders().get(shareholderId);
        var details =
                ((com.kartaguez.pocoma.domain.pot.ComponentRead.Present<
                                        com.kartaguez.pocoma.domain.pot.ShareholderDetails>)
                                target.details())
                        .revision();
        var decision =
                new com.kartaguez.pocoma.domain.pot.AttachShareholderViaInvitation(shareholderId)
                        .decide(selected.facts(), target.reference(), details);
        return (com.kartaguez.pocoma.domain.pot.TransitionResult.Changed<
                        Map<
                                com.kartaguez.pocoma.domain.pot.value.id.ShareholderId,
                                com.kartaguez.pocoma.domain.pot.Versioned<
                                        com.kartaguez.pocoma.domain.pot.ShareholderDetails>>>)
                decision;
    }

    private DepartureSnapshot captureDepartureSnapshot() {
        return new DepartureSnapshot(
                version,
                worker.write.queryForList("select * from v3_eui_version order by eui_id,version"),
                worker.write.queryForList(
                        "select * from v3_expense_financials order by revision_id"),
                worker.write.queryForList(
                        "select * from v3_expense_participant order by"
                                + " financial_revision_id,shareholder_id"),
                worker.write.queryForList(
                        "select * from v3_shareholder_details where object_id=? order by"
                                + " local_version",
                        share));
    }

    private void verifyMemberDeparture(UUID memberShare, DepartureSnapshot snapshot)
            throws Exception {
        var departure =
                body(
                        "UpdateShareholderDetails",
                        Map.of(
                                "targets",
                                List.of(
                                        Map.of(
                                                "id",
                                                memberShare.toString(),
                                                "name",
                                                "Member",
                                                "attachment",
                                                "DETACH"))));
        UUID departed = accepted(post("/commands", "member", "member-depart", departure));
        var result = poll("/commands/" + departed + "/result", "member", null);
        assertEquals("COMPLETED", result.get("outcome").asText());
        version = result.get("resultingPotVersion").asLong();
        assertMemberDepartureState(memberShare, departed, departure, snapshot);
        UUID denied =
                accepted(
                        post(
                                "/commands",
                                "member",
                                "departed-cannot-mutate",
                                body("UpdatePotDetails", Map.of("label", "Forbidden"))));
        assertEquals(
                "REJECTED",
                poll("/commands/" + denied + "/result", "member", null).get("outcome").asText());
    }

    private void assertMemberDepartureState(
            UUID memberShare, UUID departed, Map<String, Object> departure, DepartureSnapshot snapshot) {
        assertAll(
                () -> assertEquals(snapshot.historicalVersion() + 1, version),
                () ->
                        assertEquals(
                                departed,
                                accepted(post("/commands", "member", "member-depart", departure))),
                () ->
                        assertNull(
                                worker.write.queryForObject(
                                        "select user_id from v3_shareholder_details where object_id=? and active_to is null",
                                        UUID.class,
                                        memberShare)),
                () ->
                        assertEquals(
                                3,
                                worker.write.queryForObject(
                                        "select count(*) from v3_shareholder_details where object_id=?",
                                        Integer.class,
                                        memberShare)),
                () ->
                        assertFalse(
                                worker.write.queryForObject(
                                        "select deleted from v3_shareholder_header where object_id=? and active_to is null",
                                        Boolean.class,
                                        memberShare)),
                () ->
                        assertEquals(
                                snapshot.identity(),
                                worker.write.queryForList(
                                        "select * from v3_eui_version order by eui_id,version")),
                () ->
                        assertEquals(
                                snapshot.expenses(),
                                worker.write.queryForList(
                                        "select * from v3_expense_financials order by revision_id")),
                () ->
                        assertEquals(
                                snapshot.participants(),
                                worker.write.queryForList(
                                        "select * from v3_expense_participant order by"
                                                + " financial_revision_id,shareholder_id")),
                () ->
                        assertEquals(
                                snapshot.otherDetails(),
                                worker.write.queryForList(
                                        "select * from v3_shareholder_details where object_id=? order by local_version",
                                        share)));
    }

    private void verifyFinalCreatorDeparture(DepartureSnapshot snapshot) throws Exception {
        // The creator is now the final affiliate; departure preserves independent creator rights.
        execute(
                "UpdateShareholderDetails",
                Map.of(
                        "targets",
                        List.of(
                                Map.of(
                                        "id",
                                        share.toString(),
                                        "name",
                                        "Creator",
                                        "attachment",
                                        "DETACH"))));
        assertAll(
                () ->
                        assertEquals(
                                0,
                                worker.write.queryForObject(
                                        "select count(*) from v3_shareholder_details where active_to is null and user_id is not null",
                                        Integer.class)),
                () ->
                        assertEquals(
                                2,
                                worker.write.queryForObject(
                                        "select count(*) from v3_shareholder_header where active_to is null and not deleted",
                                        Integer.class)));
        execute("UpdatePotDetails", Map.of("label", "Creator still authorized"));
        assertAll(
                () ->
                        assertEquals(
                                snapshot.identity(),
                                worker.write.queryForList(
                                        "select * from v3_eui_version order by eui_id,version")),
                () ->
                        assertEquals(
                                snapshot.expenses(),
                                worker.write.queryForList(
                                        "select * from v3_expense_financials order by revision_id")));
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
        var result = execute("AddShareholders", Map.of("targets", selfAttachmentTargets(101)));
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
        unlinkAndReregisterOwner();
        assertAll(
                () -> assertNotEquals(oldToken, token),
                () ->
                        assertEquals(
                                "COMPLETED",
                                get("/commands/" + request + "/result", "owner", null)
                                        .get("outcome")
                                        .asText()),
                () ->
                        assertEquals(
                                101,
                                worker.write.queryForObject(
                                        "select count(*) from v3_shareholder_details where user_id=?",
                                        Integer.class,
                                        oldUser)));
        verifyStaleBindingRejected(oldToken);
    }

    private List<Object> selfAttachmentTargets(int count) {
        var targets = new ArrayList<Object>(count);
        for (int n = 0; n < count; n++) {
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
        }
        return targets;
    }

    private void unlinkAndReregisterOwner() throws Exception {
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
    }

    private void verifyStaleBindingRejected(String oldToken) throws Exception {
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
        var brunoOutput = createBrunoOutputDir();
        var inputs = brunoInputs(repo);
        java.nio.file.Files.writeString(
                brunoOutput.resolve("inputs-before.json"),
                json.writerWithDefaultPrettyPrinter().writeValueAsString(inputs));
        var signal = brunoOutput.resolve("controlled-internal-link");
        var receipts = brunoOutput.resolve("requests.jsonl");
        try (var threads = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var link = threads.submit(() -> awaitAndPerformControlledLink(signal));
            var command = brunoB2Command(repo, brunoOutput);
            var process = brunoB2Process(repo, command, signal, receipts);
            Instant began = Instant.now();
            long monotonic = System.nanoTime();
            int exit = runProcessWithTimeout(process, brunoOutput.resolve("bruno.log"));
            var actual = readActualScenarios(receipts);
            var expected =
                    java.util.stream.IntStream.rangeClosed(201, 224)
                            .mapToObj(n -> "B" + n)
                            .toList();
            writeBrunoReceipt(
                    new BrunoReceipt(
                            brunoOutput, command, began, monotonic, exit, expected, actual));
            if (exit != 0 || !actual.equals(expected)) {
                link.cancel(true);
                fail("B2 failed or incomplete; inspect retained reports");
            }
            assertTrue(link.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(inputs, brunoInputs(repo), "B2 executable inputs remained byte-identical");
            verifyBrunoSqlAssociations(brunoOutput);
            verifyNoLeakedSecrets(brunoOutput, process.environment());
        }
    }

    private static java.nio.file.Path createBrunoOutputDir() throws Exception {
        var output =
                java.nio.file.Path.of(
                                System.getProperty("pocoma.verification.reports", "target/reports"))
                        .toAbsolutePath()
                        .getParent();
        java.nio.file.Files.createDirectories(output);
        return java.nio.file.Files.createTempDirectory(output, "bruno-b2-");
    }

    private boolean awaitAndPerformControlledLink(java.nio.file.Path signal) {
        try {
            long end = System.nanoTime() + Duration.ofSeconds(180).toNanos();
            while (!java.nio.file.Files.exists(signal) && System.nanoTime() < end) {
                Thread.sleep(25);
            }
            if (!java.nio.file.Files.exists(signal)) {
                throw new IllegalStateException("B2 internal fixture handshake deadline");
            }
            token =
                    worker.write.queryForObject(
                            "select v.binding_token from v3_eui_authority a join v3_eui_version v"
                                    + " on v.eui_id=a.eui_id and v.version=a.current_version where"
                                    + " a.issuer=? and a.subject='owner'",
                            String.class,
                            http.issuer);
            internalLink("same");
            java.nio.file.Files.writeString(
                    signal.resolveSibling(signal.getFileName() + ".ready"),
                    "controlled-L3-link-completed");
            return true;
        } catch (Exception failure) {
            throw new IllegalStateException("B2 controlled L3 fixture failed");
        }
    }

    private static List<String> brunoB2Command(
            java.nio.file.Path repo, java.nio.file.Path brunoOutput) {
        return List.of(
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
                brunoOutput.resolve("bruno.json").toString(),
                "--reporter-skip-all-headers",
                "--reporter-skip-body");
    }

    private ProcessBuilder brunoB2Process(
            java.nio.file.Path repo,
            List<String> command,
            java.nio.file.Path signal,
            java.nio.file.Path receipts)
            throws Exception {
        var process =
                new ProcessBuilder(command).directory(repo.resolve("scripts/bruno-v3").toFile());
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
        return process;
    }

    private static int runProcessWithTimeout(ProcessBuilder process, java.nio.file.Path logFile)
            throws Exception {
        var running = process.redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
        boolean finished = running.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            running.destroyForcibly();
            running.waitFor();
        }
        return running.exitValue();
    }

    private record ScenarioRun(List<String> actual, int requests) {}

    private ScenarioRun readScenarioRun(java.nio.file.Path receipts) throws Exception {
        var actual = new ArrayList<String>();
        int requests = 0;
        if (java.nio.file.Files.exists(receipts)) {
            for (String line : java.nio.file.Files.readAllLines(receipts)) {
                var r = json.readTree(line);
                actual.add(r.get("scenario").asText());
                requests += r.get("requests").asInt();
            }
        }
        actual.sort(String::compareTo);
        return new ScenarioRun(actual, requests);
    }

    private List<String> readActualScenarios(java.nio.file.Path receipts) throws Exception {
        return readScenarioRun(receipts).actual();
    }

    private record BrunoReceipt(
            java.nio.file.Path output,
            List<String> command,
            Instant began,
            long monotonic,
            int exit,
            List<String> expected,
            List<String> actual) {}

    private void writeBrunoReceipt(BrunoReceipt receipt) throws Exception {
        int requests = readScenarioRun(receipt.output().resolve("requests.jsonl")).requests();
        java.nio.file.Files.writeString(
                receipt.output().resolve("receipt.json"),
                json.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(
                                Map.of(
                                        "command",
                                        receipt.command(),
                                        "cliVersion",
                                        "4.3.0",
                                        "startedAt",
                                        receipt.began().toString(),
                                        "finishedAt",
                                        Instant.now().toString(),
                                        "monotonicSeconds",
                                        (System.nanoTime() - receipt.monotonic()) / 1e9,
                                        "exit",
                                        receipt.exit(),
                                        "expected",
                                        receipt.expected(),
                                        "actual",
                                        receipt.actual(),
                                        "requests",
                                        requests,
                                        "provenance",
                                        "PUBLIC_ADMISSION_I2; internal Link fixture remains L3"
                                                + " only")));
    }

    private void verifyBrunoSqlAssociations(java.nio.file.Path brunoOutput) throws Exception {
        long commands =
                worker.write.queryForObject("select count(*) from v3_command", Long.class);
        long identities =
                worker.write.queryForObject(
                        "select count(*) from v3_identity_request", Long.class);
        long associations =
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
                brunoOutput.resolve("sql-observer.json"),
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
    }

    private void verifyNoLeakedSecrets(
            java.nio.file.Path brunoOutput, Map<String, String> env) throws Exception {
        List<String> secrets =
                List.of(
                        env.get("POCOMA_I2_bearerOwner"),
                        env.get("POCOMA_I2_bearerOther"),
                        token);
        for (var file :
                java.nio.file.Files.list(brunoOutput)
                        .filter(java.nio.file.Files::isRegularFile)
                        .toList()) {
            String content = java.nio.file.Files.readString(file);
            for (String secret : secrets) {
                assertFalse(content.contains(secret), "B2 export contains sensitive material");
            }
        }
    }

    private static boolean isBrunoInputFile(java.nio.file.Path path) {
        String value = path.toString();
        boolean isAllowedExt =
                value.endsWith(".bru") || value.endsWith(".js") || value.endsWith(".json");
        return !value.contains("node_modules") && isAllowedExt;
    }

    static Map<String, String> brunoInputs(java.nio.file.Path repo) throws Exception {
        var hashes = new TreeMap<String, String>();
        var root = repo.resolve("scripts/bruno-v3");
        try (var paths = java.nio.file.Files.walk(root)) {
            for (var p :
                    paths.filter(java.nio.file.Files::isRegularFile)
                            .filter(AdmissionFlowPostgresTest::isBrunoInputFile)
                            .toList()) {
                hashes.put(
                        repo.relativize(p).toString(),
                        HexFormat.of()
                                .formatHex(
                                        java.security.MessageDigest.getInstance("SHA-256")
                                                .digest(java.nio.file.Files.readAllBytes(p))));
            }
        }
        return hashes;
    }
}
