package com.kartaguez.pocoma.architecture.qualification;
import com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog;

import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition.Authority;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.supra.polling.consumption.ConsumptionWorker;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.sql.*;
import java.time.Duration;
import java.util.*;

import javax.sql.DataSource;

/** Closed development composition. No profile or database name implicitly authorizes fixtures. */
public record QualificationSettings(
        String workerName,
        int workerLimit,
        boolean fixtureIdentity,
        String identityAuthority,
        boolean identityRequest,
        boolean commandEnabled,
        boolean technicalProbes,
        boolean readMode,
        List<String> readParticipants,
        Map<String, Integer> consumptionLimits,
        Map<String, List<Integer>> partitions,
        Pool writeBusiness,
        Pool writeControl,
        Pool readBusiness,
        Pool readControl,
        List<DevTarget> allowedTargets,
        ConsumptionWorker.Settings polling,
        LifecycleSettings lifecycle) {
    public static final String PROFILE = "v3-consumption-worker-dev";
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
        return "QualificationSettings[" + workerName + ", M_W=" + workerLimit + "]";
    }

    public QualificationSettings {
        readParticipants = List.copyOf(readParticipants);
        consumptionLimits = Map.copyOf(consumptionLimits);
        partitions = Map.copyOf(partitions);
        allowedTargets = List.copyOf(allowedTargets);
    }

    public static List<ConsumptionDefinition> probeDefinitions() { return ConsumptionCatalog.probeDefinitions(); }

    public static boolean requested(Environment env) {
        boolean profile =
                Arrays.stream(env.getActiveProfiles())
                        .anyMatch(p -> p.equals(PROFILE) || p.equals(READ_PROFILE));
        if (!profile && !hasQualificationProperty(env)) return false;
        validateRequestedEnvironment(env, profile);
        return true;
    }

    private static boolean hasQualificationProperty(Environment env) {
        return List.of(
                        "enabled",
                        "fixture-identity",
                        "technical-probes",
                        "identity-authority",
                        "identity-request",
                        "read-participants")
                .stream()
                .anyMatch(k -> env.containsProperty(PREFIX + k));
    }

    private static boolean isAllowedProfileCombination(Environment env, boolean profile) {
        if (!profile) return false;
        if (env.getActiveProfiles().length == 1) return true;
        var profiles = Set.copyOf(Arrays.asList(env.getActiveProfiles()));
        if (profiles.equals(Set.of(READ_PROFILE, "v3-http-read"))) return true;
        return profiles.equals(Set.of(READ_PROFILE, "v3-http-read", "v3-http-admission"));
    }

    private static void validateRequestedEnvironment(Environment env, boolean profile) {
        if (!isAllowedProfileCombination(env, profile)) {
            throw new IllegalStateException(
                    "I5 requires explicit profile, DEV authorization and targets");
        }
        if (!flag(env, "enabled") || !"DEV".equals(env.getRequiredProperty(PREFIX + "environment"))) {
            throw new IllegalStateException(
                    "I5 requires explicit profile, DEV authorization and targets");
        }
        if (env.getRequiredProperty(PREFIX + "dev-targets").isBlank()) {
            throw new IllegalStateException(
                    "I5 requires explicit profile, DEV authorization and targets");
        }
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

    private record AuthoritySelection(
            String mode,
            boolean identity,
            boolean command,
            List<String> reads,
            Set<String> expected) {}

    public static QualificationSettings from(Environment env) {
        if (!requested(env)) throw new IllegalStateException("I5 not authorized");
        boolean readMode = Arrays.asList(env.getActiveProfiles()).contains(READ_PROFILE);
        if (!readMode && env.containsProperty(PREFIX + "read-participants"))
            throw new IllegalArgumentException("read-participants requires v3-read-worker");
        String name = env.getRequiredProperty(PREFIX + "worker-name");
        if (!name.matches("[a-z][a-z0-9-]{1,31}"))
            throw new IllegalArgumentException("invalid worker name");
        int mw = positive(env, "worker-limit");
        boolean fixture = flag(env, "fixture-identity"), probes = flag(env, "technical-probes");
        var sel = resolveAuthoritySelection(env, readMode, fixture, probes);
        validateWorkerRole(name, sel, readMode);
        var limits = parseConsumptionLimits(env, sel.expected());
        var partitions = parsePartitions(env, sel.expected());
        var allowed = parseAllowedTargets(env);
        Pool wb = pool(env, "write-business", true, mw),
                wc = pool(env, "write-control", false, mw),
                rb = pool(env, "read-business", true, mw),
                rc = pool(env, "read-control", false, mw);
        validatePoolPairs(wb, wc, rb, rc);
        int batch = env.getProperty(PREFIX + "lifecycle.batch-size", Integer.class, 32);
        if (batch < 1 || batch > 32)
            throw new IllegalArgumentException("pilot batch must be 1..32");
        var life = buildLifecycle(env, readMode, batch);
        var settings = buildWorkerSettings(env, batch, life);
        validateLeaseBudget(readMode, partitions, new LeaseBounds(mw, settings, life));
        if (settings.drainDeadline().compareTo(Duration.ofSeconds(90)) > 0)
            throw new IllegalArgumentException("dev drain must precede external shutdown deadline");
        return new QualificationSettings(
                name,
                mw,
                fixture,
                sel.mode(),
                sel.identity(),
                sel.command(),
                probes,
                readMode,
                sel.reads(),
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

    private static AuthoritySelection resolveAuthoritySelection(
            Environment env, boolean readMode, boolean fixture, boolean probes) {
        String mode = env.getRequiredProperty(PREFIX + "identity-authority");
        if (!Set.of("fixture", "real").contains(mode))
            throw new IllegalArgumentException("explicit Identity authority mode required");
        boolean identity =
                env.containsProperty(PREFIX + "identity-request") && flag(env, "identity-request");
        boolean command =
                env.containsProperty(PREFIX + "command-enabled") && flag(env, "command-enabled");
        validateAuthorityCombination(mode, identity, command, fixture);
        var reads = readMode ? parseReadParticipants(env, fixture, probes, mode) : List.<String>of();
        var sel = new AuthoritySelection(mode, identity, command, reads, Set.of());
        var expected = buildExpectedConsumptions(sel, probes, readMode);
        return new AuthoritySelection(mode, identity, command, reads, expected);
    }

    private static void validateAuthorityCombination(
            String mode, boolean identity, boolean command, boolean fixture) {
        boolean isReal = mode.equals("real");
        if ((identity && !isReal) || (isReal && fixture))
            throw new IllegalArgumentException(
                    "Real Identity forbids fixtures; IdentityRequest requires real authority");
        if (command && mode.equals("fixture") && !fixture)
            throw new IllegalArgumentException(
                    "Fixture Command requires explicit fixture authority");
    }

    private static Set<String> buildExpectedConsumptions(
            AuthoritySelection sel, boolean probes, boolean readMode) {
        var expected = new LinkedHashSet<String>(sel.reads());
        if (sel.identity()) expected.add("IdentityRequest");
        if (sel.command()) expected.add("Command");
        if (probes) expected.addAll(List.of("I5ReadPotProbe", "I5ReadCompletionProbe"));
        if (expected.isEmpty() && !readMode)
            throw new IllegalArgumentException("at least one implemented pair required");
        return expected;
    }

    private static List<String> parseReadParticipants(
            Environment env, boolean fixture, boolean probes, String mode) {
        if (fixture || probes || !mode.equals("real"))
            throw new IllegalArgumentException(
                    "READ business profile requires real Identity and forbids probes/fixtures");
        var reads = new ArrayList<String>();
        String selection = env.getRequiredProperty(PREFIX + "read-participants");
        if (selection.isEmpty()) return reads;
        for (String selected : selection.split(",", -1)) {
            if (!READ_NAMES.contains(selected) || reads.contains(selected))
                throw new IllegalArgumentException(
                        "unknown or duplicate READ participant: " + selected);
            reads.add(selected);
        }
        return reads;
    }

    private static void validateWorkerRole(
            String name, AuthoritySelection sel, boolean readMode) {
        switch (name) {
            case "command-worker", "pocoma-command-worker" ->
                    validateCommandWorker(sel, readMode);
            case "results-worker", "pocoma-results-worker" ->
                    validateResultsWorker(sel, readMode);
            case "business-read-worker", "pocoma-business-read-worker" ->
                    validateBusinessReadWorker(sel, readMode);
            default -> {}
        }
    }

    private static void validateCommandWorker(AuthoritySelection sel, boolean readMode) {
        if (readMode || !sel.reads().isEmpty())
            throw new IllegalArgumentException("Command worker cannot activate read participants");
        if (!sel.command() || !sel.identity())
            throw new IllegalArgumentException(
                    "Command worker must activate Command and IdentityRequest");
    }

    private static void validateResultsWorker(AuthoritySelection sel, boolean readMode) {
        if (!readMode || sel.command() || sel.identity())
            throw new IllegalArgumentException("Results worker cannot activate write participants");
        var resultTypes = Set.of("MaterializeCommandResult", "MaterializeIdentityRequestResult");
        if (!Set.copyOf(sel.reads()).equals(resultTypes))
            throw new IllegalArgumentException(
                    "Results worker must activate exclusively MaterializeCommandResult and MaterializeIdentityRequestResult");
    }

    private static void validateBusinessReadWorker(AuthoritySelection sel, boolean readMode) {
        if (!readMode || sel.command() || sel.identity())
            throw new IllegalArgumentException(
                    "Business read worker cannot activate write participants");
        if (sel.reads().contains("MaterializeCommandResult")
                || sel.reads().contains("MaterializeIdentityRequestResult"))
            throw new IllegalArgumentException(
                    "Business READ worker cannot activate result projections");
    }

    private static Map<String, Integer> parseConsumptionLimits(
            Environment env, Set<String> expected) {
        var limits = new LinkedHashMap<String, Integer>();
        for (var entry : entries(env.getRequiredProperty(PREFIX + "consumption-limits"), ",")) {
            var pair = entry.split("=", -1);
            if (pair.length != 2 || !expected.contains(pair[0]))
                throw new IllegalArgumentException("invalid Consumption limits");
            int val = Integer.parseInt(pair[1]);
            if (val < 1 || limits.put(pair[0], val) != null)
                throw new IllegalArgumentException("invalid Consumption limits");
        }
        if (!limits.keySet().equals(expected))
            throw new IllegalArgumentException("incomplete enabled Consumption limits");
        return limits;
    }

    private static List<Integer> parsePartitionIds(String raw) {
        var values = Arrays.stream(raw.split("\\|", -1)).map(Integer::valueOf).toList();
        if (values.isEmpty() || values.stream().anyMatch(p -> p < 0 || p >= 4))
            throw new IllegalArgumentException("invalid partitions");
        if (new HashSet<>(values).size() != values.size())
            throw new IllegalArgumentException("invalid partitions");
        return values;
    }

    private static Map<String, List<Integer>> parsePartitions(
            Environment env, Set<String> expected) {
        var partitions = new LinkedHashMap<String, List<Integer>>();
        for (var entry : entries(env.getRequiredProperty(PREFIX + "partitions"), ";")) {
            var pair = entry.split("=", -1);
            if (pair.length != 2 || !expected.contains(pair[0]))
                throw new IllegalArgumentException("invalid candidate definition");
            if (partitions.put(pair[0], parsePartitionIds(pair[1])) != null)
                throw new IllegalArgumentException("invalid partitions");
        }
        if (!partitions.keySet().equals(expected))
            throw new IllegalArgumentException("incomplete partition configuration");
        return partitions;
    }

    private static List<DevTarget> parseAllowedTargets(Environment env) {
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
        return allowed;
    }

    private static void validatePoolPairs(Pool wb, Pool wc, Pool rb, Pool rc) {
        if (!sameAuthority(wb, wc) || !sameAuthority(rb, rc))
            throw new IllegalArgumentException(
                    "Business/Control targets must match; WRITE/READ must differ");
        if (wb.database().equals(rb.database())
                && endpoint(wb).getAuthority().equals(endpoint(rb).getAuthority()))
            throw new IllegalArgumentException(
                    "Business/Control targets must match; WRITE/READ must differ");
    }

    private static LifecycleSettings buildLifecycle(Environment env, boolean readMode, int batch) {
        Duration lease =
                readMode
                        ? Duration.ofMillis(
                                Long.parseLong(
                                        env.getRequiredProperty(
                                                PREFIX + "lifecycle.lease-duration-ms")))
                        : duration(env, "lifecycle.lease-duration-ms", 30000);
        return new LifecycleSettings(
                batch,
                lease,
                duration(env, "lifecycle.claim-duration-ms", 60000),
                duration(env, "lifecycle.renewal-interval-ms", 10000),
                Duration.ofMillis(500),
                Duration.ofSeconds(2),
                new LifecycleSettings.RetryPolicy(
                        env.getProperty(PREFIX + "lifecycle.retry-max-failures", Integer.class, 3),
                        duration(env, "lifecycle.retry-delay-ms", 30000)));
    }

    private static ConsumptionWorker.Settings buildWorkerSettings(
            Environment env, int batch, LifecycleSettings life) {
        return new ConsumptionWorker.Settings(
                batch,
                env.getProperty(PREFIX + "quantum", Integer.class, 1),
                duration(env, "min-poll", 10),
                duration(env, "max-poll", 1000),
                duration(env, "error-poll", 1000),
                Duration.ofSeconds(1),
                life.renewalInterval(),
                Duration.ofSeconds(5),
                duration(env, "drain-timeout", 90000));
    }

    private record LeaseBounds(int mw, ConsumptionWorker.Settings settings, LifecycleSettings life) {}

    private static void validateLeaseBudget(
            boolean readMode, Map<String, List<Integer>> partitions, LeaseBounds bounds) {
        // Necessary nominal-batch check, not physical certification. Partial batches can
        // require more calls: qualification must count every candidate, include polling,
        // and observe all PostgreSQL deadlines with the measured whole-path envelope.
        int batch = bounds.settings().pageSize();
        int leaseLots =
                partitions.values().stream().mapToInt(p -> (p.size() + batch - 1) / batch).sum();
        Duration leaseBound =
                readMode
                        ? readModeLeaseBound(partitions, bounds.settings())
                        : bounds.settings().renewalInterval().plusSeconds(5L * leaseLots + 3);
        if (leaseBound.compareTo(bounds.life().leaseDuration()) >= 0
                || bounds.settings()
                                .renewalInterval()
                                .plusSeconds(5L * bounds.mw() + 3)
                                .compareTo(bounds.life().claimDuration())
                        >= 0)
            throw new IllegalArgumentException(
                    "renewal budget exceeds pilot margins; explicit requalification required");
    }

    private static Duration readModeLeaseBound(
            Map<String, List<Integer>> partitions, ConsumptionWorker.Settings settings) {
        Duration leaseBound = Duration.ZERO;
        for (var authority : Authority.values()) {
            int candidates = 0, bindings = 0;
            for (var entry : partitions.entrySet()) {
                if (definition(entry.getKey()).authority() == authority) {
                    candidates += entry.getValue().size();
                    bindings++;
                }
            }
            // One useful renewal per call, one bounded no-progress window per binding.
            // Independent lanes per authority. Qualification must observe each SQL deadline.
            var bound =
                    settings.renewalInterval()
                            .plus(settings.renewalBudget().multipliedBy((long) candidates + bindings))
                            .plus(settings.minPoll().multipliedBy(candidates))
                            .plusSeconds(4);
            if (bound.compareTo(leaseBound) > 0) leaseBound = bound;
        }
        return leaseBound;
    }

    private static String[] entries(String value, String separator) {
        return value.isEmpty() ? new String[0] : value.split(separator, -1);
    }

    private static Duration duration(Environment env, String key, long ms) {
        return Duration.ofMillis(Long.parseLong(env.getProperty(PREFIX + key, Long.toString(ms))));
    }

    private static Pool pool(Environment env, String key, boolean business, int mw) {
        String p = PREFIX + key + ".", authority = key.startsWith("write") ? "write" : "read";
        String db = env.getRequiredProperty("pocoma.v3.pilot." + authority + ".database"),
                schema = env.getRequiredProperty("pocoma.v3.pilot." + authority + ".schema"),
                role = env.getRequiredProperty("pocoma.v3.pilot." + authority + ".username");
        if (env.containsProperty(p + "maximum"))
            throw new IllegalArgumentException("use maximum-pool-size, not maximum: " + key);
        int defaultMax = mw + (business ? 2 : 6);
        int max = env.getProperty(p + "maximum-pool-size", Integer.class, defaultMax);
        int minIdle = env.getProperty(p + "minimum-idle", Integer.class, max);
        var pool =
                new Pool(
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
        validatePoolUri(pool, db, schema, role);
        return pool;
    }

    private static void validatePoolSizing(Pool pool, String role, int defaultMax) {
        if (!pool.username().equals(role)
                || pool.maximum() != defaultMax
                || pool.minimumIdle() != pool.maximum()) {
            throw new IllegalArgumentException("unqualified pool sizing or runtime principal");
        }
    }

    private static void validatePoolUri(Pool pool, String db, String schema, String role) {
        var uri = endpoint(pool);
        boolean validUri =
                uri.getHost() != null
                        && uri.getPort() >= 1
                        && uri.getPath().equals("/" + db)
                        && uri.getUserInfo() == null
                        && ("currentSchema=" + schema).equals(uri.getQuery())
                        && uri.getFragment() == null;
        boolean validNames =
                db.matches("[a-z][a-z0-9_]*")
                        && schema.matches("[a-z][a-z0-9_]*")
                        && !schema.equals("public")
                        && role.matches("[a-z][a-z0-9_]*");
        if (!validUri || !validNames) {
            throw new IllegalArgumentException("explicit single TCP target and schema required");
        }
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
                if (!allowedTargets.contains(actual) || !matchesPoolTarget(actual, pool))
                    throw new IllegalStateException(
                            "physical authority outside explicit dev allowlist");
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("I5 target validation failed", e);
        }
    }

    private static boolean matchesPoolTarget(DevTarget actual, Pool pool) {
        return actual.database().equals(pool.database())
                && actual.schema().equals(pool.schema())
                && actual.role().equals(pool.username());
    }

    public Map<ConsumptionId, Integer> capacityLimits() {
        var result = new LinkedHashMap<ConsumptionId, Integer>();
        consumptionLimits.forEach((name, limit) -> result.put(definition(name).id(), limit));
        return Map.copyOf(result);
    }

    public static ConsumptionDefinition definition(String name) {
        return switch (name) {
            case "IdentityRequest" ->
                    com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions().stream()
                            .filter(d -> d.family() == ConsumableFamily.IDENTITY_REQUEST)
                            .findFirst()
                            .orElseThrow();
            case "Command" -> com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions().getFirst();
            case "I5ReadPotProbe" -> probeDefinitions().get(0);
            case "I5ReadCompletionProbe" -> probeDefinitions().get(1);
            default -> {
                int index = READ_NAMES.indexOf(name);
                if (index < 0)
                    throw new IllegalArgumentException("source/participant not delivered");
                int catalogIndex = index == 6 ? 5 : index < 3 ? index + 2 : index + 3;
                yield com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions().get(catalogIndex);
            }
        };
    }

    public List<ConsumptionDefinition> catalog() {
        var result = new ArrayList<>(com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions());
        if (technicalProbes) result.addAll(probeDefinitions());
        return List.copyOf(result);
    }
}
