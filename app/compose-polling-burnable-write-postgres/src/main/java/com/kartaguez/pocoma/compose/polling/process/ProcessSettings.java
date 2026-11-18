package com.kartaguez.pocoma.compose.polling.process;

import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition.Authority;
import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.supra.polling.consumption.ConsumptionWorker;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.Duration;
import java.util.*;

import javax.sql.DataSource;

/** Closed development composition. No profile or database name implicitly authorizes fixtures. */
public record ProcessSettings(
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
        ProcessPoolSettings pools,
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
        return "ProcessSettings[" + workerName + ", M_W=" + workerLimit + "]";
    }

    public ProcessSettings {
        readParticipants = List.copyOf(readParticipants);
        consumptionLimits = Map.copyOf(consumptionLimits);
        partitions = Map.copyOf(partitions);
    }

    public Pool writeBusiness() {
        return pools.writeBusiness();
    }

    public Pool writeControl() {
        return pools.writeControl();
    }

    public List<DevTarget> allowedTargets() {
        return pools.allowedTargets();
    }

    public static List<ConsumptionDefinition> probeDefinitions() { return ConsumptionCatalog.probeDefinitions(); }

    public static boolean requested(Environment env) {
        boolean own = Arrays.asList(env.getActiveProfiles()).contains("v3-consumption-worker-dev");
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

    private static Environment namespaced(Environment original) {
        var selected = new org.springframework.core.env.StandardEnvironment();
        selected.setActiveProfiles(original.getActiveProfiles());
        selected.getPropertySources().addFirst(namespacedPropertySource(original));
        var profiles = Arrays.asList(original.getActiveProfiles());
        if (profiles.contains(PROFILE) && profiles.contains(READ_PROFILE)) {
            if (!original.containsProperty("pocoma.v3.process.enabled")) {
                throw new IllegalStateException(
                        "Co-hosted workers require independent process/materialize namespaces");
            }
        }
        return selected;
    }

    private static org.springframework.core.env.PropertySource<Environment> namespacedPropertySource(
            Environment original) {
        return new org.springframework.core.env.PropertySource<>(
                "explicit-compose-namespace", original) {
            public Object getProperty(String key) {
                if (key.startsWith(PREFIX)
                        && source.containsProperty("pocoma.v3.process.enabled")) {
                    return source.getProperty("pocoma.v3.process." + key.substring(PREFIX.length()));
                }
                return source.getProperty(key);
            }
        };
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

    private record AuthorityFlags(
            String mode, boolean identity, boolean command, boolean fixture) {}

    public static ProcessSettings from(Environment original) {
        final Environment env = namespaced(original);
        if (!requested(env)) throw new IllegalStateException("I5 not authorized");
        if (env.containsProperty(PREFIX + "read-participants")) {
            throw new IllegalArgumentException("read-participants requires v3-read-worker");
        }
        String name = requireValidWorkerName(env);
        int mw = positive(env, "worker-limit");
        boolean fixture = flag(env, "fixture-identity");
        boolean probes = flag(env, "technical-probes");
        if (probes) throw new IllegalArgumentException("Process cannot host READ technical probes");
        var flags = readAuthorityFlags(env, fixture);
        validateProcessWorkerName(name, flags);
        return buildValidatedSettings(env, name, mw, flags);
    }

    private static ProcessSettings buildValidatedSettings(
            Environment env, String name, int mw, AuthorityFlags flags) {
        var expected = collectExpectedConsumptions(flags);
        var limits = parseConsumptionLimits(env, expected);
        var partitions = parsePartitions(env, expected);
        var pools = ProcessPoolSettings.from(env, mw);
        int batch = requireValidBatchSize(env);
        var life = buildLifecycleSettings(env, batch);
        var settings = buildWorkerSettings(env, batch, life.renewalInterval());
        verifyTimingBounds(life, settings, partitions, mw);
        return new ProcessSettings(
                name,
                mw,
                flags.fixture(),
                flags.mode(),
                flags.identity(),
                flags.command(),
                false,
                false,
                List.of(),
                limits,
                partitions,
                pools,
                settings,
                life);
    }

    private static String requireValidWorkerName(Environment env) {
        String name = env.getRequiredProperty(PREFIX + "worker-name");
        if (!name.matches("[a-z][a-z0-9-]{1,31}")) {
            throw new IllegalArgumentException("invalid worker name");
        }
        return name;
    }

    private static AuthorityFlags readAuthorityFlags(Environment env, boolean fixture) {
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
        boolean realMode = mode.equals("real");
        if (realMode) {
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

    private static Set<String> collectExpectedConsumptions(AuthorityFlags flags) {
        var expected = new LinkedHashSet<String>();
        if (flags.identity()) expected.add("IdentityRequest");
        if (flags.command()) expected.add("Command");
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("at least one implemented pair required");
        }
        return expected;
    }

    private static void validateProcessWorkerName(String name, AuthorityFlags flags) {
        if (Set.of("command-worker", "pocoma-command-worker").contains(name)) {
            if (!flags.command() || !flags.identity()) {
                throw new IllegalArgumentException(
                        "Command worker must activate Command and IdentityRequest");
            }
            return;
        }
        if (Set.of(
                        "results-worker",
                        "pocoma-results-worker",
                        "business-read-worker",
                        "pocoma-business-read-worker")
                .contains(name)) {
            String label = name.contains("results") ? "Results worker" : "Business read worker";
            throw new IllegalArgumentException(label + " cannot activate write participants");
        }
    }

    private static Map<String, Integer> parseConsumptionLimits(
            Environment env, Set<String> expected) {
        var limits = new LinkedHashMap<String, Integer>();
        for (var entry : entries(env.getRequiredProperty(PREFIX + "consumption-limits"), ",")) {
            parseLimitEntry(entry, expected, limits);
        }
        if (!limits.keySet().equals(expected)) {
            throw new IllegalArgumentException("incomplete enabled Consumption limits");
        }
        return limits;
    }

    private static void parseLimitEntry(
            String entry, Set<String> expected, Map<String, Integer> limits) {
        var pair = entry.split("=", -1);
        if (pair.length != 2 || !expected.contains(pair[0])) {
            throw new IllegalArgumentException("invalid Consumption limits");
        }
        int value = Integer.parseInt(pair[1]);
        if (value < 1 || limits.put(pair[0], value) != null) {
            throw new IllegalArgumentException("invalid Consumption limits");
        }
    }

    private static Map<String, List<Integer>> parsePartitions(
            Environment env, Set<String> expected) {
        var partitions = new LinkedHashMap<String, List<Integer>>();
        for (var entry : entries(env.getRequiredProperty(PREFIX + "partitions"), ";")) {
            parsePartitionEntry(entry, expected, partitions);
        }
        if (!partitions.keySet().equals(expected)) {
            throw new IllegalArgumentException("incomplete partition configuration");
        }
        return partitions;
    }

    private static void parsePartitionEntry(
            String entry, Set<String> expected, Map<String, List<Integer>> partitions) {
        var pair = entry.split("=", -1);
        if (pair.length != 2 || !expected.contains(pair[0])) {
            throw new IllegalArgumentException("invalid candidate definition");
        }
        var values = Arrays.stream(pair[1].split("\\|", -1)).map(Integer::valueOf).toList();
        if (!isValidPartitionList(values) || partitions.put(pair[0], values) != null) {
            throw new IllegalArgumentException("invalid partitions");
        }
    }

    private static boolean isValidPartitionList(List<Integer> values) {
        if (values.isEmpty() || new HashSet<>(values).size() != values.size()) return false;
        return values.stream().noneMatch(p -> p < 0 || p >= 4);
    }

    private static int requireValidBatchSize(Environment env) {
        int batch = env.getProperty(PREFIX + "lifecycle.batch-size", Integer.class, 32);
        if (batch < 1 || batch > 32) {
            throw new IllegalArgumentException("pilot batch must be 1..32");
        }
        return batch;
    }

    private static LifecycleSettings buildLifecycleSettings(Environment env, int batch) {
        return new LifecycleSettings(
                batch,
                duration(env, "lifecycle.lease-duration-ms", 30000),
                duration(env, "lifecycle.claim-duration-ms", 60000),
                duration(env, "lifecycle.renewal-interval-ms", 10000),
                Duration.ofMillis(500),
                Duration.ofSeconds(2),
                new LifecycleSettings.RetryPolicy(
                        env.getProperty(PREFIX + "lifecycle.retry-max-failures", Integer.class, 3),
                        duration(env, "lifecycle.retry-delay-ms", 30000)));
    }

    private static ConsumptionWorker.Settings buildWorkerSettings(
            Environment env, int batch, Duration renewalInterval) {
        return new ConsumptionWorker.Settings(
                batch,
                env.getProperty(PREFIX + "quantum", Integer.class, 1),
                duration(env, "min-poll", 10),
                duration(env, "max-poll", 1000),
                duration(env, "error-poll", 1000),
                Duration.ofSeconds(1),
                renewalInterval,
                Duration.ofSeconds(5),
                duration(env, "drain-timeout", 90000));
    }

    private static void verifyTimingBounds(
            LifecycleSettings life,
            ConsumptionWorker.Settings settings,
            Map<String, List<Integer>> partitions,
            int mw) {
        // Necessary nominal-batch check, not physical certification. Partial batches can
        // require more calls: qualification must count every candidate, include polling,
        // and observe all PostgreSQL deadlines with the measured whole-path envelope.
        int batch = life.batchSize();
        int leaseLots =
                partitions.values().stream().mapToInt(p -> (p.size() + batch - 1) / batch).sum();
        Duration leaseBound = settings.renewalInterval().plusSeconds(5L * leaseLots + 3);
        Duration claimBound = settings.renewalInterval().plusSeconds(5L * mw + 3);
        if (leaseBound.compareTo(life.leaseDuration()) >= 0
                || claimBound.compareTo(life.claimDuration()) >= 0) {
            throw new IllegalArgumentException(
                    "renewal budget exceeds pilot margins; explicit requalification required");
        }
        if (settings.drainDeadline().compareTo(Duration.ofSeconds(90)) > 0) {
            throw new IllegalArgumentException("dev drain must precede external shutdown deadline");
        }
    }

    private static String[] entries(String value, String separator) {
        return value.isEmpty() ? new String[0] : value.split(separator, -1);
    }

    private static Duration duration(Environment env, String key, long ms) {
        return Duration.ofMillis(Long.parseLong(env.getProperty(PREFIX + key, Long.toString(ms))));
    }

    public void validateTarget(Pool pool, DataSource source) {
        pools.validateTarget(pool, source);
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
            case "Command" ->
                    com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions()
                            .getFirst();
            case "I5ReadPotProbe" -> probeDefinitions().get(0);
            case "I5ReadCompletionProbe" -> probeDefinitions().get(1);
            default -> readParticipantDefinition(name);
        };
    }

    private static ConsumptionDefinition readParticipantDefinition(String name) {
        int index = READ_NAMES.indexOf(name);
        if (index < 0) throw new IllegalArgumentException("source/participant not delivered");
        int catalogIndex = index == 6 ? 5 : index < 3 ? index + 2 : index + 3;
        return com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions()
                .get(catalogIndex);
    }

    public List<ConsumptionDefinition> catalog() {
        var result = new ArrayList<>(com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog.definitions());
        if (technicalProbes) result.addAll(probeDefinitions());
        return List.copyOf(result);
    }
}
