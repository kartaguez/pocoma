package com.kartaguez.pocoma;

import static com.kartaguez.pocoma.domain.projection.v3.SubobjectContent.*;
import static com.kartaguez.pocoma.domain.projection.v3.SubobjectKind.*;
import com.fasterxml.jackson.databind.*;
import com.kartaguez.pocoma.domain.pot.v3.*;
import com.kartaguez.pocoma.domain.pot.v3.Fraction;
import com.kartaguez.pocoma.domain.pot.value.*;
import com.kartaguez.pocoma.domain.pot.value.id.*;
import com.kartaguez.pocoma.domain.projection.v3.*;
import com.kartaguez.pocoma.domain.identity.*;
import com.kartaguez.pocoma.domain.identity.v3.*;
import com.kartaguez.pocoma.engine.read.pot.v3.*;
import com.kartaguez.pocoma.infra.persistence.read.jdbc.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.runtime.consumption.v3.*;
import com.kartaguez.pocoma.supra.web.read.v3.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.*;
import org.flywaydb.core.Flyway;
import org.springframework.boot.*;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.Date;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only injector, signing fixture and independent SQL/HTTP observer. No public admission. */
final class V3ReadHttpFixture implements AutoCloseable {
    static final String IMAGE = "postgres@sha256:18cfe3ef5e6815560c98237d6216d1e5119702fb0f3894c8785dd58b8bbe5d73";
    static PostgreSQLContainer container() { return new PostgreSQLContainer(DockerImageName.parse(IMAGE).asCompatibleSubstituteFor("postgres"))
        .withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements", "-c", "track_io_timing=on", "-c", "log_min_error_statement=panic", "-c", "log_line_prefix=%m [%p] xid=%x state=%e "); }
    static UUID id(long n) { return UUID.fromString("00000000-0000-0000-0000-" + String.format(java.util.Locale.ROOT, "%012d", n)); }
    static final UUID OWNER = id(101), OTHER = id(102), S1 = id(201), S2 = id(202), POT = id(300), FORBIDDEN = id(301), DELETED = id(302), LAG = id(303);
    static final String TOKEN = "I1-OWN-BINDING-CANARY", OTHER_TOKEN = "I1-OTHER-BINDING-CANARY";
    final String schema = "i1_" + UUID.randomUUID().toString().replace("-", ""), role = schema + "_http";
    final PostgreSQLContainer pg;
    final JdbcTemplate admin;
    final SpringAttemptTransactions injector;
    final JdbcSubobjectVersionStore objects;
    final JdbcPotVersionStore pots;
    final JdbcPotBalanceStore balances;
    final HttpServer jwks;
    final RSAKey key;
    final String issuer, url;
    final ObjectMapper json = new ObjectMapper();
    final Map<String, Object> properties = new LinkedHashMap<>();
    ConfigurableApplicationContext context;
    String baseUrl;
    final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    V3ReadHttpFixture(PostgreSQLContainer pg) throws Exception {
        this.pg = pg;
        var control = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        if (!control.queryForObject("select exists(select 1 from pg_database where datname='i1_read')", Boolean.class)) control.execute("create database i1_read");
        url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/i1_read?currentSchema=" + schema + ",public";
        var root = new JdbcTemplate(new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword()));
        root.execute("create extension if not exists pgcrypto with schema public");
        root.execute("create extension if not exists pg_stat_statements with schema public");
        root.execute("create role " + role + " login password 'ephemeral-i1-fixture'");
        Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/v3-consumption-read/migration", "classpath:db/v3-read-business/migration")
            .placeholders(Map.of("expectedDatabase", "i1_read", "expectedSchema", schema, "expectedAdmin", pg.getUsername(), "expectedRuntime", role)).load().migrate();
        var ds = new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword()); admin = new JdbcTemplate(ds);
        // Fixture HTTP role can only SELECT; injector and independent observer retain separate
        // authority.
        admin.execute("revoke all on all tables in schema " + schema + " from " + role);
        admin.execute("grant usage on schema " + schema + " to " + role);
        admin.execute("grant select on all tables in schema " + schema + " to " + role);
        ensureMaintenanceTable(admin, role);
        var manager = new DataSourceTransactionManager(ds); manager.setNestedTransactionAllowed(false);
        injector = new SpringAttemptTransactions(ds, manager, 60);
        objects = new JdbcSubobjectVersionStore(ds, injector); pots = new JdbcPotVersionStore(ds, injector); balances = new JdbcPotBalanceStore(ds, injector);
        key = new RSAKeyGenerator(2048).keyID("i1-local").generate();
        jwks = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        jwks.createContext("/jwks", exchange -> {
            byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        }); jwks.start(); issuer = "http://127.0.0.1:" + jwks.getAddress().getPort();
        properties.put("server.port", "0"); properties.put("server.address", "127.0.0.1");
        properties.put("spring.main.banner-mode", "off"); properties.put("logging.level.root", "WARN");
        properties.put("spring.flyway.enabled", "false"); properties.put("spring.jpa.open-in-view", "false"); properties.put("management.health.db.enabled", "false");
        properties.put("pocoma.v3.http.issuer", issuer); properties.put("pocoma.v3.http.audience", "pocoma-i1"); properties.put("pocoma.v3.http.jwk-set-uri", issuer + "/jwks");
        properties.put("pocoma.v3.http.read.url", url); properties.put("pocoma.v3.http.read.username", role); properties.put("pocoma.v3.http.read.password", "ephemeral-i1-fixture");
        binding("owner", 1, OWNER, TOKEN); binding("other", 1, OTHER, OTHER_TOKEN); binding("same", 1, OWNER, "I1-SAME-USER-CANARY"); binding("inactive", 2, null, null);
    }
    static void initializeWorkerDatabase(PostgreSQLContainer pg) {
        var root = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        root.execute("create extension if not exists pgcrypto with schema public");
        root.execute("create database i5_read");
        new JdbcTemplate(new DriverManagerDataSource("jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/i5_read", pg.getUsername(), pg.getPassword())).execute("create extension pgcrypto with schema public");
    }

    private void ensureMaintenanceTable(JdbcTemplate readAdmin, String readRole) {
        String sql = String.format("""
            CREATE TABLE IF NOT EXISTS public.read_maintenance_state (
                id INT PRIMARY KEY DEFAULT 1 CHECK (id = 1),
                state VARCHAR(32) NOT NULL CHECK (state IN ('READY', 'MAINTENANCE', 'REBUILDING', 'FAILED')),
                phase VARCHAR(32) NOT NULL CHECK (phase IN (
                    'IDLE', 'DRAINING', 'EXCLUDED', 'SCHEMA_DROPPED',
                    'SCHEMA_CREATED', 'MIGRATED', 'ROUTED', 'REPLAYING', 'CONVERGED'
                )),
                target_prefix JSONB NULL,
                operator_id VARCHAR(64) NULL,
                operation_id UUID NULL,
                started_at TIMESTAMPTZ NULL,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
                error_detail TEXT NULL
            );
            ALTER TABLE public.read_maintenance_state ADD COLUMN IF NOT EXISTS operation_id UUID NULL;
            ALTER TABLE public.read_maintenance_state OWNER TO %s;
            REVOKE ALL ON public.read_maintenance_state FROM PUBLIC;
            REVOKE ALL ON public.read_maintenance_state FROM %s;
            GRANT SELECT ON public.read_maintenance_state TO %s;
            INSERT INTO public.read_maintenance_state (id, state, phase, updated_at)
            VALUES (1, 'READY', 'IDLE', clock_timestamp())
            ON CONFLICT (id) DO UPDATE SET
                state = 'READY', phase = 'IDLE', target_prefix = NULL, operator_id = NULL, operation_id = NULL,
                started_at = NULL, error_detail = NULL, updated_at = clock_timestamp();
            """, pg.getUsername(), readRole, readRole);
        readAdmin.execute(sql);
    }

    JdbcTemplate admissionObserver;
    String admissionUrl, admissionRole, admissionSchema;

    void enableAdmission() throws Exception {
        admissionSchema = schema + "_w";
        admissionRole = schema + "_admission";
        String worker = schema + "_w_worker";
        admissionUrl = pg.getJdbcUrl() + "&currentSchema=" + admissionSchema + ",public";
        var ds = new DriverManagerDataSource(admissionUrl, pg.getUsername(), pg.getPassword());
        admissionObserver = new JdbcTemplate(ds);
        admissionObserver.execute("create extension if not exists pgcrypto with schema public");
        admissionObserver.execute("create role " + worker + " nologin");
        admissionObserver.execute(
                "create role " + admissionRole + " login password 'ephemeral-i2'");
        Flyway.configure()
                .dataSource(ds)
                .schemas(admissionSchema)
                .defaultSchema(admissionSchema)
                .locations(
                        "classpath:db/v3-pot/migration",
                        "classpath:db/v3-pot-execution/migration",
                        "classpath:db/v3-consumption-write/migration",
                        "classpath:db/v3-identity/migration",
                        "classpath:db/v3-admission/migration")
                .placeholders(
                        Map.of(
                                "expectedDatabase",
                                pg.getDatabaseName(),
                                "expectedSchema",
                                admissionSchema,
                                "expectedAdmin",
                                pg.getUsername(),
                                "expectedRuntime",
                                worker))
                .load()
                .migrate();
        grantAdmission(admissionSchema, admissionRole);
        admissionProperties(admissionUrl, admissionSchema, admissionRole);
    }

    void grantAdmission(String targetSchema, String targetRole) throws Exception {
        String script = "grant-l5-admission.sql", destination = "/tmp/" + schema + script;
        pg.copyFileToContainer(
                org.testcontainers.utility.MountableFile.forHostPath(
                        Path.of("../../scripts/v3/" + script).toAbsolutePath()),
                destination);
        var grant =
                pg.execInContainer(
                        "env",
                        "PGOPTIONS=-c search_path=" + targetSchema,
                        "psql",
                        "-X",
                        "-U",
                        pg.getUsername(),
                        "-d",
                        pg.getDatabaseName(),
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-v",
                        "expected_database=" + pg.getDatabaseName(),
                        "-v",
                        "expected_schema=" + targetSchema,
                        "-v",
                        "expected_admin=" + pg.getUsername(),
                        "-v",
                        "admission_role=" + targetRole,
                        "-f",
                        destination);
        if (grant.getExitCode() != 0)
            throw new IllegalStateException("Admission grant failed: " + grant.getStderr());
    }

    void admissionProperties(String targetUrl, String targetSchema, String targetRole) {
        String p = "pocoma.v3.http.admission.";
        properties.put(p + "url", targetUrl);
        properties.put(p + "username", targetRole);
        properties.put(p + "password", "ephemeral-i2");
        properties.put(p + "database", pg.getDatabaseName());
        properties.put(p + "schema", targetSchema);
        properties.put(p + "lookup-key-id", "stable");
        properties.put(p + "lookup-key", Base64.getEncoder().encodeToString(new byte[32]));
        properties.put(p + "comparison-current", "k1");
        properties.put(p + "comparison-key-ids", "k1");
        properties.put(p + "comparison-keys.k1", Base64.getEncoder().encodeToString(new byte[32]));
    }

    void start(Class<?>... roots) {
        var app = new SpringApplication(roots); app.setAdditionalProfiles(
                properties.containsKey("pocoma.v3.http.admission.url")
                        ? new String[] {V3ReadHttpConfiguration.PROFILE, "v3-http-admission"}
                        : new String[] {V3ReadHttpConfiguration.PROFILE}); app.setDefaultProperties(properties);
        app.addInitializers(ctx -> ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("i1-isolated-fixture", properties)));
        context = app.run(); baseUrl = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
        // Both physical connections must finish lazy JDBC initialization before zero-SQL oracles.
        var before = sqlStats();
        try (var one = pool().getConnection(); var two = pool().getConnection()) {
            for (var connection : List.of(one, two)) try (var statement = connection.createStatement(); var result = statement.executeQuery("select 1")) {
                if (!result.next() || result.getInt(1) != 1) throw new IllegalStateException("READ fixture initialization failed");
            }
        } catch (java.sql.SQLException failed) { throw new IllegalStateException("READ fixture pool initialization failed", failed); }
        if (pool().getHikariPoolMXBean().getTotalConnections() != 2 || pool().getHikariPoolMXBean().getActiveConnections() != 0)
            throw new IllegalStateException("READ fixture pool not initialized");
        try { evidence("HTTP-pool-initialization-" + schema, Map.of("before", before, "after", sqlStats(), "physicalConnectionsInitialized", 2)); }
        catch (IOException failed) { throw new IllegalStateException("READ fixture initialization evidence failed", failed); }

    }
    ExternalIdentity eui(String subject) { return new ExternalIdentity(issuer, subject); }
    void binding(String subject, long version, UUID user, String token) {
        injector.write(() -> {
            new JdbcCurrentBindingStore(admin.getDataSource(), injector).apply(new ExternalUserIdentity(
                new ExternalUserIdentityId(UUID.nameUUIDFromBytes((issuer + "/" + subject).getBytes(StandardCharsets.UTF_8))), eui(subject), version,
                user == null ? null : new PocomaUserId(user), token == null ? null : new BindingToken(token))); return null;
        });
    }
    String jwt(String subject) throws Exception { return jwt(subject, issuer, "pocoma-i1", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600), Instant.now().minusSeconds(2), key, JWSAlgorithm.RS256); }
    String jwt(String subject, String iss, String audience, Instant issued, Instant expiry, Instant notBefore, RSAKey signing, JWSAlgorithm algorithm) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(iss).subject(subject).audience(audience).issueTime(Date.from(issued)).expirationTime(Date.from(expiry)).notBeforeTime(Date.from(notBefore)).claim("auth_time", issued.getEpochSecond()).build();
        var signed = new SignedJWT(new JWSHeader.Builder(algorithm).keyID("i1-local").build(), claims); signed.sign(new RSASSASigner(signing)); return signed.serialize();
    }
    HttpResponse<byte[]> request(String method, String path, String bearer, String token, byte[] body, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(baseUrl + "/api/v3" + path)).timeout(Duration.ofSeconds(10));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (token != null) builder.header("X-Pocoma-Binding-Token", token);
        headers.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    HttpResponse<byte[]> get(String path) throws Exception { return request("GET", path, jwt("owner"), TOKEN, null, Map.of()); }
    HttpResponse<byte[]> post(String path, String body) throws Exception { return request("POST", path, jwt("owner"), TOKEN, body.getBytes(StandardCharsets.UTF_8), Map.of("Content-Type", "application/json")); }
    JsonNode body(HttpResponse<byte[]> response) throws Exception { return json.readTree(response.body()); }
    String batch(int count, long version) {
        var refs = new ArrayList<String>(); for (int n = 0; n < count; n++) refs.add(ref(1000 + n, version)); return "{\"references\":[" + String.join(",", refs) + "]}";
    }
    static String ref(long id, long version) { return "{\"id\":\"" + id(id) + "\",\"headerVersion\":" + version + ",\"detailsVersion\":" + version + ",\"financialsVersion\":" + version + ",\"deleted\":false}"; }
    String path(UUID pot, long version) { return "/pots/" + pot + "/versions/" + version; }
    void seed(UUID pot, long version, int expenseCount, int shareholders, Set<UUID> allowed, boolean deleted, boolean withBalance, boolean expenseDetails, String label) {
        seedProfile(pot, version, expenseCount, shareholders, allowed, deleted, withBalance, expenseDetails, label, 0, false);
    }
    void seedProfile(UUID pot, long version, int expenseCount, int shareholders, Set<UUID> allowed, boolean deleted, boolean withBalance, boolean expenseDetails, String label, int digits, boolean expenseRationals) {
        Fraction rational = digits == 0 ? new Fraction(2, 3) : new Fraction(java.math.BigInteger.TEN.pow(digits-1).add(java.math.BigInteger.ONE), java.math.BigInteger.TEN.pow(digits-1).add(java.math.BigInteger.valueOf(3)));

        var shares = new ArrayList<PotVersion.Shareholder>(); var refs = new ArrayList<PotVersion.Expense>(); var parts = new LinkedHashMap<ShareholderId, Optional<com.kartaguez.pocoma.domain.pot.v3.Weight>>();
        var values = new ArrayList<SubobjectVersion>(); var vector = new LinkedHashMap<UUID, Fraction>();
        add(values, pot, POT_HEADER, pot, version, new PotHeaderContent(new PotHeader(new PotId(pot), deleted)));
        add(values, pot, POT_DETAILS, pot, version, new PotDetailsContent(new PotDetails(new PotId(pot), new Label(label), new UserId(OWNER))));
        for (int n = 0; n < shareholders; n++) {
            UUID s = id(201 + n); shares.add(new PotVersion.Shareholder(s, version, version, version, false, true));
            add(values, pot, SHAREHOLDER_HEADER, s, version, new ShareholderHeaderContent(new ShareholderHeader(new PotId(pot), new ShareholderId(s), false)));
            add(values, pot, SHAREHOLDER_DETAILS, s, version, new ShareholderDetailsContent(new ShareholderDetails(new PotId(pot), new ShareholderId(s), new Name("share-" + n), Optional.empty())));
            add(values, pot, SHAREHOLDER_WEIGHT, s, version, new ShareholderWeightContent(new ShareholderWeight(new PotId(pot), new ShareholderId(s), new com.kartaguez.pocoma.domain.pot.v3.Weight(rational))));
            parts.put(new ShareholderId(s), expenseRationals ? Optional.of(new com.kartaguez.pocoma.domain.pot.v3.Weight(rational)) : Optional.empty()); vector.put(s, n == 0 ? new Fraction(1, 3) : n == 1 ? new Fraction(-1, 3) : Fraction.ZERO);
        }
        for (int n = 0; n < expenseCount; n++) {
            UUID e = id(1000 + n); refs.add(new PotVersion.Expense(e, version, version, version, false));
            add(values, pot, EXPENSE_HEADER, e, version, new ExpenseHeaderContent(new ExpenseHeader(new PotId(pot), new ExpenseId(e), false)));
            if (expenseDetails) add(values, pot, EXPENSE_DETAILS, e, version, new ExpenseDetailsContent(new ExpenseDetails(new PotId(pot), new ExpenseId(e), new Label("dépense\\\"-" + n), LocalDate.of(2026, 11, 3))));
            add(values, pot, EXPENSE_FINANCIALS, e, version, new ExpenseFinancialsContent(new ExpenseFinancials(new PotId(pot), new ExpenseId(e), new com.kartaguez.pocoma.domain.pot.v3.Amount(expenseRationals ? rational : new Fraction(1, 3)), new ShareholderId(S1), parts)));
        }
        injector.write(() -> { objects.insertOrVerifyAll(values); pots.insertAndAdvance(new PotVersion(pot, version, version, version, deleted, shares, refs, allowed, Set.of()));
            if (withBalance && !deleted) balances.insertOrVerify(new PotBalance(pot, version, vector)); return null; });
    }
    void seed(UUID pot, long version, int count, Set<UUID> allowed, boolean deleted, boolean balance, boolean details, String label) { seed(pot, version, count, 2, allowed, deleted, balance, details, label); }
    static void add(List<SubobjectVersion> values, UUID pot, SubobjectKind kind, UUID object, long version, SubobjectContent content) { values.add(new SubobjectVersion(new SubobjectKey(pot, kind, object, version), content)); }
    void results() {
        for (boolean rejected : List.of(false, true)) {
            admin.update("insert into read_command_result values (?,'CreatePot',?,?,?, ?,?,?)", id(rejected ? 402 : 401), issuer, "owner", POT, rejected ? "REJECTED" : "CHANGED", rejected ? null : 2L, rejected ? "INVALID_BINDING" : null);
            admin.update(
                    "insert into read_identity_request_result values (?,'REGISTRATION',?,?,?,"
                            + " ?,?,?,?)", id(rejected ? 404 : 403), issuer, "owner", rejected ? "REJECTED" : "CHANGED", rejected ? null : issuer, rejected ? null : "owner", rejected ? null : 1L, rejected ? "INVALID_BINDING" : null);
        }
    }
    void navigation(int count) { for (int n = 1; n <= count; n++) admin.update("insert into read_user_pot values (?,?,1,true)", OWNER, id(10000 + n)); }
    void corpus() {
        seed(POT, 1, 200, Set.of(OWNER), false, true, true, "Pot V1"); seed(POT, 2, 200, Set.of(OWNER), false, true, true, "Pot V2");
        seed(FORBIDDEN, 1, 0, Set.of(OTHER), false, true, true, "private");
        seed(DELETED, 1, 2, Set.of(OWNER), false, true, true, "before delete"); seed(DELETED, 2, 2, Set.of(OWNER), true, false, true, "deleted");
        seed(LAG, 1, 0, Set.of(OWNER), false, false, true, "lag"); results(); navigation(205);
        seed(id(10001), 1, 0, Set.of(OWNER), false, true, true, "navigation-open");
        seed(id(304), 1, 0, Set.of(OWNER), false, true, true, "x".repeat(4_194_304));
        admin.update(
                "insert into read_identity_request_result values"
                        + " (?,'UNLINK',?,?,'CHANGED',?,?,2,null)", id(407), issuer, "owner", issuer, "owner");
    }
    HikariDataSource pool() { return context.getBean("v3HttpReadPool", HikariDataSource.class); }
    V3ReadFacadeComposition.Services services() { return context.getBean(V3ReadFacadeComposition.Services.class); }
    long activeBackends() { return admin.queryForObject("select count(*) from pg_stat_activity where usename=? and state<>'idle'", Long.class, role); }
    List<Map<String, Object>> sqlStats() { return admin.queryForList(
                "select query,calls,rows,total_exec_time from public.pg_stat_statements where"
                        + " userid=(select oid from pg_roles where rolname=?) order by query", role); }
    void evidence(String name, Object observation) throws IOException {
        String destination = System.getenv("POCOMA_I1_PHYSICAL"); if (destination == null) return;
        Path dir = Path.of(destination); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(observation) + "\n");
    }
    /** Separate B1 lifecycle, launched after T1 by the shared campaign owner. */
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]).toAbsolutePath(), output = Path.of(args[1]).toAbsolutePath(); Files.createDirectories(output);
        long started = System.nanoTime();
        try (var pg = container()) {
            pg.start();
            try (var fixture = new V3ReadHttpFixture(pg)) {
                fixture.corpus(); fixture.start(PocomaWebApiApplication.class);
                Path signal = Files.createTempDirectory("pocoma-i1-publisher-").resolve("publish");
                boolean expensesOnly = "true".equals(System.getenv("POCOMA_I1_BRUNO_EXPENSES_ONLY"));
                String folder = expensesOnly ? "01 Identity and READ/03 Expense Batches" : "01 Identity and READ";
                var expectedScenarios = java.util.stream.IntStream.rangeClosed(expensesOnly ? 15 : 1, expensesOnly ? 20 : 32).mapToObj(n -> "B%02d".formatted(n)).toList();
                try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                    Future<Boolean> publisher = expensesOnly ? CompletableFuture.completedFuture(true) : executor.submit(() -> {
                        try {
                            long end = System.nanoTime() + Duration.ofSeconds(120).toNanos();
                            while (!Files.exists(signal) && System.nanoTime() < end) Thread.sleep(10);
                            if (!Files.exists(signal)) throw new IllegalStateException(
                                                                "B30 publication handshake not"
                                                                        + " observed");
                            fixture.admin.update(
                                                            "insert into read_command_result values"
                                                                + " (?,'CreatePot',?,?,?,'CHANGED',2,null)", id(405), fixture.issuer, "owner", POT);
                            fixture.admin.update(
                                                            "insert into"
                                                                + " read_identity_request_result"
                                                                + " values"
                                                                + " (?,'REGISTRATION',?,?,'CHANGED',?,?,1,null)", id(406), fixture.issuer, "owner", fixture.issuer, "owner");
                            return true;
                        } catch (Exception failed) { throw new IllegalStateException("B30 fixture publication failed", failed); }
                    });
                    var command = List.of(repo.resolve("scripts/bruno-v3/node_modules/.bin/bru").toString(), "run", "-r", folder, "--env", "Local", "--sandbox", "developer", "--noproxy", "--reporter-json", output.resolve("bruno.json").toString(), "--reporter-skip-all-headers", "--reporter-skip-body");
                    var process = new ProcessBuilder(command).directory(repo.resolve("scripts/bruno-v3").toFile());
                    var environment = process.environment(); environment.put("POCOMA_I1_BASE_URL", fixture.baseUrl); environment.put("POCOMA_I1_PUBLISH_SIGNAL", signal.toString()); environment.put("POCOMA_I1_REQUEST_RECEIPTS", output.resolve("requests.jsonl").toString());
                    for (var item : Map.of("Owner", "owner", "Other", "other", "SameUser", "same", "Inactive", "inactive", "Absent", "absent").entrySet()) environment.put("POCOMA_I1_bearer" + item.getKey(), fixture.jwt(item.getValue()));
                    var now = Instant.now(); environment.put("POCOMA_I1_bearerForged", fixture.jwt("owner", fixture.issuer, "pocoma-i1", now.minusSeconds(1), now.plusSeconds(600), now.minusSeconds(2), new RSAKeyGenerator(2048).generate(), JWSAlgorithm.RS256));
                    environment.put("POCOMA_I1_bindingOwner", TOKEN); environment.put("POCOMA_I1_bindingOther", OTHER_TOKEN);
                    long cliStarted = System.nanoTime(); int code = process.redirectErrorStream(true).redirectOutput(output.resolve("bruno.log").toFile()).start().waitFor();
                    Path requestReceipts = output.resolve("requests.jsonl");
                    long scenarioCount = Files.isRegularFile(requestReceipts) ? Files.readAllLines(requestReceipts).size() : 0;
                    var actualScenarios = new ArrayList<String>();
                    if (Files.isRegularFile(requestReceipts)) for (String line : Files.readAllLines(requestReceipts)) actualScenarios.add(fixture.json.readTree(line).get("scenario").asText());
                    actualScenarios.sort(String::compareTo);
                    boolean coverage = actualScenarios.equals(expectedScenarios);
                    boolean publication;
                    if (code != 0 || !coverage) { publisher.cancel(true); publication = false; } else publication = publisher.get(125, TimeUnit.SECONDS);
                    var receipt = new LinkedHashMap<String,Object>();
                    receipt.put("provenance", "READ_FIXTURE — NO PUBLIC ADMISSION PROOF"); receipt.put("cliVersion", "4.3.0"); receipt.put("cliSeconds", (System.nanoTime()-cliStarted)/1e9); receipt.put("exitCode", code); receipt.put("fixturePublisherCompleted", publication); receipt.put("expectedScenarios",expectedScenarios); receipt.put("executedScenarioReceipts",scenarioCount); receipt.put("coverageComplete",coverage); receipt.put("command",command); receipt.put("fixtureSecondsBeforeCli",(cliStarted-started)/1e9);
                    Files.writeString(output.resolve("receipt.json"), fixture.json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
                    // Reporters exclude bodies/headers. Scan actual generated reports for process
                    // secrets.
                    for (Path artifact : Files.list(output).filter(Files::isRegularFile).toList()) {
                        String text = Files.readString(artifact);
                        for (String secret : environment.entrySet().stream().filter(e -> e.getKey().startsWith("POCOMA_I1_bearer") || e.getKey().startsWith("POCOMA_I1_binding")).map(Map.Entry::getValue).toList())
                            if (text.contains(secret)) throw new IllegalStateException("B1 export confidentiality violation");
                    }
                    if (code != 0 || !coverage) throw new IllegalStateException("Bruno B1 failed or incomplete; preserve reports");
                } finally { Files.deleteIfExists(signal); Files.deleteIfExists(signal.getParent()); }
            }
        }
    }
    @Override public void close() {
        if (context != null) context.close(); jwks.stop(0);
        // Schema and role live only in the disposable container. No reset of a shared READ.
    }
}
