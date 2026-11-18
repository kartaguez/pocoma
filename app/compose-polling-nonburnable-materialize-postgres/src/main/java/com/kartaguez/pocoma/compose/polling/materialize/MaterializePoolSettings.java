package com.kartaguez.pocoma.compose.polling.materialize;

import java.net.URI;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;

/** Technical PostgreSQL pool, endpoint, and physical dev-target configuration for Materialize. */
public record MaterializePoolSettings(
        MaterializeSettings.Pool sourceRead,
        MaterializeSettings.Pool readBusiness,
        MaterializeSettings.Pool readControl,
        List<MaterializeSettings.DevTarget> allowedTargets) {

    public MaterializePoolSettings {
        allowedTargets = List.copyOf(allowedTargets);
    }

    static MaterializePoolSettings from(Environment env, int workerLimit) {
        var allowed = parseDevTargets(env);
        var wb = pool(env, "write-source-read", true, workerLimit);
        var rb = pool(env, "read-business", true, workerLimit);
        var rc = pool(env, "read-control", false, workerLimit);
        verifyReadAndWriteTargets(wb, rb, rc);
        return new MaterializePoolSettings(wb, rb, rc, allowed);
    }

    private static List<MaterializeSettings.DevTarget> parseDevTargets(Environment env) {
        var allowed = new ArrayList<MaterializeSettings.DevTarget>();
        for (var tuple : env.getRequiredProperty(MaterializeSettings.PREFIX + "dev-targets").split(";")) {
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

    private static MaterializeSettings.DevTarget parseDevTargetTuple(String tuple) {
        var v = tuple.split(",", -1);
        if (v.length != 7) {
            throw new IllegalArgumentException("dev-target tuple requires seven fields");
        }
        return new MaterializeSettings.DevTarget(
                v[0],
                Integer.parseInt(v[1]),
                v[2],
                v[3],
                v[4],
                v[5],
                Integer.parseInt(v[6]));
    }

    private static void verifyReadAndWriteTargets(
            MaterializeSettings.Pool wb,
            MaterializeSettings.Pool rb,
            MaterializeSettings.Pool rc) {
        if (!sameAuthority(rb, rc)) {
            throw new IllegalArgumentException(
                    "READ Business/Control targets must match and source WRITE must differ");
        }
        if (wb.database().equals(rb.database())
                && endpoint(wb).getAuthority().equals(endpoint(rb).getAuthority())) {
            throw new IllegalArgumentException(
                    "READ Business/Control targets must match and source WRITE must differ");
        }
    }

    private static MaterializeSettings.Pool pool(
            Environment env, String key, boolean business, int workerLimit) {
        String p = MaterializeSettings.PREFIX + key + ".";
        String authority = key.startsWith("write") ? "write" : "read";
        String db = env.getRequiredProperty("pocoma.v3.pilot." + authority + ".database");
        String schema = env.getRequiredProperty("pocoma.v3.pilot." + authority + ".schema");
        String role =
                key.equals("write-source-read")
                        ? env.getRequiredProperty(p + "username")
                        : env.getRequiredProperty("pocoma.v3.pilot." + authority + ".username");
        if (env.containsProperty(p + "maximum")) {
            throw new IllegalArgumentException("use maximum-pool-size, not maximum: " + key);
        }
        int defaultMax = workerLimit + (business ? 2 : 6);
        int max = env.getProperty(p + "maximum-pool-size", Integer.class, defaultMax);
        int minIdle = env.getProperty(p + "minimum-idle", Integer.class, max);
        var pool =
                new MaterializeSettings.Pool(
                        key,
                        env.getRequiredProperty(p + "url"),
                        env.getRequiredProperty(p + "username"),
                        env.getRequiredProperty(p + "password"),
                        db,
                        schema,
                        max,
                        minIdle,
                        business);
        verifyPoolSizingAndTarget(pool, role, defaultMax);
        return pool;
    }

    private static void verifyPoolSizingAndTarget(
            MaterializeSettings.Pool pool, String role, int defaultMax) {
        if (!pool.username().equals(role) || pool.maximum() != defaultMax) {
            throw new IllegalArgumentException("unqualified pool sizing or runtime principal");
        }
        if (pool.minimumIdle() != pool.maximum()) {
            throw new IllegalArgumentException("unqualified pool sizing or runtime principal");
        }
        var uri = endpoint(pool);
        boolean validUri = hasValidUriShape(uri, pool.database(), pool.schema());
        boolean validIds = hasValidIdentifiers(pool.database(), pool.schema(), role);
        if (!validUri || !validIds) {
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

    private static URI endpoint(MaterializeSettings.Pool pool) {
        if (!pool.url().startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("PostgreSQL TCP required");
        }
        return URI.create(pool.url().substring(5));
    }

    private static boolean sameAuthority(
            MaterializeSettings.Pool a, MaterializeSettings.Pool b) {
        return a.url().equals(b.url())
                && a.username().equals(b.username())
                && a.password().equals(b.password());
    }

    public void validateTarget(MaterializeSettings.Pool pool, DataSource source) {
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
                        new MaterializeSettings.DevTarget(
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
            MaterializeSettings.Pool pool, MaterializeSettings.DevTarget actual) {
        if (!allowedTargets.contains(actual)) return false;
        if (!actual.database().equals(pool.database())) return false;
        return actual.schema().equals(pool.schema()) && actual.role().equals(pool.username());
    }
}
