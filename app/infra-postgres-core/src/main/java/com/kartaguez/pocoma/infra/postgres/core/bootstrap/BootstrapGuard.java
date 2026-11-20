package com.kartaguez.pocoma.infra.postgres.core.bootstrap;

import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition.Authority;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.*;
import java.util.*;

import javax.sql.DataSource;

/** Reads only: refusal is the only response to any incomplete or incompatible authority. */
public final class BootstrapGuard {
    private static final List<String> TABLES =
            List.of(
                    "consumption_routing_contract",
                    "partition_ownership",
                    "discovery_checkpoint",
                    "consumption_slot");

    public enum Privileges {
        LIFECYCLE_ONLY,
        LIFECYCLE_AND_DISCOVERY
    }

    private record GuardOptions(
            boolean development,
            boolean technicalProbes,
            boolean fixtureIdentity,
            boolean readWorker,
            Authority validationScope) {
        static GuardOptions production() {
            return new GuardOptions(false, false, false, false, null);
        }
    }

    private final GuardOptions options;
    private final Target write;
    private final Target read;
    private final List<ConsumptionDefinition> definitions;

    public record Target(DataSource source, String database, String schema, String runtimeRole) {
        public Target {
            Objects.requireNonNull(source);
            for (String value : List.of(database, schema, runtimeRole)) {
                if (!value.matches("[a-z][a-z0-9_]*") || value.equals("public"))
                    throw new IllegalArgumentException(
                            "explicit non-public pilot identity required");
            }
        }

        public static Target from(Environment env, String authority) {
            String p = "pocoma.v3.pilot." + authority + ".";
            return new Target(
                    new DriverManagerDataSource(
                            env.getRequiredProperty(p + "url"),
                            env.getRequiredProperty(p + "username"),
                            env.getRequiredProperty(p + "password")),
                    env.getRequiredProperty(p + "database"),
                    env.getRequiredProperty(p + "schema"),
                    env.getRequiredProperty(p + "username"));
        }
    }

    public BootstrapGuard(Target write, Target read, List<ConsumptionDefinition> definitions) {
        this(write, read, definitions, GuardOptions.production());
    }

    private BootstrapGuard(
            Target write,
            Target read,
            List<ConsumptionDefinition> definitions,
            GuardOptions options) {
        this.options = Objects.requireNonNull(options);
        this.write = Objects.requireNonNull(write);
        this.read = Objects.requireNonNull(read);
        this.definitions = List.copyOf(definitions);
        validateDefinitions(this.definitions, options);
    }

    private static void validateDefinitions(
            List<ConsumptionDefinition> definitions, GuardOptions options) {
        if (!options.development()) {
            validateProductionDefinitions(definitions);
            return;
        }
        validateDevelopmentDefinitions(definitions, options.technicalProbes());
    }

    private static void validateProductionDefinitions(List<ConsumptionDefinition> definitions) {
        if (definitions.size() != 9) {
            throw new IllegalArgumentException("nine unique pilot identities required");
        }
        if (definitions.stream().map(ConsumptionDefinition::id).distinct().count() != 9) {
            throw new IllegalArgumentException("nine unique pilot identities required");
        }
    }

    private static void validateDevelopmentDefinitions(
            List<ConsumptionDefinition> definitions, boolean technicalProbes) {
        var expected = new ArrayList<>(ConsumptionCatalog.definitions());
        if (technicalProbes) expected.addAll(ConsumptionCatalog.probeDefinitions());
        if (definitions.size() != expected.size()
                || !new HashSet<>(definitions).equals(new HashSet<>(expected))) {
            throw new IllegalArgumentException("exact business/technical tuples required");
        }
    }

    public static BootstrapGuard development(
            Target write,
            Target read,
            List<ConsumptionDefinition> definitions,
            boolean probes,
            boolean fixture) {
        return new BootstrapGuard(
                write, read, definitions, new GuardOptions(true, probes, fixture, false, null));
    }

    public static BootstrapGuard readWorker(
            Target write, Target read, List<ConsumptionDefinition> definitions) {
        return new BootstrapGuard(
                write, read, definitions, new GuardOptions(true, false, false, true, null));
    }

    /** Process owns only WRITE resources; the source role is inspected without opening READ. */
    public static BootstrapGuard processWorker(
            Target write,
            String sourceReadRole,
            List<ConsumptionDefinition> definitions,
            boolean fixture) {
        return new BootstrapGuard(
                write,
                new Target(write.source(), write.database(), write.schema(), sourceReadRole),
                definitions,
                new GuardOptions(true, false, fixture, false, Authority.WRITE));
    }

    /** Materialize owns READ lifecycle resources and a separate SELECT-only WRITE source. */
    public static BootstrapGuard materializeWorker(
            Target source, Target read, List<ConsumptionDefinition> definitions) {
        return new BootstrapGuard(
                source, read, definitions, new GuardOptions(true, false, false, true, Authority.READ));
    }
    public void validateWriteSourceRead(DataSource source) {
        WriteSourceReadPrivileges.validate(new Target(source,write.database(),write.schema(),write.runtimeRole()));
    }

    private static final Map<String, Set<String>> READ_UPDATES =
            Map.ofEntries(
                    Map.entry("read_subobject_version", Set.of()),
                    Map.entry("read_pot_user_set", Set.of()),
                    Map.entry("read_pot_version_node", Set.of()),
                    Map.entry("read_pot_version", Set.of()),
                    Map.entry("read_current_pot", Set.of("pot_version")),
                    Map.entry("read_pot_balance_content", Set.of()),
                    Map.entry("read_pot_balance_aggregate_content", Set.of()),
                    Map.entry("read_pot_balance", Set.of()),
                    Map.entry("read_pot_balance_aggregate", Set.of()),
                    Map.entry("read_pot_visibility_state", Set.of("materialized_version")),
                    Map.entry("read_user_pot", Set.of("pot_version", "visible")),
                    Map.entry("read_current_binding", Set.of("eui_version", "user_id", "binding_token")),
                    Map.entry("read_command_result", Set.of()),
                    Map.entry("read_identity_request_result", Set.of()),
                    Map.entry("read_current_invitation", Set.of("version", "expires_at", "protected_link")));

    private record TableRightsSpec(
            boolean allowInsert, boolean allowDelete, String ownerQuery) {}

    private record ColumnRightsSpec(
            java.util.function.Predicate<String> allowInsert,
            java.util.function.Predicate<String> allowUpdate,
            String errorDetailPrefix) {}

    private static void verifyTableRights(
            Statement s, String table, TableRightsSpec spec, String errorDetailPrefix)
            throws SQLException {
        String sql =
                "select has_table_privilege('"
                        + table
                        + "','SELECT'),has_table_privilege('"
                        + table
                        + "','INSERT'),has_table_privilege('"
                        + table
                        + "','UPDATE,TRUNCATE,REFERENCES,TRIGGER'),has_table_privilege('"
                        + table
                        + "','DELETE'),pg_has_role(current_user,("
                        + spec.ownerQuery()
                        + "),'MEMBER')";
        try (var rows = s.executeQuery(sql)) {
            rows.next();
            boolean valid =
                    rows.getBoolean(1)
                            && rows.getBoolean(2) == spec.allowInsert()
                            && !rows.getBoolean(3)
                            && rows.getBoolean(4) == spec.allowDelete()
                            && !rows.getBoolean(5);
            require(valid, errorDetailPrefix + table);
        }
    }

    private static void verifyInformationSchemaColumns(
            Statement s, String table, ColumnRightsSpec spec) throws SQLException {
        String sql =
                "select column_name,has_column_privilege('"
                        + table
                        + "',column_name,'INSERT'),has_column_privilege('"
                        + table
                        + "',column_name,'UPDATE'),has_column_privilege('"
                        + table
                        + "',column_name,'REFERENCES') from information_schema.columns"
                        + " where table_schema=current_schema() and table_name='"
                        + table
                        + "'";
        try (var rows = s.executeQuery(sql)) {
            while (rows.next()) {
                String column = rows.getString(1);
                boolean valid =
                        rows.getBoolean(2) == spec.allowInsert().test(column)
                                && rows.getBoolean(3) == spec.allowUpdate().test(column)
                                && !rows.getBoolean(4);
                require(valid, spec.errorDetailPrefix() + column);
            }
        }
    }

    private static String pgTablesOwnerQuery(String table) {
        return "select tableowner from pg_tables where schemaname=current_schema() and tablename='"
                + table
                + "'";
    }

    private static void inspectReadBusiness(Statement s) throws SQLException {
        for (var entry : READ_UPDATES.entrySet()) {
            String table = entry.getKey();
            if (table.equals("read_current_invitation") && !tableExists(s, table)) continue;
            verifyTableRights(
                    s,
                    table,
                    new TableRightsSpec(true, false, pgTablesOwnerQuery(table)),
                    "unsafe READ business table rights: ");
            verifyReadBusinessColumnRights(s, table, entry.getValue());
            verifyNoPublicAcl(s, table, "PUBLIC READ business grant: " + table);
        }
    }

    private static void verifyReadBusinessColumnRights(
            Statement s, String table, Set<String> updatableColumns) throws SQLException {
        String sql =
                "select a.attname,has_column_privilege(c.oid,a.attnum,'SELECT'),"
                        + "has_column_privilege(c.oid,a.attnum,'INSERT'),has_column_privilege(c.oid,a.attnum,'UPDATE'),has_column_privilege(c.oid,a.attnum,'REFERENCES')"
                        + " from pg_class c join pg_attribute a on a.attrelid=c.oid where"
                        + " c.oid='"
                        + table
                        + "'::regclass and a.attnum>0 and not a.attisdropped";
        try (var rows = s.executeQuery(sql)) {
            while (rows.next()) {
                boolean valid =
                        rows.getBoolean(2)
                                && rows.getBoolean(3)
                                && rows.getBoolean(4) == updatableColumns.contains(rows.getString(1))
                                && !rows.getBoolean(5);
                require(valid, "unsafe READ business column rights: " + table + "." + rows.getString(1));
            }
        }
    }

    private static void verifyNoPublicAcl(Statement s, String table, String message)
            throws SQLException {
        String sql =
                "select exists(select 1 from pg_class c, lateral aclexplode(c.relacl)"
                        + " acl where c.oid='"
                        + table
                        + "'::regclass and acl.grantee=0) or exists(select 1 from"
                        + " pg_attribute a, lateral aclexplode(a.attacl) acl where"
                        + " a.attrelid='"
                        + table
                        + "'::regclass and acl.grantee=0)";
        try (var rows = s.executeQuery(sql)) {
            rows.next();
            require(!rows.getBoolean(1), message);
        }
    }

    public void validate() {
        try {
            String w = inspect(write, Authority.WRITE, null);
            String r = inspect(read, Authority.READ, null);
            if (w.equals(r))
                throw new IllegalStateException("WRITE and READ share a physical database");
        } catch (SQLException failure) {
            throw new IllegalStateException("L2 pilot authority refused", failure);
        }
    }

    public void validateLifecyclePrivileges() {
        validateLifecyclePrivileges(Privileges.LIFECYCLE_ONLY);
    }

    public void validateLifecyclePrivileges(Privileges profile) {
        Objects.requireNonNull(profile);
        if (options.validationScope() == Authority.WRITE) {
            validateLifecycleSource(write.source(), Authority.WRITE, profile);
            return;
        }
        if (options.validationScope() == Authority.READ) {
            validateWriteSourceRead(write.source());
            validateLifecycleSource(read.source(), Authority.READ, profile);
            return;
        }
        try {
            String w = inspect(write, Authority.WRITE, profile);
            String r = inspect(read, Authority.READ, profile);
            if (w.equals(r)) {
                throw new IllegalStateException("WRITE and READ share a physical database");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("L2 lifecycle authority refused", failure);
        }
    }

    public void validateLifecycleSource(DataSource source, Authority authority) {
        validateLifecycleSource(source, authority, Privileges.LIFECYCLE_ONLY);
    }

    public void validateLifecycleSource(
            DataSource source, Authority authority, Privileges profile) {
        Objects.requireNonNull(profile);
        if (options.validationScope() == Authority.READ && authority == Authority.WRITE) {
            validateWriteSourceRead(source);
            return;
        }
        ensureReadAuthorityAvailable(authority);
        var expected = authority == Authority.WRITE ? write : read;
        try {
            inspect(
                    new Target(
                            source, expected.database(), expected.schema(), expected.runtimeRole()),
                    authority,
                    profile);
        } catch (SQLException failure) {
            throw new IllegalStateException("actual lifecycle source refused", failure);
        }
    }

    private void ensureReadAuthorityAvailable(Authority authority) {
        if (options.validationScope() == Authority.WRITE && authority != Authority.WRITE) {
            throw new IllegalStateException("Process has no READ authority");
        }
    }

    private String inspect(Target target, Authority authority, Privileges profile)
            throws SQLException {
        try (var c = target.source().getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (var s = c.createStatement()) {
                verifyTargetIdentity(s, target);
                verifyRoleNotPrivileged(s);
                verifyRoutingAndOwnership(s, authority);
                if (options.development()) inspectDevelopment(s, authority);
                // All table rights are effective rights: PUBLIC and inherited roles included.
                if (profile != null) inspectLifecycle(s, profile);
                if (profile == Privileges.LIFECYCLE_AND_DISCOVERY && authority == Authority.WRITE) {
                    inspectDiscoveryWrite(s);
                }
                if (profile == null) verifyReadOnlyPilotTables(s);
                if (authority == Authority.WRITE) verifyWriteCommandTriggers(s);
                verifySqlHash(c);
                return readPhysicalIdentity(c, s);
            } finally {
                c.rollback();
            }
        }
    }

    private static void verifyTargetIdentity(Statement s, Target target) throws SQLException {
        try (var rs =
                s.executeQuery(
                        "select current_database(),current_schema(),current_user,"
                                + "inet_server_addr()::text,inet_server_port()")) {
            rs.next();
            boolean match =
                    target.database().equals(rs.getString(1))
                            && target.schema().equals(rs.getString(2))
                            && target.runtimeRole().equals(rs.getString(3));
            require(match, "wrong database/schema/runtime role");
        }
    }

    private static void verifyRoleNotPrivileged(Statement s) throws SQLException {
        try (var rs =
                s.executeQuery(
                        "select exists(select 1 from pg_roles where"
                                + " pg_has_role(current_user,oid,'MEMBER') and (rolsuper or"
                                + " rolcreaterole or rolcreatedb or rolreplication or"
                                + " rolbypassrls or"
                                + " has_schema_privilege(oid,current_schema(),'CREATE')))")) {
            rs.next();
            require(!rs.getBoolean(1), "privileged runtime role");
        }
        try (var rs =
                s.executeQuery(
                        "select"
                                + " has_schema_privilege(current_schema(),'CREATE'),to_regclass('v3_command_slot')")) {
            rs.next();
            require(!rs.getBoolean(1) && rs.getString(2) == null, "DDL privilege or legacy Command slot");
        }
    }

    private void verifyRoutingAndOwnership(Statement s, Authority authority) throws SQLException {
        var expected = new HashMap<UUID, ConsumptionDefinition>();
        definitions.stream()
                .filter(d -> d.authority() == authority)
                .forEach(d -> expected.put(d.id().value(), d));
        try (var rs =
                s.executeQuery(
                        "select consumption_id,family,routing_version,partition_count from"
                                + " consumption_routing_contract")) {
            while (rs.next()) {
                var definition = expected.remove(rs.getObject(1, UUID.class));
                require(matchesDefinition(definition, rs), "routing contract mismatch");
            }
            require(expected.isEmpty(), "incomplete routing catalog");
        }
        try (var rs =
                s.executeQuery(
                        "select c.consumption_id,p from consumption_routing_contract c"
                                + " cross join generate_series(0,3) p except select"
                                + " consumption_id,partition_id from partition_ownership")) {
            require(!rs.next(), "missing partition ownership");
        }
        try (var rs = s.executeQuery("select count(*) from partition_ownership")) {
            rs.next();
            long expectedCount =
                    definitions.stream().filter(d -> d.authority() == authority).count() * 4;
            require(rs.getInt(1) == expectedCount, "extra ownership");
        }
    }

    private static boolean matchesDefinition(ConsumptionDefinition definition, ResultSet rs)
            throws SQLException {
        if (definition == null) return false;
        if (!definition.family().name().equals(rs.getString(2))) return false;
        if (!definition.routingVersion().equals(rs.getString(3))) return false;
        return definition.partitionCount() == rs.getInt(4);
    }

    private static void verifyReadOnlyPilotTables(Statement s) throws SQLException {
        for (String table : TABLES) {
            try (var rs =
                    s.executeQuery(
                            "select has_table_privilege('"
                                    + table
                                    + "','SELECT'),exists(select 1 from pg_roles where"
                                    + " pg_has_role(current_user,oid,'MEMBER') and"
                                    + " has_table_privilege(oid,'"
                                    + table
                                    + "','INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')),pg_has_role(current_user,(select"
                                    + " tableowner from pg_tables where"
                                    + " schemaname=current_schema() and tablename='"
                                    + table
                                    + "'),'MEMBER')")) {
                rs.next();
                require(
                        rs.getBoolean(1) && !rs.getBoolean(2) && !rs.getBoolean(3),
                        "unsafe table privileges: " + table);
            }
        }
    }

    private void verifyWriteCommandTriggers(Statement s) throws SQLException {
        UUID command =
                definitions.stream()
                        .filter(d -> d.family() == ConsumableFamily.COMMAND)
                        .findFirst()
                        .orElseThrow()
                        .id()
                        .value();
        try (var rs =
                s.executeQuery(
                        "select"
                                + " pg_get_functiondef('v3_require_command_unit()'::regprocedure),"
                                + " to_regclass('v3_request_counter') is not null and"
                                + " to_regclass('v3_request_position') is not null")) {
            rs.next();
            boolean valid =
                    rs.getString(1)
                                    .contains(
                                            "s.consumption_id='"
                                                    + command
                                                    + "'::uuid and s.consumable_id=c.request_id")
                            && rs.getBoolean(2);
            require(valid, "canonical Command trigger or request publication tables mismatch");
        }
        try (var rs =
                s.executeQuery(
                        "select count(*)=4 from pg_trigger where tgrelid in"
                                + " ('v3_command'::regclass,'v3_identity_request'::regclass,'v3_request_counter'::regclass,'v3_request_position'::regclass)"
                                + " and tgname in"
                                + " ('v3_command_request_positioned','v3_identity_request_positioned','v3_request_counter_unit','v3_request_position_unit')"
                                + " and tgdeferrable and tginitdeferred")) {
            rs.next();
            require(rs.getBoolean(1), "sequential request publication triggers required");
        }
    }

    private static void verifySqlHash(Connection c) throws SQLException {
        // Independent fixed ordinary UUID catches a replaced SQL hash function at startup.
        for (var family : ConsumableFamily.values()) {
            var id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
            try (var q = c.prepareStatement("select consumption_hash_v1(?,?)")) {
                q.setString(1, family.name());
                q.setObject(2, id);
                try (var rs = q.executeQuery()) {
                    rs.next();
                    require(rs.getInt(1) == RoutingV1.hash(family, id), "SQL hash mismatch");
                }
            }
        }
    }

    private static String readPhysicalIdentity(Connection c, Statement s) throws SQLException {
        try (var rs =
                s.executeQuery(
                        "select inet_server_addr()::text || ':' || inet_server_port() ||"
                                + " '/' || current_database()")) {
            rs.next();
            String identity = rs.getString(1);
            require(identity != null, "TCP physical authority identity required");
            c.rollback();
            return identity;
        }
    }

    private static void inspectLifecycle(Statement s, Privileges profile) throws SQLException {
        verifyLifecycleRolesAndPublicAcl(s);
        for (String table : TABLES) {
            verifyTableRights(
                    s,
                    table,
                    new TableRightsSpec(
                            false, table.equals("consumption_slot"), pgTablesOwnerQuery(table)),
                    "unsafe lifecycle table rights: ");
            var updateColumns = lifecycleUpdateColumns(table, profile);
            var insertColumns = lifecycleInsertColumns(table, profile);
            verifyInformationSchemaColumns(
                    s,
                    table,
                    new ColumnRightsSpec(
                            insertColumns::contains,
                            updateColumns::contains,
                            "unsafe lifecycle column rights: " + table + "."));
        }
    }

    private static void verifyLifecycleRolesAndPublicAcl(Statement s) throws SQLException {
        // Reject role switching too: NOINHERIT does not prevent SET ROLE.
        try (var rs =
                s.executeQuery(
                        "select exists(select 1 from pg_roles where rolname<>current_user and"
                                + " pg_has_role(current_user,oid,'MEMBER'))")) {
            rs.next();
            require(!rs.getBoolean(1), "lifecycle role membership");
        }
        try (var rows =
                s.executeQuery(
                        "select exists(select 1 from pg_class c join pg_namespace n on"
                            + " n.oid=c.relnamespace, lateral aclexplode(c.relacl) acl where"
                            + " n.nspname=current_schema() and acl.grantee=0 and"
                            + " acl.privilege_type<>'SELECT') or exists(select 1 from pg_attribute"
                            + " a join pg_class c on c.oid=a.attrelid join pg_namespace n on"
                            + " n.oid=c.relnamespace, lateral aclexplode(a.attacl) acl where"
                            + " n.nspname=current_schema() and acl.grantee=0 and"
                            + " acl.privilege_type<>'SELECT')")) {
            rows.next();
            require(!rows.getBoolean(1), "PUBLIC mutation grant in lifecycle authority");
        }
    }

    private static Set<String> lifecycleUpdateColumns(String table, Privileges profile) {
        return switch (table) {
            case "partition_ownership" -> Set.of("owner_token", "owned_until");
            case "consumption_slot" ->
                    Set.of("claim_token", "claimed_until", "retry_after", "attempt_count", "state");
            case "discovery_checkpoint" ->
                    profile == Privileges.LIFECYCLE_AND_DISCOVERY ? Set.of("position") : Set.of();
            default -> Set.of();
        };
    }

    private static Set<String> lifecycleInsertColumns(String table, Privileges profile) {
        if (profile != Privileges.LIFECYCLE_AND_DISCOVERY) return Set.of();
        return switch (table) {
            case "consumption_slot" ->
                    Set.of("consumption_id", "consumable_id", "partition_hash", "partition_id");
            case "discovery_checkpoint" -> Set.of("consumption_id", "partition_id", "position");
            default -> Set.of();
        };
    }

    private static final Set<String> IDENTITY_TABLES =
            Set.of(
                    "v3_identity_request",
                    "v3_eui_authority",
                    "v3_pocoma_user",
                    "v3_eui_version",
                    "v3_identity_request_result",
                    "v3_identity_event",
                    "v3_identity_completion_event");

    public void validateIdentitySource(DataSource source) {
        validateIdentitySource(source, null);
    }

    public void validateIdentitySource(DataSource source, String readEffectRole) {
        if (options.fixtureIdentity()) {
            throw new IllegalStateException("Real Identity refuses fixture authority");
        }
        validateLifecycleSource(source, Authority.WRITE, Privileges.LIFECYCLE_AND_DISCOVERY);
        try (var c = source.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (var statement = c.createStatement()) {
                inspectIdentity(statement);
                verifyIdentityLoggingPolicy(statement);
                require(
                        !read.runtimeRole().equals(write.runtimeRole()),
                        "distinct READ/WRITE principals required for real DEV");
                verifyIdentityReadPrincipal(c, statement, readEffectRole);
            } finally {
                c.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Identity V6 authority refused");
        }
    }

    private void verifyIdentityReadPrincipal(
            Connection c, Statement statement, String readEffectRole) throws SQLException {
        if (options.validationScope() == Authority.WRITE && readEffectRole != null) {
            if (!read.runtimeRole().equals(readEffectRole)) {
                WriteSourceReadPrivileges.inspectRole(statement, read.runtimeRole());
            }
            requireNoIdentityAuthority(c, statement, readEffectRole);
            return;
        }
        if (options.validationScope() == Authority.WRITE) {
            WriteSourceReadPrivileges.inspectRole(statement, read.runtimeRole());
            return;
        }
        requireNoIdentityAuthority(c, statement, read.runtimeRole());
    }

    private static void verifyIdentityLoggingPolicy(Statement statement) throws SQLException {
        try (var rows =
                statement.executeQuery(
                        """
select current_setting('log_error_verbosity')='terse'
 and current_setting('log_min_messages')='warning'
 and current_setting('log_min_error_statement')='panic'
 and current_setting('log_statement')='none' and current_setting('log_duration')='off'
 and current_setting('log_min_duration_statement')='-1'
 and current_setting('log_min_duration_sample')='-1'
 and current_setting('log_transaction_sample_rate')='0'
 and current_setting('log_parameter_max_length')='0'
 and current_setting('log_parameter_max_length_on_error')='0'
 and position('%e' in current_setting('log_line_prefix'))>0
 and position('%p' in current_setting('log_line_prefix'))>0
 and position('%x' in current_setting('log_line_prefix'))>0
""")) {
            rows.next();
            require(rows.getBoolean(1), "Identity PostgreSQL logging policy required");
        }
    }

    private static void requireNoIdentityAuthority(Connection c, Statement statement, String role)
            throws SQLException {
        require(role.matches("[a-z][a-z0-9_]*"), "invalid READ principal");
        try (var query =
                c.prepareStatement(
                        """
select exists(select 1 from pg_roles where rolname=? and
    (rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls))
 or not exists(select 1 from pg_roles where rolname=?)
 or exists(select 1 from pg_roles where rolname<>? and pg_has_role(?,oid,'MEMBER'))
 or exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace
    where c.relkind in ('v','m') and n.nspname not in ('pg_catalog','information_schema')
    and (has_any_column_privilege(?,c.oid,'SELECT') or has_table_privilege(?,c.oid,'SELECT')))
 or exists(select 1 from pg_proc p join pg_namespace n on n.oid=p.pronamespace
    where p.prosecdef and n.nspname not in ('pg_catalog','information_schema'))
""")) {
            for (int parameter = 1; parameter <= 6; parameter++)
                query.setString(parameter, role);
            try (var rows = query.executeQuery()) {
                rows.next();
                require(
                        !rows.getBoolean(1),
                        "Identity indirect exposure or unsafe READ principal on WRITE");
            }
        }
        var identityAndRequestTables = new HashSet<>(IDENTITY_TABLES);
        identityAndRequestTables.addAll(
                Set.of("v3_request_counter", "v3_request_position"));
        for (String table : identityAndRequestTables) {
            try (var rows =
                    statement.executeQuery(
                            "select exists(select 1 from pg_roles where rolname='"
                                    + role
                                    + "' and (has_any_column_privilege(oid,'"
                                    + table
                                    + "','SELECT,INSERT,UPDATE,REFERENCES')"
                                    + " or has_table_privilege(oid,'"
                                    + table
                                    + "','DELETE,TRUNCATE,TRIGGER')))")) {
                rows.next();
                require(
                        !rows.getBoolean(1),
                        "READ principal has Identity WRITE authority privileges");
            }
        }
    }

    private static void inspectIdentity(Statement statement) throws SQLException {
        verifyIdentitySchemaAndConstraints(statement);
        verifyIdentityPublicExposure(statement);
        for (String table : IDENTITY_TABLES) {
            boolean insert = !table.equals("v3_identity_request");
            String ownerQuery =
                    "select relowner from pg_class where oid='" + table + "'::regclass";
            verifyTableRights(
                    statement,
                    table,
                    new TableRightsSpec(insert, false, ownerQuery),
                    "unsafe Identity table rights: ");
            verifyInformationSchemaColumns(
                    statement,
                    table,
                    new ColumnRightsSpec(
                            col -> insert,
                            col -> isUpdatableIdentityColumn(table, col),
                            "unsafe Identity column rights: "));
        }
    }

    private static void verifyIdentitySchemaAndConstraints(Statement statement)
            throws SQLException {
        try (var rows =
                statement.executeQuery(
                        "select current_setting('server_encoding')='UTF8' and"
                            + " current_setting('client_encoding')='UTF8',to_regprocedure('v3_identity_check_unit(uuid,boolean)')"
                            + " is not null and to_regclass('v3_identity_request') is not null")) {
            rows.next();
            require(rows.getBoolean(1) && rows.getBoolean(2), "Identity V6/encoding required");
        }
        try (var rows =
                statement.executeQuery(
                        "select count(*)=2 from pg_trigger where tgrelid in"
                            + " ('v3_eui_authority'::regclass,'v3_eui_version'::regclass) and"
                            + " tgname in ('v3_identity_anchor_state','v3_identity_version_state')"
                            + " and tgdeferrable and tginitdeferred")) {
            rows.next();
            require(rows.getBoolean(1), "Identity structural constraints required");
        }
        try (var rows =
                statement.executeQuery(
                        "select count(*)=6 from pg_attribute a join pg_collation c on"
                            + " c.oid=a.attcollation where a.attrelid in"
                            + " ('v3_eui_authority'::regclass,'v3_identity_request'::regclass) and"
                            + " a.attname in"
                            + " ('issuer','subject','requester_issuer','requester_subject','target_issuer','target_subject')"
                            + " and c.collname='C'")) {
            rows.next();
            require(rows.getBoolean(1), "Identity C collation required");
        }
        try (var rows =
                statement.executeQuery(
                        "select count(*)=2 from v3_event_counter where family in"
                                + " ('IDENTITY','IDENTITY_COMPLETION')")) {
            rows.next();
            require(rows.getBoolean(1), "Identity publication counters required");
        }
    }

    private static void verifyIdentityPublicExposure(Statement statement) throws SQLException {
        // Identity contains tokens: even PUBLIC SELECT is excessive. The L2 technical
        // tables keep their existing policy; this check is specific to the real authority.
        try (var rows =
                statement.executeQuery(
                        """
select exists(select 1 from pg_class c join pg_namespace n on n.oid=c.relnamespace,
    lateral aclexplode(c.relacl) acl where n.nspname=current_schema()
    and c.relname in ('v3_identity_request','v3_eui_authority','v3_pocoma_user','v3_eui_version',
        'v3_identity_request_result','v3_identity_event','v3_identity_completion_event') and acl.grantee=0)
or exists(select 1 from pg_attribute a join pg_class c on c.oid=a.attrelid
    join pg_namespace n on n.oid=c.relnamespace,lateral aclexplode(a.attacl) acl
    where n.nspname=current_schema() and c.relname in ('v3_identity_request','v3_eui_authority',
        'v3_pocoma_user','v3_eui_version','v3_identity_request_result','v3_identity_event',
        'v3_identity_completion_event') and acl.grantee=0)
""")) {
            rows.next();
            require(!rows.getBoolean(1), "PUBLIC privileges expose Identity authority");
        }
    }

    private static boolean isUpdatableIdentityColumn(String table, String column) {
        if (table.equals("v3_identity_request")) return column.equals("status");
        if (table.equals("v3_eui_authority")) return column.equals("current_version");
        return false;
    }

    private static boolean tableExists(Statement statement, String table) throws SQLException {
        try (var rows = statement.executeQuery("select to_regclass('" + table + "') is not null")) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static final Set<String> DISCOVERY_INSERT_TABLES =
            Set.of(
                    "v3_command_result",
                    "v3_pot_identity",
                    "v3_pot_version",
                    "v3_shareholder_identity",
                    "v3_expense_identity",
                    "v3_pot_header",
                    "v3_pot_details",
                    "v3_shareholder_header",
                    "v3_shareholder_details",
                    "v3_shareholder_weight",
                    "v3_expense_header",
                    "v3_expense_details",
                    "v3_expense_financials",
                    "v3_expense_participant",
                    "v3_pot_event",
                    "v3_pot_event_component",
                    "v3_command_completion_event",
                    "v3_event_position");

    private static final Set<String> DISCOVERY_HISTORY_TABLES =
            Set.of(
                    "v3_pot_header",
                    "v3_pot_details",
                    "v3_shareholder_header",
                    "v3_shareholder_details",
                    "v3_shareholder_weight",
                    "v3_expense_header",
                    "v3_expense_details",
                    "v3_expense_financials");

    private static void inspectDiscoveryWrite(Statement statement) throws SQLException {
        var insertTables = new HashSet<>(DISCOVERY_INSERT_TABLES);
        for (String table : List.of("v3_invitation", "v3_invitation_event")) {
            if (tableExists(statement, table)) insertTables.add(table);
        }
        var tables = new HashSet<>(insertTables);
        tables.addAll(
                Set.of(
                        "v3_command",
                        "v3_event_counter",
                        "v3_request_counter",
                        "v3_request_position"));
        verifyDiscoveryCountersAndPublicAcl(statement);
        for (String table : tables) {
            boolean allowInsert = insertTables.contains(table);
            verifyTableRights(
                    statement,
                    table,
                    new TableRightsSpec(allowInsert, false, pgTablesOwnerQuery(table)),
                    "unsafe combined business table rights: ");
            Set<String> updates = discoveryUpdateColumns(table);
            verifyInformationSchemaColumns(
                    statement,
                    table,
                    new ColumnRightsSpec(
                            col -> allowInsert,
                            updates::contains,
                            "unsafe combined business column rights: " + table + "."));
        }
        try (var rows =
                statement.executeQuery(
                        "select exists(select 1 from pg_proc where pronamespace=(select oid from"
                                + " pg_namespace where nspname=current_schema()) and prosecdef)")) {
            rows.next();
            require(!rows.getBoolean(1), "SECURITY DEFINER in combined authority");
        }
    }

    private static void verifyDiscoveryCountersAndPublicAcl(Statement statement)
            throws SQLException {
        String sql =
                "select (select count(*)=2 from v3_request_counter where family in"
                        + " ('COMMAND','IDENTITY_REQUEST')),exists(select 1 from pg_class c"
                        + " join pg_namespace n on n.oid=c.relnamespace,lateral"
                        + " aclexplode(c.relacl) acl where n.nspname=current_schema() and"
                        + " c.relname in ('v3_request_counter','v3_request_position') and"
                        + " acl.grantee=0) or exists(select 1 from pg_attribute a join"
                        + " pg_class c on c.oid=a.attrelid join pg_namespace n on"
                        + " n.oid=c.relnamespace,lateral aclexplode(a.attacl) acl where"
                        + " n.nspname=current_schema() and c.relname in"
                        + " ('v3_request_counter','v3_request_position') and acl.grantee=0)";
        try (var rows = statement.executeQuery(sql)) {
            rows.next();
            require(
                    rows.getBoolean(1) && !rows.getBoolean(2),
                    "request publication counters required and PUBLIC grants forbidden");
        }
    }

    private static Set<String> discoveryUpdateColumns(String table) {
        if (DISCOVERY_HISTORY_TABLES.contains(table)) return Set.of("active_to");
        return switch (table) {
            case "v3_command" -> Set.of("status");
            case "v3_event_counter" -> Set.of("position");
            case "v3_pot_identity" -> Set.of("current_version");
            default -> Set.of();
        };
    }

    private void inspectDevelopment(Statement s, Authority authority) throws SQLException {
        verifyNoDatabaseOrPublicDdl(s);
        String fixture =
                authority == Authority.WRITE ? "i5_fixture_identity" : "i5_read_probe_effect";
        boolean enabled =
                authority == Authority.WRITE
                        ? options.fixtureIdentity()
                        : options.technicalProbes();
        verifyFixturePresence(s, fixture, enabled);
        if (enabled) verifyFixtureRights(s, fixture, authority);
        if (options.readWorker() && authority == Authority.READ) inspectReadBusiness(s);
        verifyNoUnallocatedTables(s, authority, fixture, enabled);
    }

    private static void verifyNoDatabaseOrPublicDdl(Statement s) throws SQLException {
        try (var row =
                s.executeQuery(
                        "select"
                            + " has_database_privilege(current_database(),'CREATE,TEMP'),has_schema_privilege('public','CREATE')")) {
            row.next();
            require(
                    !row.getBoolean(1) && !row.getBoolean(2),
                    "runtime database/public-schema DDL privilege");
        }
    }

    private static void verifyFixturePresence(Statement s, String fixture, boolean enabled)
            throws SQLException {
        try (var row = s.executeQuery("select to_regclass('" + fixture + "') is not null")) {
            row.next();
            require(row.getBoolean(1) == enabled, "fixture installation differs from explicit mode");
        }
    }

    private static void verifyFixtureRights(Statement s, String fixture, Authority authority)
            throws SQLException {
        String tableSql =
                "select has_table_privilege('"
                        + fixture
                        + "','SELECT'),has_table_privilege('"
                        + fixture
                        + "','INSERT'),has_table_privilege('"
                        + fixture
                        + "','UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES')";
        try (var row = s.executeQuery(tableSql)) {
            row.next();
            boolean valid =
                    row.getBoolean(1)
                            && row.getBoolean(2) == (authority == Authority.READ)
                            && !row.getBoolean(3);
            require(valid, "unsafe fixture table rights");
        }
        String colSql =
                "select column_name,has_column_privilege('"
                        + fixture
                        + "',column_name,'INSERT'),has_column_privilege('"
                        + fixture
                        + "',column_name,'UPDATE'),has_column_privilege('"
                        + fixture
                        + "',column_name,'REFERENCES') from information_schema.columns"
                        + " where table_schema=current_schema() and table_name='"
                        + fixture
                        + "'";
        try (var rows = s.executeQuery(colSql)) {
            while (rows.next()) {
                boolean allowUpdate =
                        authority == Authority.WRITE && rows.getString(1).equals("revision");
                boolean valid =
                        rows.getBoolean(2) == (authority == Authority.READ)
                                && rows.getBoolean(3) == allowUpdate
                                && !rows.getBoolean(4);
                require(valid, "unsafe fixture column rights");
            }
        }
    }

    private static final Set<String> DEV_WRITE_TABLES =
            Set.of(
                    "v3_command",
                    "v3_command_result",
                    "v3_invitation",
                    "v3_invitation_event",
                    "v3_pot_identity",
                    "v3_pot_version",
                    "v3_shareholder_identity",
                    "v3_expense_identity",
                    "v3_pot_header",
                    "v3_pot_details",
                    "v3_shareholder_header",
                    "v3_shareholder_details",
                    "v3_shareholder_weight",
                    "v3_expense_header",
                    "v3_expense_details",
                    "v3_expense_financials",
                    "v3_expense_participant",
                    "v3_pot_event",
                    "v3_pot_event_component",
                    "v3_command_completion_event",
                    "v3_event_position",
                    "v3_event_counter",
                    "v3_request_position",
                    "v3_request_counter");

    private void verifyNoUnallocatedTables(
            Statement s, Authority authority, String fixture, boolean enabled) throws SQLException {
        // Effective grants on any unlisted object would introduce another borrower/writer.
        String colCheck =
                options.readWorker()
                        ? " or has_any_column_privilege(quote_ident(schemaname)||'.'||quote_ident(tablename),'SELECT,INSERT,UPDATE,REFERENCES')"
                        : "";
        String sql =
                "select tablename,has_table_privilege(quote_ident(schemaname)||'.'||quote_ident(tablename),'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"
                        + colCheck
                        + " from pg_tables where schemaname=current_schema()";
        try (var rows = s.executeQuery(sql)) {
            while (rows.next()) {
                String table = rows.getString(1);
                if (isAllowedRuntimeTable(table, authority, fixture, enabled)) continue;
                require(!rows.getBoolean(2), "unallocated runtime table privileges");
            }
        }
    }

    private static boolean isAllowedRuntimeTable(
            String table, Authority authority, String fixture, boolean enabled) {
        if (TABLES.contains(table)) return true;
        if (enabled && table.equals(fixture)) return true;
        if (authority == Authority.READ) return READ_UPDATES.containsKey(table);
        return authority == Authority.WRITE
                && (DEV_WRITE_TABLES.contains(table) || IDENTITY_TABLES.contains(table));
    }

    private static final List<String> INERT_FORBIDDEN_PACKAGES =
            List.of(".infra.", ".engine.", ".orchestrator.", ".supra.", "$Legacy");

    public static void requireInertType(Class<?> type) {
        String name = type.getName();
        if (isForbiddenPocomaComponentName(name)) {
            throw new IllegalStateException("incompatible I1 pilot component: " + name);
        }
        if (isSpringBootRunner(type)) {
            throw new IllegalStateException("runner forbidden in I1 pilot: " + name);
        }
        if (isActivePocomaComponent(type, name)) {
            throw new IllegalStateException("active I1 pilot component: " + name);
        }
    }

    private static boolean isActivePocomaComponent(Class<?> type, String name) {
        return name.startsWith("com.kartaguez.pocoma.") && isActiveSpringComponent(type);
    }

    private static boolean isSpringBootRunner(Class<?> type) {
        return org.springframework.boot.ApplicationRunner.class.isAssignableFrom(type)
                || org.springframework.boot.CommandLineRunner.class.isAssignableFrom(type);
    }

    private static boolean isForbiddenPocomaComponentName(String name) {
        if (!name.startsWith("com.kartaguez.pocoma.")) return false;
        if (INERT_FORBIDDEN_PACKAGES.stream().anyMatch(name::contains)) return true;
        return name.contains(".runtime.") && !name.contains(".runtime.consumption.");
    }

    private static boolean isActiveSpringComponent(Class<?> type) {
        if (org.springframework.context.Lifecycle.class.isAssignableFrom(type)) return true;
        if (org.springframework.context.ApplicationListener.class.isAssignableFrom(type)) {
            return true;
        }
        return Arrays.stream(type.getMethods()).anyMatch(BootstrapGuard::hasActiveSpringAnnotation);
    }

    private static boolean hasActiveSpringAnnotation(java.lang.reflect.Method method) {
        return method.isAnnotationPresent(org.springframework.scheduling.annotation.Scheduled.class)
                || method.isAnnotationPresent(
                        org.springframework.context.event.EventListener.class);
    }

    private static void require(boolean valid, String detail) {
        if (!valid) throw new IllegalStateException("L2 pilot refused: " + detail);
    }
}
