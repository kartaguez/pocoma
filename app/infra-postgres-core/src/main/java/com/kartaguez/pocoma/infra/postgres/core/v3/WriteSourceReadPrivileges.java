package com.kartaguez.pocoma.infra.postgres.core.v3;
import java.sql.*;
import java.util.*;
/** Effective authority check; no credential or mutation store is created by this guard. */
public final class WriteSourceReadPrivileges {
    private WriteSourceReadPrivileges() {}
    private static final Set<String> TABLES=Set.of("v3_command","v3_command_result","v3_command_completion_event",
        "v3_identity_request","v3_identity_request_result","v3_identity_completion_event","v3_eui_authority","v3_eui_version","v3_pocoma_user","v3_identity_event",
        "v3_pot_identity","v3_pot_version","v3_pot_header","v3_pot_details","v3_pot_event","v3_shareholder_identity","v3_shareholder_header","v3_shareholder_details","v3_shareholder_weight",
        "v3_expense_identity","v3_expense_header","v3_expense_details","v3_expense_financials","v3_expense_participant","v3_invitation","v3_invitation_event","v3_event_counter","v3_event_position");
    public static void validate(V3BootstrapGuard.Target target) {
        try(var c=target.source().getConnection()) {
            c.setReadOnly(true);c.setAutoCommit(false);c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            try(var s=c.createStatement()) {
                s.setQueryTimeout(2);
                try(var row=s.executeQuery("select current_database(),current_schema(),current_user")) {
                    row.next();require(target.database().equals(row.getString(1)) && target.schema().equals(row.getString(2)) && target.runtimeRole().equals(row.getString(3)),"wrong source identity");
                }
                inspectRole(s,target.runtimeRole());
            } finally {c.rollback();}
        } catch(SQLException failure) {throw new IllegalStateException("WRITE source SELECT authority refused");}
    }
    /** Process can inspect a role's rights without opening READ or possessing source credentials. */
    public static void inspectRole(Statement s,String role) throws SQLException {
        if(role==null || !role.matches("[a-z][a-z0-9_]*"))throw new IllegalArgumentException("Explicit source role required");
        String roles="with roles as (select oid,rolsuper,rolcreaterole,rolcreatedb,rolreplication,rolbypassrls from pg_roles where rolname='"+role+"' or pg_has_role('"+role+"',oid,'MEMBER')) ";
        check(s,roles+"select exists(select 1 from roles where rolsuper or rolcreaterole or rolcreatedb or rolreplication or rolbypassrls) or exists(select 1 from roles r where has_database_privilege(r.oid,current_database(),'CREATE,TEMP')) or exists(select 1 from roles r cross join pg_namespace n where n.nspname not like 'pg_%' and n.nspname<>'information_schema' and has_schema_privilege(r.oid,n.oid,'CREATE'))","privileged role or inherited DDL");
        try(var rows=s.executeQuery(roles+"select n.nspname,c.relname,c.relkind,bool_or(has_table_privilege(r.oid,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') or has_any_column_privilege(r.oid,c.oid,'INSERT,UPDATE,REFERENCES') or pg_has_role(r.oid,c.relowner,'MEMBER')) as mutates,bool_or(has_table_privilege(r.oid,c.oid,'SELECT') or has_any_column_privilege(r.oid,c.oid,'SELECT')) as reads from pg_class c join pg_namespace n on n.oid=c.relnamespace cross join roles r where c.relkind in ('r','p','v','m','f') and n.nspname not like 'pg_%' and n.nspname<>'information_schema' group by n.nspname,c.relname,c.relkind")) {
            while(rows.next()) {
                require(!rows.getBoolean("mutates"),"source can mutate a relation");
                if(rows.getBoolean("reads"))require(TABLES.contains(rows.getString(2)) && Set.of("r","p").contains(rows.getString(3)),"unlisted read or view authority");
            }
        }
        check(s,roles+"select exists(select 1 from roles r cross join pg_class c join pg_namespace n on n.oid=c.relnamespace where c.relkind='S' and n.nspname not like 'pg_%' and (has_sequence_privilege(r.oid,c.oid,'SELECT,USAGE,UPDATE') or pg_has_role(r.oid,c.relowner,'MEMBER'))) or exists(select 1 from roles r cross join pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname not like 'pg_%' and n.nspname<>'information_schema' and p.prosecdef and has_function_privilege(r.oid,p.oid,'EXECUTE'))","sequence or SECURITY DEFINER authority");
    }
    private static void check(Statement s,String sql,String reason)throws SQLException {
        try(var row=s.executeQuery(sql)){row.next();require(!row.getBoolean(1),reason);}
    }
    private static void require(boolean ok,String reason){if(!ok)throw new IllegalStateException("WRITE source SELECT authority refused: "+reason);}
}
