package com.kartaguez.pocoma;
import static com.kartaguez.pocoma.domain.projection.SubobjectKind.EXPENSE_DETAILS;
import static com.kartaguez.pocoma.domain.projection.SubobjectKind.EXPENSE_FINANCIALS;
import static com.kartaguez.pocoma.domain.projection.SubobjectKind.EXPENSE_HEADER;
import static com.kartaguez.pocoma.domain.projection.SubobjectKind.POT_DETAILS;
import static com.kartaguez.pocoma.domain.projection.SubobjectKind.POT_HEADER;
import static com.kartaguez.pocoma.domain.projection.SubobjectKind.SHAREHOLDER_DETAILS;
import static com.kartaguez.pocoma.domain.projection.SubobjectKind.SHAREHOLDER_HEADER;
import static com.kartaguez.pocoma.domain.projection.SubobjectKind.SHAREHOLDER_WEIGHT;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.compose.web.read.ReadFacadeComposition;
import com.kartaguez.pocoma.compose.web.read.ReadHttpConfiguration;
import com.kartaguez.pocoma.domain.identity.BindingToken;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.ExternalUserIdentity;
import com.kartaguez.pocoma.domain.identity.ExternalUserIdentityId;
import com.kartaguez.pocoma.domain.identity.PocomaUserId;
import com.kartaguez.pocoma.domain.pot.ExpenseDetails;
import com.kartaguez.pocoma.domain.pot.ExpenseFinancials;
import com.kartaguez.pocoma.domain.pot.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.Fraction;
import com.kartaguez.pocoma.domain.pot.PotDetails;
import com.kartaguez.pocoma.domain.pot.PotHeader;
import com.kartaguez.pocoma.domain.pot.ShareholderDetails;
import com.kartaguez.pocoma.domain.pot.ShareholderHeader;
import com.kartaguez.pocoma.domain.pot.ShareholderWeight;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.PotBalance;
import com.kartaguez.pocoma.domain.projection.PotVersion;
import com.kartaguez.pocoma.domain.projection.SubobjectContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ExpenseDetailsContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ExpenseFinancialsContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ExpenseHeaderContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.PotDetailsContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.PotHeaderContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ShareholderDetailsContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ShareholderHeaderContent;
import com.kartaguez.pocoma.domain.projection.SubobjectContent.ShareholderWeightContent;
import com.kartaguez.pocoma.domain.projection.SubobjectKey;
import com.kartaguez.pocoma.domain.projection.SubobjectKind;
import com.kartaguez.pocoma.domain.projection.SubobjectVersion;
import com.kartaguez.pocoma.infra.postgres.read.JdbcCurrentBindingStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotBalanceStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcPotVersionStore;
import com.kartaguez.pocoma.infra.postgres.read.JdbcSubobjectVersionStore;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;

/** Test-only injector, signing fixture and independent SQL/HTTP observer. No public admission. */
final class ReadHttpFixture implements AutoCloseable {
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
    ReadHttpFixture(PostgreSQLContainer pg) throws Exception {
        this.pg = pg;
        var control = new JdbcTemplate(new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()));
        if (!control.queryForObject("select exists(select 1 from pg_database where datname='i1_read')", Boolean.class)) control.execute("create database i1_read");
        url = "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/i1_read?currentSchema=" + schema + ",public";
        var root = new JdbcTemplate(new DriverManagerDataSource(url, pg.getUsername(), pg.getPassword()));
        root.execute("create extension if not exists pgcrypto with schema public");
        root.execute("create extension if not exists pg_stat_statements with schema public");
        root.execute("create role " + role + " login password 'ephemeral-i1-fixture'");
        Flyway.configure().dataSource(url, pg.getUsername(), pg.getPassword()).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/read/migration")
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
                        "classpath:db/write/migration")
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

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.context.annotation.Import({com.kartaguez.pocoma.compose.web.write.AdmissionHttpConfiguration.class,
        com.kartaguez.pocoma.compose.web.read.ReadHttpConfiguration.class})
    static class DualHttpComposeRoot {}

    private void warmUpPhysicalPool() {
        var before = sqlStats();
        verifyPoolConnections();
        if (pool().getHikariPoolMXBean().getTotalConnections() != 2
                || pool().getHikariPoolMXBean().getActiveConnections() != 0) {
            throw new IllegalStateException("READ fixture pool not initialized");
        }
        recordPoolEvidence(before);
    }

    private void verifyPoolConnections() {
        try (var one = pool().getConnection(); var two = pool().getConnection()) {
            for (var connection : List.of(one, two)) {
                verifySelectOne(connection);
            }
        } catch (java.sql.SQLException failed) {
            throw new IllegalStateException("READ fixture pool initialization failed", failed);
        }
    }

    private static void verifySelectOne(java.sql.Connection connection) throws java.sql.SQLException {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery("select 1")) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new IllegalStateException("READ fixture initialization failed");
            }
        }
    }

    private void recordPoolEvidence(List<Map<String, Object>> before) {
        try {
            evidence(
                    "HTTP-pool-initialization-" + schema,
                    Map.of("before", before, "after", sqlStats(), "physicalConnectionsInitialized", 2));
        } catch (IOException failed) {
            throw new IllegalStateException("READ fixture initialization evidence failed", failed);
        }
    }

    void start(Class<?>... roots) {
        boolean dual = properties.containsKey("pocoma.v3.http.admission.url");
        if (dual) {
            roots =
                    Arrays.stream(roots)
                            .map(r -> r == PocomaWebReadApplication.class ? DualHttpComposeRoot.class : r)
                            .toArray(Class<?>[]::new);
        }
        var app = new SpringApplication(roots);
        app.setAdditionalProfiles(
                dual
                        ? new String[] {ReadHttpConfiguration.PROFILE, "v3-http-admission"}
                        : new String[] {ReadHttpConfiguration.PROFILE});
        app.setDefaultProperties(properties);
        app.addInitializers(
                ctx ->
                        ctx.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new org.springframework.core.env.MapPropertySource(
                                                "i1-isolated-fixture", properties)));
        context = app.run();
        baseUrl = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
        // Both physical connections must finish lazy JDBC initialization before zero-SQL oracles.
        warmUpPhysicalPool();
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
    private record SeedContext(UUID pot, long version, Fraction rational, boolean expenseDetails, boolean expenseRationals) {}

    private record SeedAccumulator(
            List<PotVersion.Shareholder> shares,
            List<PotVersion.Expense> refs,
            Map<ShareholderId, Optional<com.kartaguez.pocoma.domain.pot.Weight>> parts,
            List<SubobjectVersion> values,
            Map<UUID, Fraction> vector) {
        SeedAccumulator() {
            this(new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>(), new ArrayList<>(), new LinkedHashMap<>());
        }
    }

    void seedProfile(UUID pot, long version, int expenseCount, int shareholders, Set<UUID> allowed, boolean deleted, boolean withBalance, boolean expenseDetails, String label, int digits, boolean expenseRationals) {
        var ctx = new SeedContext(pot, version, rationalForDigits(digits), expenseDetails, expenseRationals);
        var acc = new SeedAccumulator();
        addSubobject(acc.values(), new SubobjectKey(pot, POT_HEADER, pot, version), new PotHeaderContent(new PotHeader(new PotId(pot), deleted)));
        addSubobject(acc.values(), new SubobjectKey(pot, POT_DETAILS, pot, version), new PotDetailsContent(new PotDetails(new PotId(pot), new Label(label), new UserId(OWNER))));
        populateShareholders(ctx, shareholders, acc);
        populateExpenses(ctx, expenseCount, acc);
        injector.write(() -> {
            objects.insertOrVerifyAll(acc.values());
            pots.insertAndAdvance(new PotVersion(pot, version, version, version, deleted, acc.shares(), acc.refs(), allowed, Set.of()));
            if (withBalance && !deleted) {
                balances.insertOrVerify(new PotBalance(pot, version, acc.vector()));
            }
            return null;
        });
    }

    private static Fraction rationalForDigits(int digits) {
        if (digits == 0) {
            return new Fraction(2, 3);
        }
        var base = java.math.BigInteger.TEN.pow(digits - 1);
        return new Fraction(base.add(java.math.BigInteger.ONE), base.add(java.math.BigInteger.valueOf(3)));
    }

    private static void populateShareholders(SeedContext ctx, int shareholders, SeedAccumulator acc) {
        var weight = new com.kartaguez.pocoma.domain.pot.Weight(ctx.rational());
        for (int n = 0; n < shareholders; n++) {
            UUID s = id(201 + n);
            acc.shares().add(new PotVersion.Shareholder(s, ctx.version(), ctx.version(), ctx.version(), false, true));
            addSubobject(acc.values(), new SubobjectKey(ctx.pot(), SHAREHOLDER_HEADER, s, ctx.version()), new ShareholderHeaderContent(new ShareholderHeader(new PotId(ctx.pot()), new ShareholderId(s), false)));
            addSubobject(acc.values(), new SubobjectKey(ctx.pot(), SHAREHOLDER_DETAILS, s, ctx.version()), new ShareholderDetailsContent(new ShareholderDetails(new PotId(ctx.pot()), new ShareholderId(s), new Name("share-" + n), Optional.empty())));
            addSubobject(acc.values(), new SubobjectKey(ctx.pot(), SHAREHOLDER_WEIGHT, s, ctx.version()), new ShareholderWeightContent(new ShareholderWeight(new PotId(ctx.pot()), new ShareholderId(s), weight)));
            acc.parts().put(new ShareholderId(s), ctx.expenseRationals() ? Optional.of(weight) : Optional.empty());
            acc.vector().put(s, initialBalanceFraction(n));
        }
    }

    private static Fraction initialBalanceFraction(int index) {
        if (index == 0) {
            return new Fraction(1, 3);
        }
        return index == 1 ? new Fraction(-1, 3) : Fraction.ZERO;
    }

    private static void populateExpenses(SeedContext ctx, int expenseCount, SeedAccumulator acc) {
        var amount = new com.kartaguez.pocoma.domain.pot.Amount(ctx.expenseRationals() ? ctx.rational() : new Fraction(1, 3));
        for (int n = 0; n < expenseCount; n++) {
            UUID e = id(1000 + n);
            acc.refs().add(new PotVersion.Expense(e, ctx.version(), ctx.version(), ctx.version(), false));
            addSubobject(acc.values(), new SubobjectKey(ctx.pot(), EXPENSE_HEADER, e, ctx.version()), new ExpenseHeaderContent(new ExpenseHeader(new PotId(ctx.pot()), new ExpenseId(e), false)));
            if (ctx.expenseDetails()) {
                addSubobject(acc.values(), new SubobjectKey(ctx.pot(), EXPENSE_DETAILS, e, ctx.version()), new ExpenseDetailsContent(new ExpenseDetails(new PotId(ctx.pot()), new ExpenseId(e), new Label("dépense\\\"-" + n), LocalDate.of(2026, 11, 3))));
            }
            addSubobject(acc.values(), new SubobjectKey(ctx.pot(), EXPENSE_FINANCIALS, e, ctx.version()), new ExpenseFinancialsContent(new ExpenseFinancials(new PotId(ctx.pot()), new ExpenseId(e), amount, new ShareholderId(S1), acc.parts())));
        }
    }

    void seed(UUID pot, long version, int count, Set<UUID> allowed, boolean deleted, boolean balance, boolean details, String label) { seed(pot, version, count, 2, allowed, deleted, balance, details, label); }
    static void addSubobject(List<SubobjectVersion> values, SubobjectKey key, SubobjectContent content) { values.add(new SubobjectVersion(key, content)); }
    void results() {
        insertResultPair(false, null, 2L, 1L);
        insertResultPair(true, "INVALID_BINDING", null, null);
    }

    private void insertResultPair(boolean rejected, String reason, Long potSeq, Long idSeq) {
        String outcome = rejected ? "REJECTED" : "CHANGED";
        admin.update(
                "insert into read_command_result values (?,'CreatePot',?,?,?, ?,?,?)",
                id(rejected ? 402 : 401), issuer, "owner", POT, outcome, potSeq, reason);
        admin.update(
                "insert into read_identity_request_result values (?,'REGISTRATION',?,?,?, ?,?,?,?)",
                id(rejected ? 404 : 403), issuer, "owner", outcome,
                rejected ? null : issuer, rejected ? null : "owner", idSeq, reason);
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
    HikariDataSource pool() { return context.getBean("httpReadPool", HikariDataSource.class); }
    ReadFacadeComposition.Services services() { return context.getBean(ReadFacadeComposition.Services.class); }
    long activeBackends() { return admin.queryForObject("select count(*) from pg_stat_activity where usename=? and state<>'idle'", Long.class, role); }
    List<Map<String, Object>> sqlStats() { return admin.queryForList(
                "select query,calls,rows,total_exec_time from public.pg_stat_statements where"
                        + " userid=(select oid from pg_roles where rolname=?) order by query", role); }
    void evidence(String name, Object observation) throws IOException {
        String destination = System.getenv("POCOMA_I1_PHYSICAL"); if (destination == null) return;
        Path dir = Path.of(destination); Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".json"), json.writerWithDefaultPrettyPrinter().writeValueAsString(observation) + "\n");
    }
    private static Future<Boolean> startPublisher(
            ExecutorService executor, ReadHttpFixture fixture, Path signal, boolean expensesOnly) {
        if (expensesOnly) {
            return CompletableFuture.completedFuture(true);
        }
        return executor.submit(
                () -> {
                    try {
                        long end = System.nanoTime() + Duration.ofSeconds(120).toNanos();
                        while (!Files.exists(signal) && System.nanoTime() < end) {
                            Thread.sleep(10);
                        }
                        if (!Files.exists(signal)) {
                            throw new IllegalStateException("B30 publication handshake not observed");
                        }
                        fixture.admin.update(
                                "insert into read_command_result values (?,'CreatePot',?,?,?,'CHANGED',2,null)",
                                id(405), fixture.issuer, "owner", POT);
                        fixture.admin.update(
                                "insert into read_identity_request_result values (?,'REGISTRATION',?,?,'CHANGED',?,?,1,null)",
                                id(406), fixture.issuer, "owner", fixture.issuer, "owner");
                        return true;
                    } catch (Exception failed) {
                        throw new IllegalStateException("B30 fixture publication failed", failed);
                    }
                });
    }

    private static void populateBrunoEnvironment(
            Map<String, String> environment, ReadHttpFixture fixture, Path signal, Path output)
            throws Exception {
        environment.put("POCOMA_I1_BASE_URL", fixture.baseUrl);
        environment.put("POCOMA_I1_PUBLISH_SIGNAL", signal.toString());
        environment.put("POCOMA_I1_REQUEST_RECEIPTS", output.resolve("requests.jsonl").toString());
        for (var item : Map.of("Owner", "owner", "Other", "other", "SameUser", "same", "Inactive", "inactive", "Absent", "absent").entrySet()) {
            environment.put("POCOMA_I1_bearer" + item.getKey(), fixture.jwt(item.getValue()));
        }
        var now = Instant.now();
        environment.put(
                "POCOMA_I1_bearerForged",
                fixture.jwt(
                        "owner", fixture.issuer, "pocoma-i1", now.minusSeconds(1),
                        now.plusSeconds(600), now.minusSeconds(2),
                        new RSAKeyGenerator(2048).generate(), JWSAlgorithm.RS256));
        environment.put("POCOMA_I1_bindingOwner", TOKEN);
        environment.put("POCOMA_I1_bindingOther", OTHER_TOKEN);
    }

    private static void verifyExportConfidentiality(Path output, Map<String, String> environment)
            throws IOException {
        List<String> secrets =
                environment.entrySet().stream()
                        .filter(
                                e ->
                                        e.getKey().startsWith("POCOMA_I1_bearer")
                                                || e.getKey().startsWith("POCOMA_I1_binding"))
                        .map(Map.Entry::getValue)
                        .toList();
        for (Path artifact : Files.list(output).filter(Files::isRegularFile).toList()) {
            String text = Files.readString(artifact);
            if (secrets.stream().anyMatch(text::contains)) {
                throw new IllegalStateException("B1 export confidentiality violation");
            }
        }
    }

    private static List<String> readExecutedScenarios(Path requestReceipts, ObjectMapper json)
            throws IOException {
        var actualScenarios = new ArrayList<String>();
        if (!Files.isRegularFile(requestReceipts)) {
            return actualScenarios;
        }
        for (String line : Files.readAllLines(requestReceipts)) {
            actualScenarios.add(json.readTree(line).get("scenario").asText());
        }
        actualScenarios.sort(String::compareTo);
        return actualScenarios;
    }

    /** Separate B1 lifecycle, launched after T1 by the shared campaign owner. */
    public static void main(String[] args) throws Exception {
        Path repo = Path.of(args[0]).toAbsolutePath(), output = Path.of(args[1]).toAbsolutePath();
        Files.createDirectories(output);
        long started = System.nanoTime();
        try (var pg = container()) {
            pg.start();
            try (var fixture = new ReadHttpFixture(pg)) {
                fixture.corpus();
                fixture.start(PocomaWebReadApplication.class);
                runBrunoFixture(repo, output, fixture, started);
            }
        }
    }

    private static void runBrunoFixture(Path repo, Path output, ReadHttpFixture fixture, long started) throws Exception {
        Path signal = Files.createTempDirectory("pocoma-i1-publisher-").resolve("publish");
        boolean expensesOnly = "true".equals(System.getenv("POCOMA_I1_BRUNO_EXPENSES_ONLY"));
        String folder = expensesOnly ? "01 Identity and READ/03 Expense Batches" : "01 Identity and READ";
        var expectedScenarios = java.util.stream.IntStream.rangeClosed(expensesOnly ? 15 : 1, expensesOnly ? 20 : 32)
                .mapToObj("B%02d"::formatted)
                .toList();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Boolean> publisher = startPublisher(executor, fixture, signal, expensesOnly);
            var command = brunoCommand(repo, folder, output);
            var process = new ProcessBuilder(command).directory(repo.resolve("scripts/bruno-v3").toFile());
            populateBrunoEnvironment(process.environment(), fixture, signal, output);
            long cliStarted = System.nanoTime();
            int code = process.redirectErrorStream(true).redirectOutput(output.resolve("bruno.log").toFile()).start().waitFor();
            Path requestReceipts = output.resolve("requests.jsonl");
            boolean coverage = readExecutedScenarios(requestReceipts, fixture.json).equals(expectedScenarios);
            boolean publication = awaitPublisher(publisher, code, coverage);
            writeFixtureReceipt(new ReceiptData(output, fixture, started, cliStarted, code, publication, coverage, expectedScenarios, requestReceipts, command));
            verifyExportConfidentiality(output, process.environment());
            if (code != 0 || !coverage) {
                throw new IllegalStateException("Bruno B1 failed or incomplete; preserve reports");
            }
        } finally {
            Files.deleteIfExists(signal);
            Files.deleteIfExists(signal.getParent());
        }
    }

    private static List<String> brunoCommand(Path repo, String folder, Path output) {
        return List.of(
                repo.resolve("scripts/bruno-v3/node_modules/.bin/bru").toString(),
                "run", "-r", folder, "--env", "Local", "--sandbox", "developer",
                "--noproxy", "--reporter-json", output.resolve("bruno.json").toString(),
                "--reporter-skip-all-headers", "--reporter-skip-body");
    }

    private static boolean awaitPublisher(Future<Boolean> publisher, int code, boolean coverage) throws Exception {
        if (code != 0 || !coverage) {
            publisher.cancel(true);
            return false;
        }
        return publisher.get(125, TimeUnit.SECONDS);
    }

    private record ReceiptData(
            Path output,
            ReadHttpFixture fixture,
            long started,
            long cliStarted,
            int code,
            boolean publication,
            boolean coverage,
            List<String> expectedScenarios,
            Path requestReceipts,
            List<String> command) {}

    private static void writeFixtureReceipt(ReceiptData data) throws IOException {
        long scenarioCount = Files.isRegularFile(data.requestReceipts()) ? Files.readAllLines(data.requestReceipts()).size() : 0;
        var receipt = new LinkedHashMap<String, Object>();
        receipt.put("provenance", "READ_FIXTURE — NO PUBLIC ADMISSION PROOF");
        receipt.put("cliVersion", "4.3.0");
        receipt.put("cliSeconds", (System.nanoTime() - data.cliStarted()) / 1e9);
        receipt.put("exitCode", data.code());
        receipt.put("fixturePublisherCompleted", data.publication());
        receipt.put("expectedScenarios", data.expectedScenarios());
        receipt.put("executedScenarioReceipts", scenarioCount);
        receipt.put("coverageComplete", data.coverage());
        receipt.put("command", data.command());
        receipt.put("fixtureSecondsBeforeCli", (data.cliStarted() - data.started()) / 1e9);
        Files.writeString(data.output().resolve("receipt.json"), data.fixture().json.writerWithDefaultPrettyPrinter().writeValueAsString(receipt));
    }
    @Override public void close() {
        if (context != null) context.close(); jwks.stop(0);
        // Schema and role live only in the disposable container. No reset of a shared READ.
    }
}
