package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition.Authority;
import com.kartaguez.pocoma.engine.consumption.v3.LifecycleSettings;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.sql.*;
import java.time.Duration;
import java.util.*;

import javax.sql.DataSource;

/** Closed development composition. No profile or database name implicitly authorizes fixtures. */
public record V3ConsumptionWorkerSettings(
        String workerName,
        int workerLimit,
        boolean fixtureIdentity,
        boolean technicalProbes,
        Map<String, Integer> consumptionLimits,
        Map<String, List<Integer>> partitions,
        Pool writeBusiness,
        Pool writeControl,
        Pool readBusiness,
        Pool readControl,
        List<DevTarget> allowedTargets,
        V3ConsumptionWorker.Settings polling,
        LifecycleSettings lifecycle) {
    public static final String PROFILE = "v3-consumption-worker-dev";
    static final String PREFIX = "pocoma.v3.worker.";

    public record DevTarget(
            String host,
            int port,
            String database,
            String schema,
            String role,
            String serverAddress,
            int serverPort) {}

    public record Pool(
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

        DriverManagerDataSource preflightSource() {
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

    @Override
    public String toString() {
        return "V3ConsumptionWorkerSettings[" + workerName + ", M_W=" + workerLimit + "]";
    }

    public V3ConsumptionWorkerSettings {
        consumptionLimits = Map.copyOf(consumptionLimits);
        partitions = Map.copyOf(partitions);
        allowedTargets = List.copyOf(allowedTargets);
    }

    public static List<ConsumptionDefinition> probeDefinitions() {
        return List.of(
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("a5000000-0000-4000-8000-000000000001")),
                        ConsumableFamily.POT_EVENT,
                        Authority.READ),
                new ConsumptionDefinition(
                        new ConsumptionId(UUID.fromString("a5000000-0000-4000-8000-000000000002")),
                        ConsumableFamily.COMMAND_COMPLETION_EVENT,
                        Authority.READ));
    }

    public static boolean requested(Environment env) {
        boolean profile = Arrays.asList(env.getActiveProfiles()).contains(PROFILE);
        boolean any =
                profile
                        || env.containsProperty(PREFIX + "enabled")
                        || env.containsProperty(PREFIX + "fixture-identity")
                        || env.containsProperty(PREFIX + "technical-probes");
        if (!any) return false;
        if (!profile
                || env.getActiveProfiles().length != 1
                || !flag(env, "enabled")
                || !"DEV".equals(env.getRequiredProperty(PREFIX + "environment"))
                || env.getRequiredProperty(PREFIX + "dev-targets").isBlank())
            throw new IllegalStateException(
                    "I5 requires explicit profile, DEV authorization and targets");
        return true;
    }

    private static boolean flag(Environment env, String key) {
        String value = env.getRequiredProperty(PREFIX + key);
        if (!value.equals("true") && !value.equals("false"))
            throw new IllegalArgumentException("explicit boolean required: " + key);
        return value.equals("true");
    }

    private static int positive(Environment env, String key) {
        int n = Integer.parseInt(env.getRequiredProperty(PREFIX + key));
        if (n < 1) throw new IllegalArgumentException("positive bound required: " + key);
        return n;
    }

    public static V3ConsumptionWorkerSettings from(Environment env) {
        if (!requested(env)) throw new IllegalStateException("I5 not authorized");
        String name = env.getRequiredProperty(PREFIX + "worker-name");
        if (!name.matches("[a-z][a-z0-9-]{1,31}"))
            throw new IllegalArgumentException("invalid worker name");
        int mw = positive(env, "worker-limit");
        boolean fixture = flag(env, "fixture-identity"), probes = flag(env, "technical-probes");
        var expected = new LinkedHashSet<String>();
        if (fixture) expected.add("Command");
        if (probes) expected.addAll(List.of("I5ReadPotProbe", "I5ReadCompletionProbe"));
        if (expected.isEmpty())
            throw new IllegalArgumentException("at least one implemented pair required");
        var limits = new LinkedHashMap<String, Integer>();
        for (var entry : env.getRequiredProperty(PREFIX + "consumption-limits").split(",")) {
            var pair = entry.split("=", -1);
            if (pair.length != 2
                    || !expected.contains(pair[0])
                    || limits.put(pair[0], Integer.valueOf(pair[1])) != null
                    || Integer.parseInt(pair[1]) < 1)
                throw new IllegalArgumentException("invalid Consumption limits");
        }
        if (!limits.keySet().equals(expected))
            throw new IllegalArgumentException("incomplete enabled Consumption limits");
        var partitions = new LinkedHashMap<String, List<Integer>>();
        for (var entry : env.getRequiredProperty(PREFIX + "partitions").split(";")) {
            var pair = entry.split("=", -1);
            if (pair.length != 2 || !expected.contains(pair[0]))
                throw new IllegalArgumentException("invalid candidate definition");
            var values = Arrays.stream(pair[1].split("\\|", -1)).map(Integer::valueOf).toList();
            if (values.isEmpty()
                    || new HashSet<>(values).size() != values.size()
                    || values.stream().anyMatch(p -> p < 0 || p >= 4)
                    || partitions.put(pair[0], values) != null)
                throw new IllegalArgumentException("invalid partitions");
        }
        if (!partitions.keySet().equals(expected))
            throw new IllegalArgumentException("incomplete partition configuration");
        var allowed = new ArrayList<DevTarget>();
        for (var tuple : env.getRequiredProperty(PREFIX + "dev-targets").split(";")) {
            var v = tuple.split(",", -1);
            if (v.length != 7)
                throw new IllegalArgumentException("dev-target tuple requires seven fields");
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
        if (allowed.size() != 2 || new HashSet<>(allowed).size() != 2)
            throw new IllegalArgumentException("two exact dev targets required");
        Pool wb = pool(env, "write-business", true, mw),
                wc = pool(env, "write-control", false, mw),
                rb = pool(env, "read-business", true, mw),
                rc = pool(env, "read-control", false, mw);
        if (!sameAuthority(wb, wc)
                || !sameAuthority(rb, rc)
                || (wb.database().equals(rb.database())
                        && endpoint(wb).getAuthority().equals(endpoint(rb).getAuthority())))
            throw new IllegalArgumentException(
                    "Business/Control targets must match; WRITE/READ must differ");
        int batch = env.getProperty(PREFIX + "lifecycle.batch-size", Integer.class, 32);
        if (batch < 1 || batch > 32)
            throw new IllegalArgumentException("pilot batch must be 1..32");
        var life =
                new LifecycleSettings(
                        batch,
                        duration(env, "lifecycle.lease-duration-ms", 30000),
                        duration(env, "lifecycle.claim-duration-ms", 60000),
                        duration(env, "lifecycle.renewal-interval-ms", 10000),
                        Duration.ofMillis(500),
                        Duration.ofSeconds(2),
                        new LifecycleSettings.RetryPolicy(
                                env.getProperty(
                                        PREFIX + "lifecycle.retry-max-failures", Integer.class, 3),
                                duration(env, "lifecycle.retry-delay-ms", 30000)));
        var settings =
                new V3ConsumptionWorker.Settings(
                        batch,
                        env.getProperty(PREFIX + "quantum", Integer.class, 1),
                        duration(env, "min-poll", 10),
                        duration(env, "max-poll", 1000),
                        duration(env, "error-poll", 1000),
                        Duration.ofSeconds(1),
                        life.renewalInterval(),
                        Duration.ofSeconds(5),
                        duration(env, "drain-timeout", 90000));
        // Predeclared whole-path budgets, not a claim of measured certification.
        int leaseLots =
                partitions.values().stream().mapToInt(p -> (p.size() + batch - 1) / batch).sum();
        if (settings.renewalInterval()
                                .plusSeconds(5L * leaseLots + 3)
                                .compareTo(life.leaseDuration())
                        >= 0
                || settings.renewalInterval()
                                .plusSeconds(5L * mw + 3)
                                .compareTo(life.claimDuration())
                        >= 0)
            throw new IllegalArgumentException(
                    "renewal budget exceeds pilot margins; explicit requalification required");
        if (settings.drainDeadline().compareTo(Duration.ofSeconds(90)) > 0)
            throw new IllegalArgumentException("dev drain must precede external shutdown deadline");
        return new V3ConsumptionWorkerSettings(
                name,
                mw,
                fixture,
                probes,
                limits,
                partitions,
                wb,
                wc,
                rb,
                rc,
                allowed,
                settings,
                life);
    }

    private static Duration duration(Environment env, String key, long ms) {
        return Duration.ofMillis(Long.parseLong(env.getProperty(PREFIX + key, Long.toString(ms))));
    }

    private static Pool pool(Environment env, String key, boolean business, int mw) {
        String p = PREFIX + key + ".", authority = key.startsWith("write") ? "write" : "read";
        String db = env.getRequiredProperty("pocoma.v3.pilot." + authority + ".database"),
                schema = env.getRequiredProperty("pocoma.v3.pilot." + authority + ".schema"),
                role = env.getRequiredProperty("pocoma.v3.pilot." + authority + ".username");
        var pool =
                new Pool(
                        key,
                        env.getRequiredProperty(p + "url"),
                        env.getRequiredProperty(p + "username"),
                        env.getRequiredProperty(p + "password"),
                        db,
                        schema,
                        env.getProperty(
                                p + "maximum-pool-size", Integer.class, mw + (business ? 2 : 6)),
                        env.getProperty(p + "minimum-idle", Integer.class, mw + (business ? 2 : 6)),
                        business);
        if (!pool.username().equals(role)
                || pool.maximum() != mw + (business ? 2 : 6)
                || pool.minimumIdle() != pool.maximum())
            throw new IllegalArgumentException("unqualified pool sizing or runtime principal");
        var uri = endpoint(pool);
        if (uri.getHost() == null
                || uri.getPort() < 1
                || !uri.getPath().equals("/" + db)
                || uri.getUserInfo() != null
                || !("currentSchema=" + schema).equals(uri.getQuery())
                || uri.getFragment() != null
                || !db.matches("[a-z][a-z0-9_]*")
                || !schema.matches("[a-z][a-z0-9_]*")
                || schema.equals("public")
                || !role.matches("[a-z][a-z0-9_]*"))
            throw new IllegalArgumentException("explicit single TCP target and schema required");
        return pool;
    }

    private static URI endpoint(Pool pool) {
        if (!pool.url().startsWith("jdbc:postgresql://"))
            throw new IllegalArgumentException("PostgreSQL TCP required");
        return URI.create(pool.url().substring(5));
    }

    private static boolean sameAuthority(Pool a, Pool b) {
        return a.url().equals(b.url())
                && a.username().equals(b.username())
                && a.password().equals(b.password());
    }

    public void validateTarget(Pool pool, DataSource source) {
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
                        || !actual.role().equals(pool.username()))
                    throw new IllegalStateException(
                            "physical authority outside explicit dev allowlist");
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("I5 target validation failed", e);
        }
    }

    public Map<ConsumptionId, Integer> capacityLimits() {
        var result = new LinkedHashMap<ConsumptionId, Integer>();
        consumptionLimits.forEach((name, limit) -> result.put(definition(name).id(), limit));
        return Map.copyOf(result);
    }

    public static ConsumptionDefinition definition(String name) {
        return switch (name) {
            case "Command" -> V3ConsumptionCatalogConfiguration.definitions().getFirst();
            case "I5ReadPotProbe" -> probeDefinitions().get(0);
            case "I5ReadCompletionProbe" -> probeDefinitions().get(1);
            default -> throw new IllegalArgumentException("source/participant not delivered");
        };
    }

    public List<ConsumptionDefinition> catalog() {
        var result = new ArrayList<>(V3ConsumptionCatalogConfiguration.definitions());
        if (technicalProbes) result.addAll(probeDefinitions());
        return List.copyOf(result);
    }
}
