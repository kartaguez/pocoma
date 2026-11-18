package com.kartaguez.pocoma.compose.polling.process;

import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;

/** Technical PostgreSQL pool, endpoint, and physical dev-target configuration for Process. */
public record ProcessPoolSettings(
        ProcessSettings.Pool writeBusiness,
        ProcessSettings.Pool writeControl,
        List<ProcessSettings.DevTarget> allowedTargets) {

    public ProcessPoolSettings {
        allowedTargets = List.copyOf(allowedTargets);
    }

    static ProcessPoolSettings from(Environment env, int workerLimit) {
        var allowed = parseDevTargets(env);
        var wb = pool(env, "write-business", true, workerLimit);
        var wc = pool(env, "write-control", false, workerLimit);
        if (!sameAuthority(wb, wc)) {
            throw new IllegalArgumentException("WRITE Business/Control targets must match");
        }
        return new ProcessPoolSettings(wb, wc, allowed);
    }

    private static List<ProcessSettings.DevTarget> parseDevTargets(Environment env) {
        var allowed = new ArrayList<ProcessSettings.DevTarget>();
        for (var tuple : env.getRequiredProperty(ProcessSettings.PREFIX + "dev-targets").split(";")) {
            allowed.add(parseDevTargetTuple(tuple));
        }
        if (allowed.isEmpty() || allowed.size() > 2) {
            throw new IllegalArgumentException("two exact dev targets required");
        }
        if (new HashSet<>(allowed).size() != allowed.size()) {
            throw new IllegalArgumentException("two exact dev targets required");
        }
        return allowed;
    }

    private static ProcessSettings.DevTarget parseDevTargetTuple(String tuple) {
        var v = tuple.split(",", -1);
        if (v.length != 7) {
            throw new IllegalArgumentException("dev-target tuple requires seven fields");
        }
        return new ProcessSettings.DevTarget(
                v[0],
                Integer.parseInt(v[1]),
                v[2],
                v[3],
                v[4],
                v[5],
                Integer.parseInt(v[6]));
    }

    private static ProcessSettings.Pool pool(
            Environment env, String key, boolean business, int workerLimit) {
        String p = ProcessSettings.PREFIX + key + ".";
        String authority = key.startsWith("write") ? "write" : "read";
        String db = env.getRequiredProperty("pocoma.v3.pilot." + authority + ".database");
        String schema = env.getRequiredProperty("pocoma.v3.pilot." + authority + ".schema");
        String role = resolvePoolRole(env, key, p, authority);
        if (env.containsProperty(p + "maximum")) {
            throw new IllegalArgumentException("use maximum-pool-size, not maximum: " + key);
        }
        int defaultMax = workerLimit + (business ? 2 : 6);
        int max = env.getProperty(p + "maximum-pool-size", Integer.class, defaultMax);
        int minIdle = env.getProperty(p + "minimum-idle", Integer.class, max);
        var pool =
                new ProcessSettings.Pool(
                        key,
                        env.getRequiredProperty(p + "url"),
                        env.getRequiredProperty(p + "username"),
                        env.getRequiredProperty(p + "password"),
                        db,
                        schema,
                        max,
                        minIdle,
                        business);
        validatePoolSizing(pool, role, defaultMax);
        validatePoolEndpoint(endpoint(pool), db, schema, role);
        return pool;
    }

    private static String resolvePoolRole(
            Environment env, String key, String prefix, String authority) {
        if (key.equals("write-source-read")) return env.getRequiredProperty(prefix + "username");
        return env.getRequiredProperty("pocoma.v3.pilot." + authority + ".username");
    }

    private static void validatePoolSizing(
            ProcessSettings.Pool pool, String role, int defaultMax) {
        if (!pool.username().equals(role) || pool.maximum() != defaultMax) {
            throw new IllegalArgumentException("unqualified pool sizing or runtime principal");
        }
        if (pool.minimumIdle() != pool.maximum()) {
            throw new IllegalArgumentException("unqualified pool sizing or runtime principal");
        }
    }

    private static void validatePoolEndpoint(URI uri, String db, String schema, String role) {
        if (!hasValidUriShape(uri, db, schema) || !hasValidIdentifiers(db, schema, role)) {
            throw new IllegalArgumentException("explicit single TCP target and schema required");
        }
    }

    private static boolean hasValidUriShape(URI uri, String db, String schema) {
        if (uri.getHost() == null || uri.getPort() < 1) return false;
        if (uri.getUserInfo() != null || uri.getFragment() != null) return false;
        if (!uri.getPath().equals("/" + db)) return false;
        return ("currentSchema=" + schema).equals(uri.getQuery());
    }

    private static boolean hasValidIdentifiers(String db, String schema, String role) {
        if (schema.equals("public")) return false;
        return isPgIdentifier(db) && isPgIdentifier(schema) && isPgIdentifier(role);
    }

    private static boolean isPgIdentifier(String value) {
        return value.matches("[a-z][a-z0-9_]*");
    }

    private static URI endpoint(ProcessSettings.Pool pool) {
        if (!pool.url().startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("PostgreSQL TCP required");
        }
        return URI.create(pool.url().substring(5));
    }

    private static boolean sameAuthority(ProcessSettings.Pool a, ProcessSettings.Pool b) {
        return a.url().equals(b.url())
                && a.username().equals(b.username())
                && a.password().equals(b.password());
    }

    public void validateTarget(ProcessSettings.Pool pool, DataSource source) {
        var uri = endpoint(pool);
        try (var c = source.getConnection()) {
            c.setReadOnly(true);
            c.setAutoCommit(false);
            try (var s = c.createStatement();
                    var rows =
                            s.executeQuery(
                                    "select"
                                        + " current_database(),current_schema(),current_user,inet_server_addr()::text,inet_server_port()")) {
                if (!rows.next()) throw new IllegalStateException("missing physical identity");
                var actual =
                        new ProcessSettings.DevTarget(
                                uri.getHost(),
                                uri.getPort(),
                                rows.getString(1),
                                rows.getString(2),
                                rows.getString(3),
                                rows.getString(4),
                                rows.getInt(5));
                if (!isAllowedDevTarget(pool, actual)) {
                    throw new IllegalStateException(
                            "physical authority outside explicit dev allowlist");
                }
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("I5 target validation failed", e);
        }
    }

    private boolean isAllowedDevTarget(
            ProcessSettings.Pool pool, ProcessSettings.DevTarget actual) {
        if (!allowedTargets.contains(actual)) return false;
        if (!actual.database().equals(pool.database())) return false;
        return actual.schema().equals(pool.schema()) && actual.role().equals(pool.username());
    }
}
