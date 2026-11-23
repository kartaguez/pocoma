package com.kartaguez.pocoma.infra.postgres.core.bootstrap;

import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.domain.consumption.ConsumableFamily;
import com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Shared technical PostgreSQL pool, target validation, and property parsing for consumption workers. */
public final class WorkerPostgresSupport {
    public static final String PREFIX = "pocoma.v3.worker.";
    public static final String PROCESS_PROFILE = "v3-consumption-worker-dev";
    public static final String READ_PROFILE = "v3-read-worker";
    public static final List<String> READ_NAMES =
            List.of(
                    "MaterializePotVersion",
                    "MaterializePotBalance",
                    "MaterializePotVisibility",
                    "MaterializeCurrentBinding",
                    "MaterializeCommandResult",
                    "MaterializeIdentityRequestResult",
                    "MaterializeInvitation");

    private WorkerPostgresSupport() {}

    public record DevTarget(
            String host,
            int port,
            String database,
            String schema,
            String role,
            String serverAddress,
            int serverPort) {}

    public record PoolSpec(
            String name,
            String url,
            String username,
            String password,
            String database,
            String schema,
            int maximum,
            int minimumIdle,
            boolean business) {
        @Override
        public String toString() {
            return "Pool[" + name + ", maximum=" + maximum + "]";
        }

        public DriverManagerDataSource preflightSource() {
            var source = new DriverManagerDataSource(url, username, password);
            var properties = new Properties();
            properties.setProperty("connectTimeout", "2");
            properties.setProperty("socketTimeout", "5");
            properties.setProperty("ApplicationName", "v3-preflight");
            properties.setProperty(
                    "options",
                    "-c statement_timeout=2000 -c lock_timeout=500 -c transaction_timeout=3000");
            source.setConnectionProperties(properties);
            return source;
        }
    }

    public record AuthorityFlags(
            String mode, boolean identity, boolean command, boolean fixture) {}

    public static Environment namespaced(Environment original, String namespacePrefix) {
        var selected = new StandardEnvironment();
        selected.setActiveProfiles(original.getActiveProfiles());
        selected.getPropertySources()
                .addFirst(
                        new PropertySource<>("explicit-compose-namespace", original) {
                            @Override
                            public Object getProperty(String key) {
                                if (key.startsWith(PREFIX)
                                        && source.containsProperty(namespacePrefix + "enabled")) {
                                    return source.getProperty(
                                            namespacePrefix + key.substring(PREFIX.length()));
                                }
                                return source.getProperty(key);
                            }
                        });
        var profiles = Arrays.asList(original.getActiveProfiles());
        if (profiles.contains(PROCESS_PROFILE) && profiles.contains(READ_PROFILE)) {
            if (!original.containsProperty(namespacePrefix + "enabled")) {
                throw new IllegalStateException(
                        "Co-hosted workers require independent process/materialize namespaces");
            }
        }
        return selected;
    }

    public static boolean requested(Environment env, String requiredProfile) {
        boolean own = Arrays.asList(env.getActiveProfiles()).contains(requiredProfile);
        if (!own) return false;
        if (!flag(env, "enabled")) {
            throw new IllegalStateException("Explicit DEV authorization and targets required");
        }
        boolean devEnv = "DEV".equals(env.getRequiredProperty(PREFIX + "environment"));
        boolean hasTargets = !env.getRequiredProperty(PREFIX + "dev-targets").isBlank();
        if (!devEnv || !hasTargets) {
            throw new IllegalStateException("Explicit DEV authorization and targets required");
        }
        return true;
    }

    public static boolean flag(Environment env, String key) {
        String value = env.getRequiredProperty(PREFIX + key);
        if (!value.equals("true") && !value.equals("false")) {
            throw new IllegalArgumentException("explicit boolean required: " + key);
        }
        return value.equals("true");
    }

    public static int positive(Environment env, String key) {
        int n = Integer.parseInt(env.getRequiredProperty(PREFIX + key));
        if (n < 1) throw new IllegalArgumentException("positive bound required: " + key);
        return n;
    }

    public static String requireValidWorkerName(Environment env) {
        String name = env.getRequiredProperty(PREFIX + "worker-name");
        if (!name.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new IllegalArgumentException("invalid worker name");
        }
        return name;
    }

    public static AuthorityFlags readAuthorityFlags(Environment env, boolean fixture) {
        String mode = env.getRequiredProperty(PREFIX + "identity-authority");
        if (!Set.of("fixture", "real").contains(mode)) {
            throw new IllegalArgumentException("explicit Identity authority mode required");
        }
        boolean identity =
                env.containsProperty(PREFIX + "identity-request") && flag(env, "identity-request");
        boolean command =
                env.containsProperty(PREFIX + "command-enabled") && flag(env, "command-enabled");
        validateAuthorityCombination(mode, identity, command, fixture);
        return new AuthorityFlags(mode, identity, command, fixture);
    }

    private static void validateAuthorityCombination(
            String mode, boolean identity, boolean command, boolean fixture) {
        if (mode.equals("real")) {
            if (fixture) {
                throw new IllegalArgumentException(
                        "Real Identity forbids fixtures; IdentityRequest requires real authority");
            }
            return;
        }
        if (identity) {
            throw new IllegalArgumentException(
                    "Real Identity forbids fixtures; IdentityRequest requires real authority");
        }
        if (command && !fixture) {
            throw new IllegalArgumentException(
                    "Fixture Command requires explicit fixture authority");
        }
    }

    public static Map<String, Integer> parseConsumptionLimits(
            Environment env, Set<String> expected) {
        var limits = new LinkedHashMap<String, Integer>();
        for (var entry : entries(env.getRequiredProperty(PREFIX + "consumption-limits"), ",")) {
            var pair = entry.split("=", -1);
            if (pair.length != 2 || !expected.contains(pair[0])) {
                throw new IllegalArgumentException("invalid Consumption limits");
            }
            int value = Integer.parseInt(pair[1]);
            if (value < 1 || limits.put(pair[0], value) != null) {
                throw new IllegalArgumentException("invalid Consumption limits");
            }
        }
        if (!limits.keySet().equals(expected)) {
            throw new IllegalArgumentException("incomplete enabled Consumption limits");
        }
        return limits;
    }

    public static Map<String, List<Integer>> parsePartitions(
            Environment env, Set<String> expected) {
        var partitions = new LinkedHashMap<String, List<Integer>>();
        for (var entry : entries(env.getRequiredProperty(PREFIX + "partitions"), ";")) {
            var pair = entry.split("=", -1);
            if (pair.length != 2 || !expected.contains(pair[0])) {
                throw new IllegalArgumentException("invalid candidate definition");
            }
            var values = Arrays.stream(pair[1].split("\\|", -1)).map(Integer::valueOf).toList();
            if (values.isEmpty()
                    || new HashSet<>(values).size() != values.size()
                    || values.stream().anyMatch(p -> p < 0 || p >= 4)
                    || partitions.put(pair[0], values) != null) {
                throw new IllegalArgumentException("invalid partitions");
            }
        }
        if (!partitions.keySet().equals(expected)) {
            throw new IllegalArgumentException("incomplete partition configuration");
        }
        return partitions;
    }

    public static int requireValidBatchSize(Environment env) {
        int batch = env.getProperty(PREFIX + "lifecycle.batch-size", Integer.class, 32);
        if (batch < 1 || batch > 32) {
            throw new IllegalArgumentException("pilot batch must be 1..32");
        }
        return batch;
    }

    public static LifecycleSettings buildLifecycleSettings(
            Environment env, int batch, boolean requireExplicitLeaseDuration) {
        Duration leaseDuration =
                requireExplicitLeaseDuration
                        ? Duration.ofMillis(
                                Long.parseLong(
                                        env.getRequiredProperty(
                                                PREFIX + "lifecycle.lease-duration-ms")))
                        : duration(env, "lifecycle.lease-duration-ms", 30000);
        return new LifecycleSettings(
                batch,
                leaseDuration,
                duration(env, "lifecycle.claim-duration-ms", 60000),
                duration(env, "lifecycle.renewal-interval-ms", 10000),
                Duration.ofMillis(500),
                Duration.ofSeconds(2),
                new LifecycleSettings.RetryPolicy(
                        env.getProperty(PREFIX + "lifecycle.retry-max-failures", Integer.class, 3),
                        duration(env, "lifecycle.retry-delay-ms", 30000)));
    }

    public static String[] entries(String value, String separator) {
        return value.isEmpty() ? new String[0] : value.split(separator, -1);
    }

    public static Duration duration(Environment env, String key, long ms) {
        return Duration.ofMillis(Long.parseLong(env.getProperty(PREFIX + key, Long.toString(ms))));
    }

    public static ConsumptionDefinition definition(String name) {
        return switch (name) {
            case "IdentityRequest" ->
                    ConsumptionCatalog.definitions().stream()
                            .filter(d -> d.family() == ConsumableFamily.IDENTITY_REQUEST)
                            .findFirst()
                            .orElseThrow();
            case "Command" -> ConsumptionCatalog.definitions().getFirst();
            case "I5ReadPotProbe" -> ConsumptionCatalog.probeDefinitions().get(0);
            case "I5ReadCompletionProbe" -> ConsumptionCatalog.probeDefinitions().get(1);
            default -> {
                int index = READ_NAMES.indexOf(name);
                if (index < 0) throw new IllegalArgumentException("source/participant not delivered");
                int catalogIndex = index == 6 ? 5 : index < 3 ? index + 2 : index + 3;
                yield ConsumptionCatalog.definitions().get(catalogIndex);
            }
        };
    }

    public static List<DevTarget> parseDevTargets(Environment env) {
        var allowed = new ArrayList<DevTarget>();
        for (var tuple : env.getRequiredProperty(PREFIX + "dev-targets").split(";")) {
            var v = tuple.split(",", -1);
            if (v.length != 7) {
                throw new IllegalArgumentException("dev-target tuple requires seven fields");
            }
            allowed.add(
                    new DevTarget(
                            v[0],
                            Integer.parseInt(v[1]),
                            v[2],
                            v[3],
                            v[4],
                            v[5],
                            Integer.parseInt(v[6])));
        }
        if (allowed.isEmpty() || allowed.size() > 2 || new HashSet<>(allowed).size() != allowed.size()) {
            throw new IllegalArgumentException("two exact dev targets required");
        }
        return List.copyOf(allowed);
    }

    public static PoolSpec parsePool(
            Environment env, String key, boolean business, int workerLimit) {
        String p = PREFIX + key + ".";
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
                new PoolSpec(
                        key,
                        env.getRequiredProperty(p + "url"),
                        env.getRequiredProperty(p + "username"),
                        env.getRequiredProperty(p + "password"),
                        db,
                        schema,
                        max,
                        minIdle,
                        business);
        if (!pool.username().equals(role)
                || pool.maximum() != defaultMax
                || pool.minimumIdle() != pool.maximum()) {
            throw new IllegalArgumentException("unqualified pool sizing or runtime principal");
        }
        var uri = endpoint(pool);
        if (!hasValidUriShape(uri, db, schema) || !hasValidIdentifiers(db, schema, role)) {
            throw new IllegalArgumentException("explicit single TCP target and schema required");
        }
        return pool;
    }

    public static URI endpoint(PoolSpec pool) {
        if (!pool.url().startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("PostgreSQL TCP required");
        }
        return URI.create(pool.url().substring(5));
    }

    public static boolean sameAuthority(PoolSpec a, PoolSpec b) {
        return a.url().equals(b.url())
                && a.username().equals(b.username())
                && a.password().equals(b.password());
    }

    public static void validateTarget(
            List<DevTarget> allowedTargets, PoolSpec pool, DataSource source) {
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
                        new DevTarget(
                                uri.getHost(),
                                uri.getPort(),
                                rows.getString(1),
                                rows.getString(2),
                                rows.getString(3),
                                rows.getString(4),
                                rows.getInt(5));
                if (!allowedTargets.contains(actual)
                        || !actual.database().equals(pool.database())
                        || !actual.schema().equals(pool.schema())
                        || !actual.role().equals(pool.username())) {
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

    public static SpringAttemptTransactions transactionOwner(HikariDataSource pool, int timeout) {
        var manager = new DataSourceTransactionManager(pool);
        manager.setNestedTransactionAllowed(false);
        return new SpringAttemptTransactions(pool, manager, timeout);
    }

    public static HikariConfig buildHikariConfig(
            String workerName, PoolSpec spec, boolean readOnly) {
        var config = new HikariConfig();
        config.setPoolName(workerName + "-" + spec.name());
        config.setJdbcUrl(spec.url());
        if (readOnly) {
            config.setReadOnly(true);
        }
        config.setUsername(spec.username());
        config.setPassword(spec.password());
        config.setMaximumPoolSize(spec.maximum());
        config.setMinimumIdle(spec.minimumIdle());
        config.setConnectionTimeout(1000);
        config.setValidationTimeout(500);
        config.setInitializationFailTimeout(5000);
        config.addDataSourceProperty("connectTimeout", "2");
        config.addDataSourceProperty("socketTimeout", spec.business() ? "70" : "5");
        config.addDataSourceProperty("ApplicationName", config.getPoolName());
        config.addDataSourceProperty(
                "options",
                spec.business()
                        ? "-c statement_timeout=60000 -c transaction_timeout=65000"
                        : "-c lock_timeout=500 -c statement_timeout=2000 -c"
                                + " transaction_timeout=3000");
        return config;
    }

    public static void awaitPoolReserve(HikariDataSource pool, int minimumIdle) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (pool.getHikariPoolMXBean().getIdleConnections() < minimumIdle
                && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("pool initialization interrupted");
            }
        }
        if (pool.getHikariPoolMXBean().getIdleConnections() < minimumIdle) {
            throw new IllegalStateException("physical pool reserve not established");
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
}
