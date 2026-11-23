package com.kartaguez.pocoma.infra.postgres.core.bootstrap;

import static com.kartaguez.pocoma.infra.postgres.core.bootstrap.SqlPrivilegeInspector.ColumnRightsSpec;
import static com.kartaguez.pocoma.infra.postgres.core.bootstrap.SqlPrivilegeInspector.TableRightsSpec;
import static com.kartaguez.pocoma.infra.postgres.core.bootstrap.SqlPrivilegeInspector.require;
import static com.kartaguez.pocoma.infra.postgres.core.bootstrap.SqlPrivilegeInspector.verifyInformationSchemaColumns;
import static com.kartaguez.pocoma.infra.postgres.core.bootstrap.SqlPrivilegeInspector.verifyTableRights;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** Package-private Identity authority schema, ACL, and logging policy verification. */
final class IdentityAuthorityInspector {
    static final Set<String> IDENTITY_TABLES =
            Set.of(
                    "v3_identity_request",
                    "v3_eui_authority",
                    "v3_pocoma_user",
                    "v3_eui_version",
                    "v3_identity_request_result",
                    "v3_identity_event",
                    "v3_identity_completion_event");

    private IdentityAuthorityInspector() {}

    static void inspectIdentity(Statement statement) throws SQLException {
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

    static void verifyIdentityLoggingPolicy(Statement statement) throws SQLException {
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

    static void requireNoIdentityAuthority(Connection c, Statement statement, String role)
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
            for (int parameter = 1; parameter <= 6; parameter++) {
                query.setString(parameter, role);
            }
            try (var rows = query.executeQuery()) {
                rows.next();
                require(
                        !rows.getBoolean(1),
                        "Identity indirect exposure or unsafe READ principal on WRITE");
            }
        }
        var identityAndRequestTables = new HashSet<>(IDENTITY_TABLES);
        identityAndRequestTables.addAll(Set.of("v3_request_counter", "v3_request_position"));
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
}
