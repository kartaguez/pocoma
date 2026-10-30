package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition.Authority;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.*;
import java.util.*;

import javax.sql.DataSource;

/** Reads only: refusal is the only response to any incomplete or incompatible authority. */
public final class V3BootstrapGuard {
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

    private final boolean development;
    private final boolean technicalProbes;
    private final boolean fixtureIdentity;
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

        static Target from(Environment env, String authority) {
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

    public V3BootstrapGuard(Target write, Target read, List<ConsumptionDefinition> definitions) {
        this(write, read, definitions, false, false, false);
    }

    private V3BootstrapGuard(
            Target write,
            Target read,
            List<ConsumptionDefinition> definitions,
            boolean development,
            boolean probes,
            boolean fixture) {
        this.write = Objects.requireNonNull(write);
        this.read = Objects.requireNonNull(read);
        this.definitions = List.copyOf(definitions);
        this.development = development;
        this.technicalProbes = probes;
        this.fixtureIdentity = fixture;
        if (!development) {
            if (definitions.size() != 9
                    || definitions.stream().map(ConsumptionDefinition::id).distinct().count() != 9)
                throw new IllegalArgumentException("nine unique pilot identities required");
        } else {
            var expected = new ArrayList<>(V3ConsumptionCatalogConfiguration.definitions());
            if (probes) expected.addAll(V3ConsumptionWorkerSettings.probeDefinitions());
            if (definitions.size() != expected.size()
                    || !new HashSet<>(definitions).equals(new HashSet<>(expected)))
                throw new IllegalArgumentException("exact business/technical tuples required");
        }
    }

    public static V3BootstrapGuard development(
            Target write,
            Target read,
            List<ConsumptionDefinition> definitions,
            boolean probes,
            boolean fixture) {
        return new V3BootstrapGuard(write, read, definitions, true, probes, fixture);
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
        try {
            String w = inspect(write, Authority.WRITE, profile);
            String r = inspect(read, Authority.READ, profile);
            if (w.equals(r))
                throw new IllegalStateException("WRITE and READ share a physical database");
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

    private String inspect(Target target, Authority authority, Privileges profile)
            throws SQLException {
        try (var c = target.source().getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try (var s = c.createStatement()) {
                try (var rs =
                        s.executeQuery(
                                "select current_database(),current_schema(),current_user,"
                                        + "inet_server_addr()::text,inet_server_port()")) {
                    rs.next();
                    require(
                            target.database().equals(rs.getString(1))
                                    && target.schema().equals(rs.getString(2))
                                    && target.runtimeRole().equals(rs.getString(3)),
                            "wrong database/schema/runtime role");
                }
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
                    require(
                            !rs.getBoolean(1) && rs.getString(2) == null,
                            "DDL privilege or legacy Command slot");
                }
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
                        require(
                                definition != null
                                        && definition.family().name().equals(rs.getString(2))
                                        && definition.routingVersion().equals(rs.getString(3))
                                        && definition.partitionCount() == rs.getInt(4),
                                "routing contract mismatch");
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
                    require(
                            rs.getInt(1)
                                    == definitions.stream()
                                                    .filter(d -> d.authority() == authority)
                                                    .count()
                                            * 4,
                            "extra ownership");
                }
                if (development) inspectDevelopment(s, authority);
                // All table rights are effective rights: PUBLIC and inherited roles included.
                if (profile != null) inspectLifecycle(s, profile);
                if (profile == Privileges.LIFECYCLE_AND_DISCOVERY && authority == Authority.WRITE)
                    inspectDiscoveryWrite(s);
                for (String table : profile != null ? List.<String>of() : TABLES) {
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
                if (authority == Authority.WRITE) {
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
                                        + " pg_get_functiondef('v3_require_command_unit()'::regprocedure)")) {
                        rs.next();
                        require(
                                rs.getString(1)
                                        .contains(
                                                "s.consumption_id='"
                                                        + command
                                                        + "'::uuid and"
                                                        + " s.consumable_id=c.request_id"),
                                "canonical Command trigger mismatch");
                    }
                }
                // Independent fixed ordinary UUID catches a replaced SQL hash function at startup.
                for (var family : ConsumableFamily.values()) {
                    var id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
                    try (var q = c.prepareStatement("select consumption_hash_v1(?,?)")) {
                        q.setString(1, family.name());
                        q.setObject(2, id);
                        try (var rs = q.executeQuery()) {
                            rs.next();
                            require(
                                    rs.getInt(1) == RoutingV1.hash(family, id),
                                    "SQL hash mismatch");
                        }
                    }
                }
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
            } finally {
                c.rollback();
            }
        }
    }

    private static void inspectLifecycle(Statement s, Privileges profile) throws SQLException {
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
        for (String table : TABLES) {
            var columns =
                    table.equals("partition_ownership")
                            ? Set.of("owner_token", "owned_until")
                            : table.equals("consumption_slot")
                                    ? Set.of(
                                            "claim_token",
                                            "claimed_until",
                                            "retry_after",
                                            "attempt_count",
                                            "state")
                                    : table.equals("discovery_checkpoint")
                                                    && profile == Privileges.LIFECYCLE_AND_DISCOVERY
                                            ? Set.of("position")
                                            : Set.<String>of();
            var insertColumns =
                    profile != Privileges.LIFECYCLE_AND_DISCOVERY
                            ? Set.<String>of()
                            : table.equals("consumption_slot")
                                    ? Set.of(
                                            "consumption_id",
                                            "consumable_id",
                                            "partition_hash",
                                            "partition_id")
                                    : table.equals("discovery_checkpoint")
                                            ? Set.of("consumption_id", "partition_id", "position")
                                            : Set.<String>of();
            try (var rs =
                    s.executeQuery(
                            "select has_table_privilege('"
                                    + table
                                    + "','SELECT'),"
                                    + "has_table_privilege('"
                                    + table
                                    + "','INSERT,UPDATE,TRUNCATE,REFERENCES,TRIGGER'),"
                                    + "has_table_privilege('"
                                    + table
                                    + "','DELETE'),pg_has_role(current_user,(select tableowner from"
                                    + " pg_tables where schemaname=current_schema() and tablename='"
                                    + table
                                    + "'),'MEMBER')")) {
                rs.next();
                require(
                        rs.getBoolean(1)
                                && !rs.getBoolean(2)
                                && !rs.getBoolean(4)
                                && rs.getBoolean(3) == table.equals("consumption_slot"),
                        "unsafe lifecycle table rights: " + table);
            }
            try (var rs =
                    s.executeQuery(
                            "select column_name,has_column_privilege('"
                                    + table
                                    + "',column_name,'UPDATE'),"
                                    + "has_column_privilege('"
                                    + table
                                    + "',column_name,'INSERT'),has_column_privilege('"
                                    + table
                                    + "',column_name,'REFERENCES') from"
                                    + " information_schema.columns where"
                                    + " table_schema=current_schema() and table_name='"
                                    + table
                                    + "'")) {
                while (rs.next())
                    require(
                            rs.getBoolean(2) == columns.contains(rs.getString(1))
                                    && rs.getBoolean(3) == insertColumns.contains(rs.getString(1))
                                    && !rs.getBoolean(4),
                            "unsafe lifecycle column rights: " + table + "." + rs.getString(1));
            }
        }
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
        if (fixtureIdentity)
            throw new IllegalStateException("Real Identity refuses fixture authority");
        validateLifecycleSource(source, Authority.WRITE, Privileges.LIFECYCLE_AND_DISCOVERY);
        try (var c = source.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (var statement = c.createStatement()) {
                inspectIdentity(statement);
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
                require(
                        !read.runtimeRole().equals(write.runtimeRole()),
                        "distinct READ/WRITE principals required for real DEV");
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
                        query.setString(parameter, read.runtimeRole());
                    try (var rows = query.executeQuery()) {
                        rows.next();
                        require(
                                !rows.getBoolean(1),
                                "Identity indirect exposure or unsafe READ principal on WRITE");
                    }
                }
                for (String table : IDENTITY_TABLES) {
                    try (var rows =
                            statement.executeQuery(
                                    "select exists(select 1 from pg_roles where rolname='"
                                            + read.runtimeRole()
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
            } finally {
                c.rollback();
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Identity V6 authority refused");
        }
    }

    private static void inspectIdentity(Statement statement) throws SQLException {
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
        for (String table : IDENTITY_TABLES) {
            boolean insert = !table.equals("v3_identity_request");
            try (var rows =
                    statement.executeQuery(
                            "select has_table_privilege('"
                                    + table
                                    + "','SELECT'),has_table_privilege('"
                                    + table
                                    + "','INSERT'),"
                                    + "has_table_privilege('"
                                    + table
                                    + "','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'),pg_has_role(current_user,(select"
                                    + " relowner from pg_class where oid='"
                                    + table
                                    + "'::regclass),'MEMBER')")) {
                rows.next();
                require(
                        rows.getBoolean(1)
                                && rows.getBoolean(2) == insert
                                && !rows.getBoolean(3)
                                && !rows.getBoolean(4),
                        "unsafe Identity table rights: " + table);
            }
            try (var rows =
                    statement.executeQuery(
                            "select column_name,has_column_privilege('"
                                    + table
                                    + "',column_name,'INSERT'),"
                                    + "has_column_privilege('"
                                    + table
                                    + "',column_name,'UPDATE'),has_column_privilege('"
                                    + table
                                    + "',column_name,'REFERENCES') from information_schema.columns"
                                    + " where table_schema=current_schema() and table_name='"
                                    + table
                                    + "'")) {
                while (rows.next()) {
                    boolean update =
                            table.equals("v3_identity_request")
                                            && rows.getString(1).equals("status")
                                    || table.equals("v3_eui_authority")
                                            && rows.getString(1).equals("current_version");
                    require(
                            rows.getBoolean(2) == insert
                                    && rows.getBoolean(3) == update
                                    && !rows.getBoolean(4),
                            "unsafe Identity column rights");
                }
            }
        }
    }

    private static void inspectDiscoveryWrite(Statement statement) throws SQLException {
        var insertTables =
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
        var tables = new HashSet<>(insertTables);
        tables.addAll(Set.of("v3_command", "v3_event_counter"));
        var history =
                Set.of(
                        "v3_pot_header",
                        "v3_pot_details",
                        "v3_shareholder_header",
                        "v3_shareholder_details",
                        "v3_shareholder_weight",
                        "v3_expense_header",
                        "v3_expense_details",
                        "v3_expense_financials");
        for (String table : tables) {
            try (var row =
                    statement.executeQuery(
                            "select has_table_privilege('"
                                    + table
                                    + "','SELECT'),has_table_privilege('"
                                    + table
                                    + "','INSERT'),has_table_privilege('"
                                    + table
                                    + "','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER'),pg_has_role(current_user,(select"
                                    + " tableowner from pg_tables where schemaname=current_schema()"
                                    + " and tablename='"
                                    + table
                                    + "'),'MEMBER')")) {
                row.next();
                require(
                        row.getBoolean(1)
                                && row.getBoolean(2) == insertTables.contains(table)
                                && !row.getBoolean(3)
                                && !row.getBoolean(4),
                        "unsafe combined business table rights: " + table);
            }
            Set<String> updates =
                    history.contains(table)
                            ? Set.of("active_to")
                            : table.equals("v3_command")
                                    ? Set.of("status")
                                    : table.equals("v3_event_counter")
                                            ? Set.of("position")
                                            : table.equals("v3_pot_identity")
                                                    ? Set.of("current_version")
                                                    : Set.of();
            try (var rows =
                    statement.executeQuery(
                            "select column_name,has_column_privilege('"
                                    + table
                                    + "',column_name,'UPDATE'),has_column_privilege('"
                                    + table
                                    + "',column_name,'INSERT'),has_column_privilege('"
                                    + table
                                    + "',column_name,'REFERENCES') from information_schema.columns"
                                    + " where table_schema=current_schema() and table_name='"
                                    + table
                                    + "'")) {
                while (rows.next())
                    require(
                            rows.getBoolean(2) == updates.contains(rows.getString(1))
                                    && rows.getBoolean(3) == insertTables.contains(table)
                                    && !rows.getBoolean(4),
                            "unsafe combined business column rights: "
                                    + table
                                    + "."
                                    + rows.getString(1));
            }
        }
        try (var rows =
                statement.executeQuery(
                        "select exists(select 1 from pg_proc where pronamespace=(select oid from"
                                + " pg_namespace where nspname=current_schema()) and prosecdef)")) {
            rows.next();
            require(!rows.getBoolean(1), "SECURITY DEFINER in combined authority");
        }
    }

    private void inspectDevelopment(Statement s, Authority authority) throws SQLException {
        try (var row =
                s.executeQuery(
                        "select"
                            + " has_database_privilege(current_database(),'CREATE,TEMP'),has_schema_privilege('public','CREATE')")) {
            row.next();
            require(
                    !row.getBoolean(1) && !row.getBoolean(2),
                    "runtime database/public-schema DDL privilege");
        }
        String fixture =
                authority == Authority.WRITE ? "i5_fixture_identity" : "i5_read_probe_effect";
        boolean enabled = authority == Authority.WRITE ? fixtureIdentity : technicalProbes;
        try (var row = s.executeQuery("select to_regclass('" + fixture + "') is not null")) {
            row.next();
            require(
                    row.getBoolean(1) == enabled,
                    "fixture installation differs from explicit mode");
        }
        if (enabled) {
            try (var row =
                    s.executeQuery(
                            "select has_table_privilege('"
                                    + fixture
                                    + "','SELECT'),has_table_privilege('"
                                    + fixture
                                    + "','INSERT'),has_table_privilege('"
                                    + fixture
                                    + "','UPDATE,DELETE,TRUNCATE,TRIGGER,REFERENCES')")) {
                row.next();
                require(
                        row.getBoolean(1)
                                && row.getBoolean(2) == (authority == Authority.READ)
                                && !row.getBoolean(3),
                        "unsafe fixture table rights");
            }
            try (var rows =
                    s.executeQuery(
                            "select column_name,has_column_privilege('"
                                    + fixture
                                    + "',column_name,'INSERT'),has_column_privilege('"
                                    + fixture
                                    + "',column_name,'UPDATE'),has_column_privilege('"
                                    + fixture
                                    + "',column_name,'REFERENCES') from information_schema.columns"
                                    + " where table_schema=current_schema() and table_name='"
                                    + fixture
                                    + "'")) {
                while (rows.next())
                    require(
                            rows.getBoolean(2) == (authority == Authority.READ)
                                    && rows.getBoolean(3)
                                            == (authority == Authority.WRITE
                                                    && rows.getString(1).equals("revision"))
                                    && !rows.getBoolean(4),
                            "unsafe fixture column rights");
            }
        }
        // Effective grants on any unlisted object would introduce another borrower/writer.
        try (var rows =
                s.executeQuery(
                        "select"
                            + " tablename,has_table_privilege(quote_ident(schemaname)||'.'||quote_ident(tablename),'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')"
                            + " from pg_tables where schemaname=current_schema()")) {
            var writeTables =
                    Set.of(
                            "v3_command",
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
                            "v3_event_position",
                            "v3_event_counter");
            while (rows.next()) {
                String table = rows.getString(1);
                if (TABLES.contains(table)
                        || (enabled && table.equals(fixture))
                        || (authority == Authority.WRITE
                                && (writeTables.contains(table)
                                        || IDENTITY_TABLES.contains(table)))) continue;
                require(!rows.getBoolean(2), "unallocated runtime table privileges");
            }
        }
    }

    static void requireInertType(Class<?> type) {
        String name = type.getName();
        if (name.startsWith("com.kartaguez.pocoma.")
                && (name.contains(".infra.")
                        || name.contains(".engine.")
                        || name.contains(".orchestrator.")
                        || name.contains(".supra.")
                        || (name.contains(".runtime.")
                                && !name.contains(".runtime.consumption.v3."))
                        || name.contains("$Legacy")))
            throw new IllegalStateException("incompatible I1 pilot component: " + name);
        if (org.springframework.boot.ApplicationRunner.class.isAssignableFrom(type)
                || org.springframework.boot.CommandLineRunner.class.isAssignableFrom(type))
            throw new IllegalStateException("runner forbidden in I1 pilot: " + name);
        if (name.startsWith("com.kartaguez.pocoma.")
                && (org.springframework.context.Lifecycle.class.isAssignableFrom(type)
                        || org.springframework.context.ApplicationListener.class.isAssignableFrom(
                                type)
                        || Arrays.stream(type.getMethods())
                                .anyMatch(
                                        m ->
                                                m.isAnnotationPresent(
                                                                org.springframework.scheduling
                                                                        .annotation.Scheduled.class)
                                                        || m.isAnnotationPresent(
                                                                org.springframework.context.event
                                                                        .EventListener.class))))
            throw new IllegalStateException("active I1 pilot component: " + name);
    }

    private static void require(boolean valid, String detail) {
        if (!valid) throw new IllegalStateException("L2 pilot refused: " + detail);
    }
}
