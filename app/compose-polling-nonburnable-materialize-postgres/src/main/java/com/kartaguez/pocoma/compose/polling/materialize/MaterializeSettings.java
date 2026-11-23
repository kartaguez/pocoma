package com.kartaguez.pocoma.compose.polling.materialize;

import com.kartaguez.pocoma.contract.consumption.spi.LifecycleSettings;
import com.kartaguez.pocoma.domain.consumption.ConsumptionCatalog;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition.Authority;
import com.kartaguez.pocoma.domain.consumption.ConsumptionId;
import com.kartaguez.pocoma.engine.materialize.pot.MaterializePotBalance;
import com.kartaguez.pocoma.engine.materialize.pot.MaterializePotVersion;
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

/** Closed development composition for READ Materialize worker. */
public record MaterializeSettings(
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
        MaterializePoolSettings pools,
        ConsumptionWorker.Settings polling,
        LifecycleSettings lifecycle,
        long potVersionMaxDeltaDistance,
        MaterializePotBalance.Settings potBalanceSettings) {
    public static final String PROFILE = WorkerPostgresSupport.PROCESS_PROFILE;
    public static final String READ_PROFILE = WorkerPostgresSupport.READ_PROFILE;
    public static final List<String> READ_NAMES = WorkerPostgresSupport.READ_NAMES;
    static final String PREFIX = WorkerPostgresSupport.PREFIX;

    @Override
    public String toString() {
        return "MaterializeSettings[" + workerName + ", M_W=" + workerLimit + "]";
    }

    public MaterializeSettings {
        readParticipants = List.copyOf(readParticipants);
        consumptionLimits = Map.copyOf(consumptionLimits);
        partitions = Map.copyOf(partitions);
    }

    public PoolSpec sourceRead() {
        return pools.sourceRead();
    }

    public PoolSpec readBusiness() {
        return pools.readBusiness();
    }

    public PoolSpec readControl() {
        return pools.readControl();
    }

    public List<DevTarget> allowedTargets() {
        return pools.allowedTargets();
    }

    public static List<ConsumptionDefinition> probeDefinitions() {
        return ConsumptionCatalog.probeDefinitions();
    }

    public static boolean requested(Environment env) {
        return WorkerPostgresSupport.requested(env, READ_PROFILE);
    }

    private record WorkerContext(
            String name,
            int mw,
            AuthorityFlags flags,
            boolean probes,
            List<String> reads) {}

    public static MaterializeSettings from(Environment original) {
        final Environment env = WorkerPostgresSupport.namespaced(original, "pocoma.v3.materialize.");
        if (!requested(env)) throw new IllegalStateException("I5 not authorized");
        String name = WorkerPostgresSupport.requireValidWorkerName(env);
        int mw = WorkerPostgresSupport.positive(env, "worker-limit");
        boolean fixture = WorkerPostgresSupport.flag(env, "fixture-identity");
        boolean probes = WorkerPostgresSupport.flag(env, "technical-probes");
        var flags = WorkerPostgresSupport.readAuthorityFlags(env, fixture);
        var reads = parseReadParticipants(env, flags, probes);
        validateMaterializeWorkerName(name, flags, reads);
        return buildValidatedSettings(env, new WorkerContext(name, mw, flags, probes, reads));
    }

    private static MaterializeSettings buildValidatedSettings(Environment env, WorkerContext ctx) {
        var expected = collectExpectedConsumptions(ctx.reads(), ctx.flags(), ctx.probes());
        var limits = WorkerPostgresSupport.parseConsumptionLimits(env, expected);
        var partitions = WorkerPostgresSupport.parsePartitions(env, expected);
        var pools = MaterializePoolSettings.from(env, ctx.mw());
        int batch = WorkerPostgresSupport.requireValidBatchSize(env);
        var life = WorkerPostgresSupport.buildLifecycleSettings(env, batch, true);
        var settings = buildWorkerSettings(env, batch, life.renewalInterval());
        long maxDeltaDistance =
                env.getProperty(
                        PREFIX + "pot-version.max-delta-distance",
                        Long.class,
                        MaterializePotVersion.DEFAULT_MAX_DELTA_DISTANCE);
        if (maxDeltaDistance < 0) {
            throw new IllegalArgumentException(
                    "non-negative pot-version.max-delta-distance required");
        }
        var potBalanceSettings =
                new MaterializePotBalance.Settings(
                        env.getProperty(
                                PREFIX + "pot-balance.max-delta-distance",
                                Long.class,
                                MaterializePotBalance.DEFAULT_MAX_DELTA_DISTANCE),
                        env.getProperty(
                                PREFIX + "pot-balance.max-changed-expenses",
                                Integer.class,
                                MaterializePotBalance.DEFAULT_MAX_CHANGED_EXPENSES),
                        env.getProperty(
                                PREFIX + "pot-balance.max-aggregate-buckets",
                                Integer.class,
                                MaterializePotBalance.DEFAULT_MAX_AGGREGATE_BUCKETS),
                        env.getProperty(
                                PREFIX + "pot-balance.max-aggregate-bucket-entries",
                                Integer.class,
                                MaterializePotBalance.DEFAULT_MAX_AGGREGATE_BUCKET_ENTRIES));
        verifyTimingBounds(life, settings, partitions, ctx.mw());
        return new MaterializeSettings(
                ctx.name(),
                ctx.mw(),
                ctx.flags().fixture(),
                ctx.flags().mode(),
                ctx.flags().identity(),
                ctx.flags().command(),
                ctx.probes(),
                true,
                ctx.reads(),
                limits,
                partitions,
                pools,
                settings,
                life,
                maxDeltaDistance,
                potBalanceSettings);
    }

    private static List<String> parseReadParticipants(
            Environment env, AuthorityFlags flags, boolean probes) {
        if (flags.fixture() || probes || !flags.mode().equals("real")) {
            throw new IllegalArgumentException(
                    "READ business profile requires real Identity and forbids probes/fixtures");
        }
        var reads = new ArrayList<String>();
        String selection = env.getRequiredProperty(PREFIX + "read-participants");
        if (selection.isEmpty()) return reads;
        for (String selected : selection.split(",", -1)) {
            if (!READ_NAMES.contains(selected) || reads.contains(selected)) {
                throw new IllegalArgumentException(
                        "unknown or duplicate READ participant: " + selected);
            }
            reads.add(selected);
        }
        return reads;
    }

    private static Set<String> collectExpectedConsumptions(
            List<String> reads, AuthorityFlags flags, boolean probes) {
        var expected = new LinkedHashSet<String>(reads);
        if (flags.identity()) expected.add("IdentityRequest");
        if (flags.command()) expected.add("Command");
        if (probes) expected.addAll(List.of("I5ReadPotProbe", "I5ReadCompletionProbe"));
        return expected;
    }

    private static void validateMaterializeWorkerName(
            String name, AuthorityFlags flags, List<String> reads) {
        if (flags.command() || flags.identity()) {
            throw new IllegalArgumentException("Materialize cannot activate WRITE participants");
        }
        if (Set.of("command-worker", "pocoma-command-worker").contains(name)) {
            throw new IllegalArgumentException("Command worker cannot activate read participants");
        }
        if (Set.of("results-worker", "pocoma-results-worker").contains(name)) {
            var resultTypes = Set.of("MaterializeCommandResult", "MaterializeIdentityRequestResult");
            if (!resultTypes.containsAll(reads) || !reads.containsAll(resultTypes)) {
                throw new IllegalArgumentException(
                        "Results worker must activate exclusively MaterializeCommandResult and"
                                + " MaterializeIdentityRequestResult");
            }
            return;
        }
        if (Set.of("business-read-worker", "pocoma-business-read-worker").contains(name)) {
            if (reads.contains("MaterializeCommandResult")
                    || reads.contains("MaterializeIdentityRequestResult")) {
                throw new IllegalArgumentException(
                        "Business READ worker cannot activate result projections");
            }
            return;
        }
        if (Set.of("critical-read-worker", "pocoma-critical-read-worker").contains(name)) {
            var criticalTypes =
                    Set.of(
                            "MaterializeCommandResult",
                            "MaterializeIdentityRequestResult",
                            "MaterializeCurrentBinding");
            if (!criticalTypes.containsAll(reads) || !reads.containsAll(criticalTypes)) {
                throw new IllegalArgumentException(
                        "Critical READ worker (T1A) must activate exclusively"
                                + " MaterializeCommandResult, MaterializeIdentityRequestResult"
                                + " and MaterializeCurrentBinding");
            }
            return;
        }
        if (Set.of("pot-read-worker", "pocoma-pot-read-worker").contains(name)) {
            if (reads.contains("MaterializeCommandResult")
                    || reads.contains("MaterializeIdentityRequestResult")
                    || reads.contains("MaterializeCurrentBinding")) {
                throw new IllegalArgumentException(
                        "Pot READ worker (T1A) cannot activate critical result or binding projections");
            }
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
        Duration leaseBound = Duration.ZERO;
        int batch = life.batchSize();
        for (var authority : Authority.values()) {
            int candidates = 0;
            for (var entry : partitions.entrySet()) {
                if (definition(entry.getKey()).authority() == authority) {
                    candidates += entry.getValue().size();
                }
            }
            if (candidates == 0) continue;
            int batches = (candidates + batch - 1) / batch;
            var bound =
                    settings.renewalInterval()
                            .plus(settings.renewalBudget().multipliedBy(batches))
                            .plus(settings.minPoll().multipliedBy(batches))
                            .plusSeconds(4);
            if (bound.compareTo(leaseBound) > 0) leaseBound = bound;
        }
        if (leaseBound.compareTo(life.leaseDuration()) >= 0
                || settings.renewalInterval()
                                .plusSeconds(5L * mw + 3)
                                .compareTo(life.claimDuration())
                        >= 0) {
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
