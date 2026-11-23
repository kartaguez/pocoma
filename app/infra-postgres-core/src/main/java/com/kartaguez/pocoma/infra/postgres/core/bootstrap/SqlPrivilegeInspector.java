package com.kartaguez.pocoma.infra.postgres.core.bootstrap;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.function.Predicate;

/** Package-private SQL table/column privilege and ACL verification primitives for BootstrapGuard. */
final class SqlPrivilegeInspector {
    private SqlPrivilegeInspector() {}

    record TableRightsSpec(boolean allowInsert, boolean allowDelete, String ownerQuery) {}

    record ColumnRightsSpec(
            Predicate<String> allowInsert,
            Predicate<String> allowUpdate,
            String errorDetailPrefix) {}

    static void require(boolean valid, String detail) {
        if (!valid) throw new IllegalStateException("L2 pilot refused: " + detail);
    }

    static boolean tableExists(Statement statement, String table) throws SQLException {
        try (var rows = statement.executeQuery("select to_regclass('" + table + "') is not null")) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    static String pgTablesOwnerQuery(String table) {
        return "select tableowner from pg_tables where schemaname=current_schema() and tablename='"
                + table
                + "'";
    }

    static void verifyTableRights(
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

    static void verifyInformationSchemaColumns(
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

    static void verifyReadBusinessColumnRights(
            Statement s, String table, Set<String> updatableColumns, boolean allowInsert)
            throws SQLException {
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
                                && rows.getBoolean(3) == allowInsert
                                && rows.getBoolean(4)
                                        == updatableColumns.contains(rows.getString(1))
                                && !rows.getBoolean(5);
                require(
                        valid,
                        "unsafe READ business column rights: " + table + "." + rows.getString(1));
            }
        }
    }

    static void verifyNoPublicAcl(Statement s, String table, String message) throws SQLException {
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
}
