package com.kartaguez.pocoma.runtime.command.dev;
import com.kartaguez.pocoma.infra.postgres.core.BootstrapGuard;

import com.kartaguez.pocoma.contract.writeadmission.IdentityRequest;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.identity.ExternalIdentity;
import com.kartaguez.pocoma.domain.identity.BindingToken;
import com.kartaguez.pocoma.contract.writeadmission.PotCommand;
import com.kartaguez.pocoma.infra.postgres.write.PotCommandJson;
import com.kartaguez.pocoma.infra.postgres.write.identity.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.architecture.qualification.QualificationSettings;

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
public final class WorkerDevelopmentTools {
    private WorkerDevelopmentTools() {}

    public static void main(String[] args) {
        try {
            if (args.length < 2)
                throw new IllegalArgumentException("operation and configuration required");
            dispatchOperation(args, loadSettings(Path.of(args[1])));
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

    private static QualificationSettings loadSettings(Path configPath) throws Exception {
        var properties = new Properties();
        try (var in = Files.newInputStream(configPath)) {
            properties.load(in);
        }
        var env = new StandardEnvironment();
        env.getPropertySources()
                .addFirst(new PropertiesPropertySource("explicit-dev-file", properties));
        env.setActiveProfiles(QualificationSettings.PROFILE);
        return QualificationSettings.from(env);
    }

    @FunctionalInterface
    private interface DevCommand {
        void run(String[] args, QualificationSettings settings) throws Exception;
    }

    private static final Map<String, DevCommand> COMMANDS =
            Map.of(
                    "prepare", (args, settings) -> prepare(settings),
                    "validate", (args, settings) -> validate(settings),
                    "inspect", (args, settings) -> inspect(settings),
                    "inject-identity",
                            (args, settings) -> {
                                requireArgCount(args, 3, "protected intention file required");
                                injectIdentity(settings, Path.of(args[2]));
                            },
                    "verify-identity", WorkerDevelopmentTools::runVerifyIdentity,
                    "identity-token",
                            (args, settings) -> {
                                requireArgCount(args, 4, "protected own-key/output files required");
                                identityToken(settings, Path.of(args[2]), Path.of(args[3]));
                            },
                    "inject-command",
                            (args, settings) -> {
                                requireArgCount(args, 3, "request UUID required");
                                inject(settings, UUID.fromString(args[2]));
                            },
                    "verify",
                            (args, settings) -> {
                                requireArgCount(args, 4, "request UUID and wait seconds required");
                                verify(settings, UUID.fromString(args[2]), Integer.parseInt(args[3]));
                            });

    private static void runVerifyIdentity(String[] args, QualificationSettings settings)
            throws Exception {
        if (args.length != 4 && args.length != 5)
            throw new IllegalArgumentException("request UUID/wait required");
        verifyIdentity(
                settings,
                UUID.fromString(args[2]),
                Integer.parseInt(args[3]),
                args.length == 5 ? args[4] : null);
    }

    private static void dispatchOperation(String[] args, QualificationSettings settings)
            throws Exception {
        DevCommand command = COMMANDS.get(args[0]);
        if (command == null) throw new IllegalArgumentException("unknown DEV operation");
        command.run(args, settings);
    }

    private static void requireArgCount(String[] args, int expected, String message) {
        if (args.length != expected) throw new IllegalArgumentException(message);
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

    static DriverManagerDataSource admin(QualificationSettings.Pool target) {
        return new DriverManagerDataSource(
                target.url(),
                required("POCOMA_I5_ADMIN_USER"),
                required("POCOMA_I5_ADMIN_PASSWORD"));
    }

    static void prepare(QualificationSettings settings) throws Exception {
        String url = required("POCOMA_I5_ADMIN_URL"),
                user = identifier(required("POCOMA_I5_ADMIN_USER"));
        verifyAdminTargets(settings, url);
        var roles = collectPrepareRoles(settings, user);
        try (var c = DriverManager.getConnection(url, user, required("POCOMA_I5_ADMIN_PASSWORD"))) {
            verifyAdminConnection(c, settings, user);
            // Refuse all preexisting identities before the first mutation. No DROP or IF NOT
            // EXISTS.
            ensureAbsent(c, "select exists(select 1 from pg_roles where rolname=?)", roles.keySet(), "role already exists");
            var dbs = List.of(settings.writeBusiness().database(), settings.readBusiness().database());
            ensureAbsent(c, "select exists(select 1 from pg_database where datname=?)", dbs, "database already exists");
            createRoles(c, roles);
            createDatabases(c, dbs, user);
        }
        runFlywayMigrations(settings, user);
        System.out.println(
                "I5_DEV_MIGRATIONS_READY administrative routing/grant scripts still required");
    }

    private static void verifyAdminTargets(QualificationSettings settings, String url) {
        var adminEndpoint = java.net.URI.create(url.substring(5));
        for (var target : settings.allowedTargets())
            if (!target.host().equals(adminEndpoint.getHost())
                    || target.port() != adminEndpoint.getPort())
                throw new IllegalArgumentException(
                        "CLI preparation requires one explicitly authorized PostgreSQL server");
    }

    private static Map<String, String> collectPrepareRoles(
            QualificationSettings settings, String user) {
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
        return roles;
    }

    private static void verifyAdminConnection(
            Connection c, QualificationSettings settings, String user) throws SQLException {
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
    }

    private static void ensureAbsent(
            Connection c, String sql, Collection<String> names, String errorMessage)
            throws SQLException {
        for (String name : names) {
            try (var q = c.prepareStatement(sql)) {
                q.setString(1, name);
                try (var row = q.executeQuery()) {
                    row.next();
                    if (row.getBoolean(1)) throw new IllegalStateException(errorMessage);
                }
            }
        }
    }

    private static void createRoles(Connection c, Map<String, String> roles) throws SQLException {
        for (var role : roles.entrySet()) {
            String ddl;
            try (var q =
                    c.prepareStatement("select format('create role %I login password %L',?,?)")) {
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
    }

    private static void createDatabases(Connection c, List<String> dbs, String user)
            throws SQLException {
        for (String db : dbs) {
            identifier(db);
            try (var q = c.createStatement()) {
                q.execute("create database \"" + db + "\" owner \"" + user + "\"");
            }
        }
    }

    private static void runFlywayMigrations(QualificationSettings settings, String user) {
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
                                    ? new String[] {"classpath:db/write/migration"}
                                    : new String[] {"classpath:db/read/migration"})
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
    }

    static void validate(QualificationSettings settings) {
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
                BootstrapGuard.development(
                        new BootstrapGuard.Target(
                                w,
                                settings.writeControl().database(),
                                settings.writeControl().schema(),
                                settings.writeControl().username()),
                        new BootstrapGuard.Target(
                                r,
                                settings.readControl().database(),
                                settings.readControl().schema(),
                                settings.readControl().username()),
                        settings.catalog(),
                        settings.technicalProbes(),
                        settings.fixtureIdentity());
        guard.validateLifecyclePrivileges(BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
        if (settings.identityRequest()) guard.validateIdentitySource(w);
        System.out.println(
                "I5_DEV_VALIDATED two authorities; no Worker threads or coordination mutation");
    }

    static void inspect(QualificationSettings settings) {
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

    static void inject(QualificationSettings settings, UUID request) {
        if (!settings.fixtureIdentity())
            throw new IllegalStateException("Command fixture not enabled");
        String role = identifier(required("POCOMA_I5_INJECTOR_ROLE"));
        if (role.equals(settings.writeBusiness().username()))
            throw new IllegalStateException("separate injector required");
        var injectorSource =
                new DriverManagerDataSource(
                        settings.writeBusiness().url(),
                        role,
                        required("POCOMA_I5_INJECTOR_PASSWORD"));
        var sql = new JdbcTemplate(injectorSource);
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
        new SpringAttemptTransactions(injectorSource, manager(injectorSource), 5)
                .write(
                        () -> {
                            sql.update(
                                    "insert into"
                                        + " v3_command(request_id,requester_issuer,requester_subject,binding_token,pot_id,observed_version,operation,payload)"
                                        + " values"
                                        + " (?,'i5-dev','actor','i5-development-fixture',?,0,'CreatePot',?::jsonb)",
                                    request,
                                    pot,
                                    payload);
                            Long pos =
                                    sql.queryForObject(
                                            "update v3_request_counter set position=position+1"
                                                    + " where family='COMMAND' returning position",
                                            Long.class);
                            sql.update(
                                    "insert into v3_request_position(family,position,request_id)"
                                            + " values ('COMMAND',?,?)",
                                    pos,
                                    request);
                            return null;
                        });
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

    static void realIdentity(QualificationSettings settings) {
        if (!settings.identityRequest())
            throw new IllegalStateException("explicit real IdentityRequest required");
        if (!settings.identityAuthority().equals("real") || settings.fixtureIdentity())
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
        if (operation instanceof IdentityRequest.Link)
            throw new IllegalArgumentException("Retired Identity operation");
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

    static void injectIdentity(QualificationSettings settings, Path intention)
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

    static void identityToken(QualificationSettings settings, Path ownKey, Path destination)
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
            QualificationSettings settings, UUID id, int seconds, String expected)
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
                var row = rows.getFirst();
                if (expected != null && !expected.equals(row.get("outcome")))
                    throw new IllegalStateException("Unexpected Identity outcome");
                verifyIdentityResult(settings, sql, id, row);
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Identity observation incomplete");
    }

    private static void verifyIdentityResult(
            QualificationSettings settings,
            JdbcTemplate sql,
            UUID id,
            Map<String, Object> row) {
        var source = admin(settings.writeControl());
        var tx = new SpringAttemptTransactions(source, manager(source), 5);
        var observation = new JdbcIdentityRequestObservation(source);
        boolean completed =
                tx.read(ctx -> observation.observe(id, ctx))
                        == com.kartaguez.pocoma.contract.consumption.spi.RecoveryObservation
                                .COMPLETED;
        Integer remainingSlots =
                sql.queryForObject(
                        "select count(*) from consumption_slot where"
                                + " consumption_id='2999e75c-272d-5afe-a966-c9364ef021fe'"
                                + " and consumable_id=?",
                        Integer.class,
                        id);
        if (!completed)
            throw new IllegalStateException("Identity terminal evidence inconsistent");
        if (remainingSlots == null || remainingSlots != 0)
            throw new IllegalStateException("Identity terminal evidence inconsistent");
        System.out.println("L3_DEV_IDENTITY_VERIFIED request=" + id + " result=" + row);
    }

    static void verify(QualificationSettings settings, UUID request, int seconds)
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
            if (isTerminalCommandVerified(status, settings, write, read, request)) {
                System.out.println(
                        "I5_DEV_VERIFIED real CreatePot V1, terminal result, canonical slot"
                                + " absent, dense publications; READ probes="
                                + settings.technicalProbes()
                                + " (technical only)");
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("bounded observation incomplete; not a business PASS");
    }

    private static boolean isTerminalCommandVerified(
            List<String> status,
            QualificationSettings settings,
            JdbcTemplate write,
            JdbcTemplate read,
            UUID request) {
        if (status.size() != 1 || !status.getFirst().equals("TERMINAL")) return false;
        return verifyTerminalCommand(settings, write, read, request);
    }

    private static boolean verifyTerminalCommand(
            QualificationSettings settings,
            JdbcTemplate write,
            JdbcTemplate read,
            UUID request) {
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
        verifyCommandWriteState(write, request, events);
        return !settings.technicalProbes() || verifyTechnicalProbes(read, events);
    }

    private static void requireCount(
            JdbcTemplate jdbc, String sql, int expected, String error, Object... args) {
        Integer count = jdbc.queryForObject(sql, Integer.class, args);
        if (count == null || count != expected) throw new IllegalStateException(error);
    }

    private static void verifyCommandWriteState(
            JdbcTemplate write, UUID request, List<UUID> events) {
        requireCount(
                write,
                "select count(*) from v3_command_result where request_id=?"
                        + " and outcome='CHANGED' and resulting_pot_version=1",
                1,
                "inconsistent Command completion",
                request);
        requireCount(
                write,
                "select count(*) from consumption_slot where"
                        + " consumable_id=? and"
                        + " consumption_id='98f7268d-d340-581c-b83d-010926223e14'",
                0,
                "inconsistent Command completion",
                request);
        requireCount(
                write,
                "select count(*) from v3_pot_version v join v3_command c"
                        + " using(pot_id) where c.request_id=? and v.version=1",
                1,
                "real version or dense publications missing",
                request);
        requireCount(
                write,
                "select count(*) from v3_event_position where event_id in (?,?)",
                2,
                "real version or dense publications missing",
                events.get(0),
                events.get(1));
    }

    private static boolean verifyTechnicalProbes(JdbcTemplate read, List<UUID> events) {
        Integer probeEffects =
                read.queryForObject(
                        "select count(*) from i5_read_probe_effect where"
                                + " consumable_id in (?,?) and value='technical'",
                        Integer.class,
                        events.get(0),
                        events.get(1));
        Integer remainingReadSlots =
                read.queryForObject(
                        "select count(*) from consumption_slot where consumable_id in (?,?)",
                        Integer.class,
                        events.get(0),
                        events.get(1));
        return probeEffects != null && probeEffects == 2
                && remainingReadSlots != null && remainingReadSlots == 0;
    }
}
