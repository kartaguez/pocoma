package com.kartaguez.pocoma.compose.polling.process;

import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.WorkerPostgresSupport;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.WorkerPostgresSupport.AuthorityFlags;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.WorkerPostgresSupport.DevTarget;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.WorkerPostgresSupport.PoolSpec;
import com.kartaguez.pocoma.supra.polling.consumption.ConsumptionWorker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.core.env.Environment;

/** Closed development composition for WRITE Process worker. */
public record ProcessSettings(
        String workerName,
        int workerLimit,
        boolean fixtureIdentity,
        String identityAuthority,
        boolean identityRequest,
        boolean commandEnabled,
        boolean technicalProbes,
        Map<String, Integer> consumptionLimits,
        Map<String, List<Integer>> partitions,
        ProcessPoolSettings pools,
        ConsumptionWorker.Settings polling,
        LifecycleSettings lifecycle) {
    public static final String PROFILE = WorkerPostgresSupport.PROCESS_PROFILE;
    public static final List<String> READ_NAMES = WorkerPostgresSupport.READ_NAMES;
    static final String PREFIX = WorkerPostgresSupport.PREFIX;

    @Override
    public String toString() {
        return "ProcessSettings[" + workerName + ", M_W=" + workerLimit + "]";
    }

    public ProcessSettings {
        consumptionLimits = Map.copyOf(consumptionLimits);
        partitions = Map.copyOf(partitions);
    }

    public PoolSpec writeBusiness() {
        return pools.writeBusiness();
    }

    public PoolSpec writeControl() {
        return pools.writeControl();
    }

    public List<DevTarget> allowedTargets() {
        return pools.allowedTargets();
    }

    public static List<ConsumptionDefinition> probeDefinitions() {
        return ConsumptionCatalog.probeDefinitions();
    }

    public static boolean requested(Environment env) {
        return WorkerPostgresSupport.requested(env, PROFILE);
    }

    public static ProcessSettings from(Environment original) {
        final Environment env = WorkerPostgresSupport.namespaced(original, "pocoma.v3.process.");
        if (!requested(env)) throw new IllegalStateException("I5 not authorized");
        if (env.containsProperty(PREFIX + "read-participants")) {
            throw new IllegalArgumentException("read-participants requires v3-read-worker");
        }
        String name = WorkerPostgresSupport.requireValidWorkerName(env);
        int mw = WorkerPostgresSupport.positive(env, "worker-limit");
        boolean fixture = WorkerPostgresSupport.flag(env, "fixture-identity");
        boolean probes = WorkerPostgresSupport.flag(env, "technical-probes");
        if (probes) throw new IllegalArgumentException("Process cannot host READ technical probes");
        var flags = WorkerPostgresSupport.readAuthorityFlags(env, fixture);
        validateProcessWorkerName(name, flags);
        return buildValidatedSettings(env, name, mw, flags);
    }

    private static ProcessSettings buildValidatedSettings(
            Environment env, String name, int mw, AuthorityFlags flags) {
        var expected = collectExpectedConsumptions(flags);
        var limits = WorkerPostgresSupport.parseConsumptionLimits(env, expected);
        var partitions = WorkerPostgresSupport.parsePartitions(env, expected);
        var pools = ProcessPoolSettings.from(env, mw);
        int batch = WorkerPostgresSupport.requireValidBatchSize(env);
        var life = WorkerPostgresSupport.buildLifecycleSettings(env, batch, false);
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
                limits,
                partitions,
                pools,
                settings,
                life);
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

    private static ConsumptionWorker.Settings buildWorkerSettings(
            Environment env, int batch, Duration renewalInterval) {
        return new ConsumptionWorker.Settings(
                batch,
                env.getProperty(PREFIX + "quantum", Integer.class, 1),
                WorkerPostgresSupport.duration(env, "min-poll", 10),
                WorkerPostgresSupport.duration(env, "max-poll", 1000),
                WorkerPostgresSupport.duration(env, "error-poll", 1000),
                Duration.ofSeconds(1),
                renewalInterval,
                Duration.ofSeconds(5),
                WorkerPostgresSupport.duration(env, "drain-timeout", 90000));
    }

    private static void verifyTimingBounds(
            LifecycleSettings life,
            ConsumptionWorker.Settings settings,
            Map<String, List<Integer>> partitions,
            int mw) {
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

    public void validateTarget(PoolSpec pool, DataSource source) {
        pools.validateTarget(pool, source);
    }

    public Map<ConsumptionId, Integer> capacityLimits() {
        var result = new LinkedHashMap<ConsumptionId, Integer>();
        consumptionLimits.forEach((name, limit) -> result.put(definition(name).id(), limit));
        return Map.copyOf(result);
    }

    public static ConsumptionDefinition definition(String name) {
        return WorkerPostgresSupport.definition(name);
    }

    public List<ConsumptionDefinition> catalog() {
        var result = new ArrayList<>(ConsumptionCatalog.definitions());
        if (technicalProbes) result.addAll(probeDefinitions());
        return List.copyOf(result);
    }
}
