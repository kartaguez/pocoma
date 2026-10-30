package com.kartaguez.pocoma.runtime.command.v3.dev;

import com.kartaguez.pocoma.contracts.registration.v3.IdentityRequest;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentity;
import com.kartaguez.pocoma.domain.useridentity.v3.BindingToken;
import com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotCommand;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.PotCommandJson;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.runtime.consumption.v3.*;

import org.flywaydb.core.Flyway;
import org.springframework.core.env.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;

/**
 * Administrative/internal DEV CLI only. No Spring context, Worker, HTTP endpoint or implicit reset.
 */
public final class V3WorkerDevelopmentTools {
    private V3WorkerDevelopmentTools() {}

    public static void main(String[] args) {
        try {
            if (args.length < 2)
                throw new IllegalArgumentException("operation and configuration required");
            var properties = new Properties();
            try (var in = Files.newInputStream(Path.of(args[1]))) {
                properties.load(in);
            }
            var env = new StandardEnvironment();
            env.getPropertySources()
                    .addFirst(new PropertiesPropertySource("explicit-dev-file", properties));
            env.setActiveProfiles(V3ConsumptionWorkerSettings.PROFILE);
            var settings = V3ConsumptionWorkerSettings.from(env);
            switch (args[0]) {
                case "prepare" -> prepare(settings);
                case "validate" -> validate(settings);
                case "inspect" -> inspect(settings);
                case "inject-identity" -> {
                    if (args.length != 3)
                        throw new IllegalArgumentException("protected intention file required");
                    injectIdentity(settings, Path.of(args[2]));
                }
                case "verify-identity" -> {
                    if (args.length != 4 && args.length != 5)
                        throw new IllegalArgumentException("request UUID/wait required");
                    verifyIdentity(
                            settings,
                            UUID.fromString(args[2]),
                            Integer.parseInt(args[3]),
                            args.length == 5 ? args[4] : null);
                }
                case "identity-token" -> {
                    if (args.length != 4)
                        throw new IllegalArgumentException(
                                "protected own-key/output files required");
                    identityToken(settings, Path.of(args[2]), Path.of(args[3]));
                }
                case "inject-command" -> {
                    if (args.length != 3)
                        throw new IllegalArgumentException("request UUID required");
                    inject(settings, UUID.fromString(args[2]));
                }
                case "verify" -> {
                    if (args.length != 4)
                        throw new IllegalArgumentException(
                                "request UUID and wait seconds required");
                    verify(settings, UUID.fromString(args[2]), Integer.parseInt(args[3]));
                }
                default -> throw new IllegalArgumentException("unknown DEV operation");
            }
        } catch (Exception failure) {
            // Never print SQL/connection parameters or causes that may contain credentials/tokens.
            System.err.println(
                    "I5_DEV_REFUSED operation="
                            + (args.length == 0 ? "missing" : args[0])
                            + " category="
                            + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("required tool environment missing");
        return value;
    }

    static String identifier(String value) {
        if (!value.matches("[a-z][a-z0-9_]*") || value.equals("public"))
            throw new IllegalArgumentException("invalid administrative identity");
        return value;
    }

    static DriverManagerDataSource admin(V3ConsumptionWorkerSettings.Pool target) {
        return new DriverManagerDataSource(
                target.url(),
                required("POCOMA_I5_ADMIN_USER"),
                required("POCOMA_I5_ADMIN_PASSWORD"));
    }

    static void prepare(V3ConsumptionWorkerSettings settings) throws Exception {
        String url = required("POCOMA_I5_ADMIN_URL"),
                user = identifier(required("POCOMA_I5_ADMIN_USER"));
        var adminEndpoint = java.net.URI.create(url.substring(5));
        for (var target : settings.allowedTargets())
            if (!target.host().equals(adminEndpoint.getHost())
                    || target.port() != adminEndpoint.getPort())
                throw new IllegalArgumentException(
                        "CLI preparation requires one explicitly authorized PostgreSQL server");
        String react = identifier(required("POCOMA_I5_REACTIVATION_ROLE")),
                injector = identifier(required("POCOMA_I5_INJECTOR_ROLE"));
        var roles = new LinkedHashMap<String, String>();
        roles.put(
                identifier(settings.writeBusiness().username()),
                settings.writeBusiness().password());
        roles.put(
                identifier(settings.readBusiness().username()), settings.readBusiness().password());
        roles.put(react, required("POCOMA_I5_REACTIVATION_PASSWORD"));
        roles.put(injector, required("POCOMA_I5_INJECTOR_PASSWORD"));
        if (roles.size() != 4 || roles.containsKey(user))
            throw new IllegalArgumentException("four distinct non-admin roles required");
        try (var c = DriverManager.getConnection(url, user, required("POCOMA_I5_ADMIN_PASSWORD"))) {
            try (var q = c.createStatement();
                    var row =
                            q.executeQuery(
                                    "select"
                                        + " current_user,inet_server_addr()::text,inet_server_port(),current_setting('standard_conforming_strings')")) {
                row.next();
                if (!user.equals(row.getString(1)) || !"on".equals(row.getString(4)))
                    throw new IllegalStateException("administrative target refused");
                for (var target : settings.allowedTargets())
                    if (!row.getString(2).equals(target.serverAddress())
                            || row.getInt(3) != target.serverPort())
                        throw new IllegalStateException("admin server outside allowlist");
            }
            // Refuse all preexisting identities before the first mutation. No DROP or IF NOT
            // EXISTS.
            for (String role : roles.keySet())
                try (var q =
                        c.prepareStatement(
                                "select exists(select 1 from pg_roles where rolname=?)")) {
                    q.setString(1, role);
                    try (var row = q.executeQuery()) {
                        row.next();
                        if (row.getBoolean(1))
                            throw new IllegalStateException("role already exists");
                    }
                }
            for (String db :
                    List.of(
                            settings.writeBusiness().database(),
                            settings.readBusiness().database()))
                try (var q =
                        c.prepareStatement(
                                "select exists(select 1 from pg_database where datname=?)")) {
                    q.setString(1, db);
                    try (var row = q.executeQuery()) {
                        row.next();
                        if (row.getBoolean(1))
                            throw new IllegalStateException("database already exists");
                    }
                }
            for (var role : roles.entrySet()) {
                String ddl;
                try (var q =
                        c.prepareStatement(
                                "select format('create role %I login password %L',?,?)")) {
                    q.setString(1, role.getKey());
                    q.setString(2, role.getValue());
                    try (var row = q.executeQuery()) {
                        row.next();
                        ddl = row.getString(1);
                    }
                }
                try (var q = c.createStatement()) {
                    q.execute(ddl);
                }
            }
            for (String db :
                    List.of(
                            settings.writeBusiness().database(),
                            settings.readBusiness().database())) {
                identifier(db);
                try (var q = c.createStatement()) {
                    q.execute("create database \"" + db + "\" owner \"" + user + "\"");
                }
            }
        }
        for (var target : List.of(settings.writeBusiness(), settings.readBusiness())) {
            var source = admin(target);
            new JdbcTemplate(source).execute("create extension pgcrypto with schema public");
            boolean write = target.business() && target.name().startsWith("write");
            Flyway.configure()
                    .dataSource(source)
                    .schemas(target.schema())
                    .defaultSchema(target.schema())
                    .locations(
                            write
                                    ? new String[] {
                                        "classpath:db/v3-pot/migration",
                                        "classpath:db/v3-pot-execution/migration",
                                        "classpath:db/v3-consumption-write/migration",
                                        settings.identityAuthority().equals("real")
                                                ? "classpath:db/v3-identity/migration"
                                                : "classpath:db/v3-consumption-write/migration"
                                    }
                                    : new String[] {"classpath:db/v3-consumption-read/migration"})
                    .placeholders(
                            Map.of(
                                    "expectedDatabase",
                                    target.database(),
                                    "expectedSchema",
                                    target.schema(),
                                    "expectedAdmin",
                                    user,
                                    "expectedRuntime",
                                    target.username()))
                    .load()
                    .migrate();
        }
        System.out.println(
                "I5_DEV_MIGRATIONS_READY administrative routing/grant scripts still required");
    }

    static void validate(V3ConsumptionWorkerSettings settings) {
        var w =
                new DriverManagerDataSource(
                        settings.writeControl().url(),
                        settings.writeControl().username(),
                        settings.writeControl().password());
        var r =
                new DriverManagerDataSource(
                        settings.readControl().url(),
                        settings.readControl().username(),
                        settings.readControl().password());
        settings.validateTarget(settings.writeControl(), w);
        settings.validateTarget(settings.readControl(), r);
        var guard =
                V3BootstrapGuard.development(
                        new V3BootstrapGuard.Target(
                                w,
                                settings.writeControl().database(),
                                settings.writeControl().schema(),
                                settings.writeControl().username()),
                        new V3BootstrapGuard.Target(
                                r,
                                settings.readControl().database(),
                                settings.readControl().schema(),
                                settings.readControl().username()),
                        settings.catalog(),
                        settings.technicalProbes(),
                        settings.fixtureIdentity());
        guard.validateLifecyclePrivileges(V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
        if (settings.identityRequest()) guard.validateIdentitySource(w);
        System.out.println(
                "I5_DEV_VALIDATED two authorities; no Worker threads or coordination mutation");
    }

    static void inspect(V3ConsumptionWorkerSettings settings) {
        for (var target : List.of(settings.writeControl(), settings.readControl())) {
            var sql = new JdbcTemplate(admin(target));
            System.out.println(
                    "I5_DEV_INSPECT "
                            + target.name()
                            + " "
                            + sql.queryForMap(
                                    "select"
                                        + " current_database(),current_schema(),clock_timestamp(),(select"
                                        + " count(*) from consumption_slot) slots,(select count(*)"
                                        + " from consumption_slot where state='QUARANTINED')"
                                        + " quarantined,(select count(*) from discovery_checkpoint)"
                                        + " checkpoints"));
        }
    }

    static void inject(V3ConsumptionWorkerSettings settings, UUID request) {
        if (!settings.fixtureIdentity())
            throw new IllegalStateException("Command fixture not enabled");
        String role = identifier(required("POCOMA_I5_INJECTOR_ROLE"));
        if (role.equals(settings.writeBusiness().username()))
            throw new IllegalStateException("separate injector required");
        var sql =
                new JdbcTemplate(
                        new DriverManagerDataSource(
                                settings.writeBusiness().url(),
                                role,
                                required("POCOMA_I5_INJECTOR_PASSWORD")));
        // Stable request -> stable Pot; a duplicate request is observed, never silently
        // reinterpreted.
        UUID pot =
                UUID.nameUUIDFromBytes(
                        ("i5-dev-pot/" + request)
                                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var payload =
                PotCommandJson.encode(new PotCommand.CreatePot(new Label("I5 development Pot")));
        var existing =
                sql.queryForList(
                        "select pot_id,operation,payload::text from v3_command where request_id=?",
                        request);
        if (!existing.isEmpty()) {
            if (sql.queryForObject(
                            "select count(*) from v3_command where request_id=? and pot_id=? and"
                                + " operation='CreatePot' and observed_version=0 and"
                                + " requester_issuer='i5-dev' and requester_subject='actor' and"
                                + " binding_token='i5-development-fixture' and payload=?::jsonb",
                            Integer.class,
                            request,
                            pot,
                            payload)
                    != 1)
                throw new IllegalStateException("request identity already has another intention");
            System.out.println("I5_DEV_REQUEST_ALREADY_ADMITTED internal SQL fixture");
            return;
        }
        sql.update(
                "insert into"
                    + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                    + " values"
                    + " (?,'i5-dev','actor','i5-development-fixture',?,0,'CreatePot',?::jsonb)",
                request,
                pot,
                payload);
        System.out.println(
                "I5_DEV_REQUEST_ADMITTED internal SQL fixture; no HTTP API; no slot in admission"
                        + " unit");
    }

    static DataSourceTransactionManager manager(javax.sql.DataSource source) {
        var manager = new DataSourceTransactionManager(source);
        manager.setNestedTransactionAllowed(false);
        return manager;
    }

    static Properties protectedProperties(Path path) throws Exception {
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path))
            throw new IllegalArgumentException("regular private file required");
        var permissions = Files.getPosixFilePermissions(path);
        if (permissions.stream()
                .anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
            throw new IllegalArgumentException("private file permissions required");
        var result = new Properties();
        try (var in = Files.newInputStream(path)) {
            result.load(in);
        }
        return result;
    }

    static void realIdentity(V3ConsumptionWorkerSettings settings) {
        if (!settings.identityRequest()
                || !settings.identityAuthority().equals("real")
                || settings.fixtureIdentity())
            throw new IllegalStateException("explicit real IdentityRequest required");
    }

    static IdentityRequest internalRequest(Path intention) throws Exception {
        var p = protectedProperties(intention);
        var requester =
                new ExternalIdentity(
                        p.getProperty("requester.issuer"), p.getProperty("requester.subject"));
        var target =
                new ExternalIdentity(
                        p.getProperty("target.issuer"), p.getProperty("target.subject"));
        var operation = IdentityRequestJson.operation(p.getProperty("operation"), target);
        UUID id = UUID.fromString(p.getProperty("request-id"));
        var time = Instant.parse(p.getProperty("created-at"));
        String token = p.getProperty("binding-token");
        // Canonical decoded intention; file layout/comments never change provenance.
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        for (String field :
                Arrays.asList(
                        id.toString(),
                        requester.issuer(),
                        requester.subject(),
                        p.getProperty("operation"),
                        target.issuer(),
                        target.subject(),
                        token,
                        time.toString())) {
            byte[] bytes =
                    field == null ? null : field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            digest.update(
                    java.nio.ByteBuffer.allocate(4)
                            .putInt(bytes == null ? -1 : bytes.length)
                            .array());
            if (bytes != null) digest.update(bytes);
        }
        String fingerprint = HexFormat.of().formatHex(digest.digest());
        var request =
                new IdentityRequest(
                        id,
                        requester,
                        operation,
                        token == null ? null : new BindingToken(token),
                        time,
                        new IdentityRequest.AdmissionContext(
                                "pocoma-controlled-dev",
                                "L3/I3",
                                requester,
                                target,
                                fingerprint,
                                id.toString(),
                                time,
                                Map.of("scope", "internal-test-only"),
                                IdentityRequest.Origin.CONTROLLED_INTERNAL));
        return request;
    }

    static void injectIdentity(V3ConsumptionWorkerSettings settings, Path intention)
            throws Exception {
        realIdentity(settings);
        var request = internalRequest(intention);
        UUID id = request.requestId();
        String role = identifier(required("POCOMA_I5_INJECTOR_ROLE"));
        if (role.equals(settings.writeBusiness().username()))
            throw new IllegalArgumentException("distinct injector required");
        var source =
                new DriverManagerDataSource(
                        settings.writeBusiness().url(),
                        role,
                        required("POCOMA_I5_INJECTOR_PASSWORD"));
        var tx = new SpringAttemptTransactions(source, manager(source), 5);
        var store = new JdbcIdentityRequestStore(source, tx);
        var stored = tx.write(() -> store.insertPending(request));
        System.out.println(
                "L3_DEV_IDENTITY_ADMITTED request="
                        + id
                        + " status="
                        + stored.status()
                        + " origin=CONTROLLED_INTERNAL");
    }

    static void identityToken(V3ConsumptionWorkerSettings settings, Path ownKey, Path destination)
            throws Exception {
        realIdentity(settings);
        var p = protectedProperties(ownKey);
        // Confidential operator helper only, using admin credentials; never a client API/result.
        var sql = new JdbcTemplate(admin(settings.writeBusiness()));
        String token =
                sql.queryForObject(
                        "select v.binding_token from v3_eui_authority a join v3_eui_version v on"
                            + " v.eui_id=a.eui_id and v.version=a.current_version where a.issuer=?"
                            + " and a.subject=? and v.binding_token is not null",
                        String.class,
                        p.getProperty("issuer"),
                        p.getProperty("subject"));
        Files.createFile(
                destination,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        Files.writeString(destination, token, StandardOpenOption.WRITE);
        System.out.println("L3_DEV_CONFIDENTIAL_OWN_TOKEN_WRITTEN");
    }

    static void verifyIdentity(
            V3ConsumptionWorkerSettings settings, UUID id, int seconds, String expected)
            throws Exception {
        realIdentity(settings);
        if (seconds < 1 || seconds > 300)
            throw new IllegalArgumentException("bounded observation required");
        var sql = new JdbcTemplate(admin(settings.writeControl()));
        long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < end) {
            var rows =
                    sql.queryForList(
                            "select outcome,target_eui_id,target_version,rejection from"
                                    + " v3_identity_request_result where request_id=?",
                            id);
            if (rows.size() == 1) {
                if (expected != null && !expected.equals(rows.getFirst().get("outcome")))
                    throw new IllegalStateException("Unexpected Identity outcome");
                var source = admin(settings.writeControl());
                var tx = new SpringAttemptTransactions(source, manager(source), 5);
                var observation = new JdbcIdentityRequestObservation(source);
                if (tx.read(ctx -> observation.observe(id, ctx))
                                != com.kartaguez.pocoma.engine.consumption.v3.RecoveryObservation
                                        .COMPLETED
                        || sql.queryForObject(
                                        "select count(*) from consumption_slot where"
                                            + " consumption_id='2999e75c-272d-5afe-a966-c9364ef021fe'"
                                            + " and consumable_id=?",
                                        Integer.class,
                                        id)
                                != 0)
                    throw new IllegalStateException("Identity terminal evidence inconsistent");
                System.out.println(
                        "L3_DEV_IDENTITY_VERIFIED request=" + id + " result=" + rows.getFirst());
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Identity observation incomplete");
    }

    static void verify(V3ConsumptionWorkerSettings settings, UUID request, int seconds)
            throws Exception {
        if (seconds < 1 || seconds > 300)
            throw new IllegalArgumentException("bounded wait required");
        var write = new JdbcTemplate(admin(settings.writeControl()));
        var read = new JdbcTemplate(admin(settings.readControl()));
        long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < end) {
            var status =
                    write.queryForList(
                            "select status from v3_command where request_id=?",
                            String.class,
                            request);
            if (status.size() == 1 && status.getFirst().equals("TERMINAL")) {
                var events =
                        write.queryForList(
                                "select event_id from v3_pot_event where request_id=? union all"
                                        + " select event_id from v3_command_completion_event where"
                                        + " subject_id=?",
                                UUID.class,
                                request,
                                request);
                if (events.size() != 2)
                    throw new IllegalStateException("expected real CreatePot publications missing");
                if (write.queryForObject(
                                        "select count(*) from v3_command_result where request_id=?"
                                            + " and outcome='CHANGED' and resulting_pot_version=1",
                                        Integer.class,
                                        request)
                                != 1
                        || write.queryForObject(
                                        "select count(*) from consumption_slot where"
                                            + " consumable_id=? and"
                                            + " consumption_id='98f7268d-d340-581c-b83d-010926223e14'",
                                        Integer.class,
                                        request)
                                != 0)
                    throw new IllegalStateException("inconsistent Command completion");
                if (write.queryForObject(
                                        "select count(*) from v3_pot_version v join v3_command c"
                                            + " using(pot_id) where c.request_id=? and v.version=1",
                                        Integer.class,
                                        request)
                                != 1
                        || write.queryForObject(
                                        "select count(*) from v3_event_position where event_id in"
                                                + " (?,?)",
                                        Integer.class,
                                        events.get(0),
                                        events.get(1))
                                != 2)
                    throw new IllegalStateException("real version or dense publications missing");
                if (!settings.technicalProbes()
                        || read.queryForObject(
                                                "select count(*) from i5_read_probe_effect where"
                                                        + " consumable_id in (?,?) and"
                                                        + " value='technical'",
                                                Integer.class,
                                                events.get(0),
                                                events.get(1))
                                        == 2
                                && read.queryForObject(
                                                "select count(*) from consumption_slot where"
                                                        + " consumable_id in (?,?)",
                                                Integer.class,
                                                events.get(0),
                                                events.get(1))
                                        == 0) {
                    System.out.println(
                            "I5_DEV_VERIFIED real CreatePot V1, terminal result, canonical slot"
                                    + " absent, dense publications; READ probes="
                                    + settings.technicalProbes()
                                    + " (technical only)");
                    return;
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("bounded observation incomplete; not a business PASS");
    }
}
