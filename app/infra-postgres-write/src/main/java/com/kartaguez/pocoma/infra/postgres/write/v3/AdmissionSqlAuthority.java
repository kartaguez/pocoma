package com.kartaguez.pocoma.infra.postgres.write.v3;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import javax.sql.DataSource;

/** Fail-closed effective admission ACL inventory, including PUBLIC and inherited column grants. */
public final class AdmissionSqlAuthority {
    private AdmissionSqlAuthority() {}

    private static final Set<String> PROPOSAL = Set.of("link_request_id", "initiator_issuer", "initiator_subject",
            "target_issuer", "target_subject", "binding_token", "authenticated_at", "issued_at", "principal_expires_at",
            "created_at", "expires_at", "canonical_version", "lookup_key_id", "comparison_key_id",
            "scope_key_fingerprint", "intent_fingerprint", "accepted_at", "identity_request_id");
    private static final Set<String> PROPOSAL_SELECT;
    static {
        var columns = new HashSet<>(PROPOSAL);
        columns.addAll(Set.of("admission_category", "admission_operation"));
        PROPOSAL_SELECT = Set.copyOf(columns);
    }

    private static final Map<String, Set<String>> SELECT =
            Map.ofEntries(
                    Map.entry("v3_link_proposal", PROPOSAL_SELECT),
                    Map.entry(
                            "v3_admission_association",
                            Set.of(
                                    "request_id",
                                    "category",
                                    "operation",
                                    "canonical_version",
                                    "lookup_key_id",
                                    "comparison_key_id",
                                    "scope_key_fingerprint",
                                    "intent_fingerprint",
                                    "created_at")),
                    Map.entry("v3_command", Set.of("request_id", "status", "pot_id")),
                    Map.entry(
                            "v3_identity_request",
                            Set.of(
                                    "request_id",
                                    "status",
                                    "operation",
                                    "target_issuer",
                                    "target_subject")),
                    Map.entry(
                            "v3_command_result",
                            Set.of("request_id", "outcome", "resulting_pot_version")),
                    Map.entry(
                            "v3_identity_request_result",
                            Set.of("request_id", "outcome", "target_eui_id", "target_version")),
                    Map.entry("v3_identity_event", Set.of("request_id")),
                    Map.entry(
                            "v3_identity_completion_event",
                            Set.of("event_id", "subject_type", "subject_id")),
                    Map.entry("v3_event_position", Set.of("event_id")),
                    Map.entry("consumption_slot", Set.of("consumption_id", "consumable_id")),
                    Map.entry("v3_pocoma_user", Set.of("created_request_id")),
                    Map.entry("v3_eui_version", Set.of("request_id")),
                    Map.entry("v3_request_counter", Set.of("family", "position")),
                    Map.entry(
                            "v3_request_position",
                            Set.of("family", "position", "request_id")));
    private static final Map<String, Set<String>> INSERT =
            Map.of(
                    "v3_link_proposal", PROPOSAL,
                    "v3_admission_association",
                            Set.of(
                                    "request_id",
                                    "category",
                                    "operation",
                                    "canonical_version",
                                    "lookup_key_id",
                                    "comparison_key_id",
                                    "scope_key_fingerprint",
                                    "intent_fingerprint",
                                    "created_at",
                                    "command_id",
                                    "identity_request_id"),
                    "v3_command",
                            Set.of(
                                    "request_id",
                                    "requester_issuer",
                                    "requester_subject",
                                    "binding_token",
                                    "pot_id",
                                    "observed_version",
                                    "operation",
                                    "payload"),
                    "v3_identity_request",
                            Set.of(
                                    "request_id",
                                    "requester_issuer",
                                    "requester_subject",
                                    "operation",
                                    "binding_token",
                                    "target_issuer",
                                    "target_subject",
                                    "admission_context",
                                    "link_proposal_id",
                                    "created_at"),
                    "v3_request_position",
                            Set.of("family", "position", "request_id"));

    public static void verify(DataSource source, String database, String schema, String role) {
        var sql = new JdbcTemplate(source);
        var who =
                sql.queryForMap(
                        "select current_database() db,current_schema() schema,current_user role");
        require(
                database.equals(who.get("db"))
                        && schema.equals(who.get("schema"))
                        && role.equals(who.get("role"))
                        && !schema.equals("public"));
        require(
                Boolean.TRUE.equals(
                        sql.queryForObject(
                                "select not(rolsuper or rolcreatedb or rolcreaterole or"
                                        + " rolreplication or rolbypassrls) and rolcanlogin from"
                                        + " pg_roles where rolname=current_user",
                                Boolean.class)));
        require(
                sql.queryForObject(
                                "select count(*) from pg_roles where rolname<>current_user and"
                                        + " pg_has_role(current_user,oid,'MEMBER')",
                                Long.class)
                        == 0);
        require(
                !Boolean.TRUE.equals(
                        sql.queryForObject(
                                "select"
                                    + " has_schema_privilege(current_user,current_schema(),'CREATE')",
                                Boolean.class)));
        require(
                sql.queryForObject(
                                "select count(*) from pg_proc p join pg_namespace n on"
                                    + " n.oid=p.pronamespace where n.nspname not like 'pg_%' and"
                                    + " n.nspname<>'information_schema' and p.prosecdef and"
                                    + " has_function_privilege(current_user,p.oid,'EXECUTE')",
                                Long.class)
                        == 0);
        var logging =
                Map.of(
                        "log_statement",
                        "none",
                        "log_duration",
                        "off",
                        "log_min_duration_statement",
                        "-1",
                        "log_min_duration_sample",
                        "-1",
                        "log_transaction_sample_rate",
                        "0",
                        "log_parameter_max_length",
                        "0",
                        "log_parameter_max_length_on_error",
                        "0",
                        "log_min_error_statement",
                        "panic",
                        "log_error_verbosity",
                        "terse",
                        "log_min_messages",
                        "warning");
        var observed = new HashMap<String, String>();
        sql.queryForList(
                        "select name,setting from pg_settings where name in"
                            + " ('log_statement','log_duration','log_min_duration_statement','log_min_duration_sample','log_transaction_sample_rate','log_parameter_max_length','log_parameter_max_length_on_error','log_min_error_statement','log_error_verbosity','log_min_messages')")
                .forEach(
                        row -> observed.put((String) row.get("name"), (String) row.get("setting")));
        require(logging.equals(observed));
        var present = new HashSet<String>();
        // PostgreSQL computes effective privileges in one inventory query; no per-column round
        // trips.
        var rows =
                sql.queryForList(
                        """
select c.relname,n.nspname,c.relkind,a.attname,
  c.relowner=(select oid from pg_roles where rolname=current_user) owned,
  case when c.relkind='S' then has_sequence_privilege(current_user,c.oid,'USAGE,SELECT,UPDATE')
       else has_table_privilege(current_user,c.oid,'UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN') end forbidden,
  case when c.relkind<>'S' then has_column_privilege(current_user,c.oid,a.attnum,'SELECT') else false end can_select,
  case when c.relkind<>'S' then has_column_privilege(current_user,c.oid,a.attnum,'INSERT') else false end can_insert,
  case when c.relkind<>'S' then has_column_privilege(current_user,c.oid,a.attnum,'REFERENCES') else false end can_reference,
  case when c.relkind<>'S' then has_column_privilege(current_user,c.oid,a.attnum,'UPDATE') else false end can_update
from pg_class c join pg_namespace n on n.oid=c.relnamespace
  join pg_attribute a on a.attrelid=c.oid and a.attnum>0 and not a.attisdropped
where n.nspname not like 'pg_%' and n.nspname<>'information_schema'
  and c.relkind in ('r','p','v','m','f','S')
""");
        for (var r : rows) {
            String table = (String) r.get("relname"), column = (String) r.get("attname");
            boolean local = schema.equals(r.get("nspname"));
            require(
                    !Boolean.TRUE.equals(r.get("owned"))
                            && !Boolean.TRUE.equals(r.get("forbidden"))
                            && !Boolean.TRUE.equals(r.get("can_reference")));
            if (local) present.add(table);
            boolean select = local && SELECT.getOrDefault(table, Set.of()).contains(column);
            boolean insert = local && INSERT.getOrDefault(table, Set.of()).contains(column);
            boolean update =
                    local
                            && ((table.equals("v3_link_proposal")
                                            && Set.of("accepted_at", "identity_request_id")
                                                    .contains(column))
                                    || (table.equals("v3_request_counter")
                                            && column.equals("position")));
            require(
                    Boolean.TRUE.equals(r.get("can_select")) == select
                            && Boolean.TRUE.equals(r.get("can_insert")) == insert
                            && Boolean.TRUE.equals(r.get("can_update")) == update);
        }
        require(present.containsAll(SELECT.keySet()) && present.containsAll(INSERT.keySet()));
    }

    private static void require(boolean value) {
        if (!value) throw new IllegalStateException("Unsafe effective admission SQL authority");
    }
}
