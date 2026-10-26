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
        this.write = Objects.requireNonNull(write);
        this.read = Objects.requireNonNull(read);
        this.definitions = List.copyOf(definitions);
        if (definitions.size() != 9
                || definitions.stream().map(ConsumptionDefinition::id).distinct().count() != 9)
            throw new IllegalArgumentException("nine unique pilot identities required");
    }

    public void validate() {
        try {
            String w = inspect(write, Authority.WRITE);
            String r = inspect(read, Authority.READ);
            if (w.equals(r))
                throw new IllegalStateException("WRITE and READ share a physical database");
        } catch (SQLException failure) {
            throw new IllegalStateException("L2 pilot authority refused", failure);
        }
    }

    private String inspect(Target target, Authority authority) throws SQLException {
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
                            rs.getInt(1) == (authority == Authority.WRITE ? 8 : 28),
                            "extra ownership");
                }
                // All table rights are effective rights: PUBLIC and inherited roles included.
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
