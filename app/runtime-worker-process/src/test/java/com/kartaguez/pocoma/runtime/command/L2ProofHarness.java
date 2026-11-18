package com.kartaguez.pocoma.runtime.command;
import com.kartaguez.pocoma.compose.polling.process.*;
import com.kartaguez.pocoma.compose.polling.process.dev.FixturePotActorAuthority;
import com.kartaguez.pocoma.compose.polling.materialize.ReadParticipants;
import com.kartaguez.pocoma.contract.consumption.invocation.WorkerView;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.domain.consumption.ConsumptionDefinition;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.BootstrapGuard;
import com.kartaguez.pocoma.infra.postgres.write.identity.JdbcIdentityPotActorAuthority;
import com.kartaguez.pocoma.infra.observability.micrometer.*;
import com.kartaguez.pocoma.infra.security.spring.InvitationConfiguration;
import com.kartaguez.pocoma.infra.tx.spring.SpringAttemptTransactions;
import com.kartaguez.pocoma.orchestrator.consumption.*;
import com.kartaguez.pocoma.runtime.consumption.dev.TechnicalReadParticipant;
import com.kartaguez.pocoma.supra.polling.consumption.ConsumptionWorker;
import com.zaxxer.hikari.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.time.*;
import java.util.*;
/** Explicit mixed-authority PostgreSQL fixture, never an application configuration. */
final class L2ProofHarness {
        ConsumptionWorker consumptionWorker(
                Resources resources,
                L2ProofSettings settings,
                BootstrapGuard guard,
                WorkerCapacity capacity,
                ConsumptionObservation observation,
                org.springframework.core.env.Environment env,StageHandoffTracker tracker) {
            var bindings = new ArrayList<ConsumptionWorker.Binding>();
            var ds =
                    new DiscoverySettings(
                            128,
                            settings.lifecycle().batchSize(),
                            settings.lifecycle().batchSize(),
                            64,
                            settings.lifecycle().lockTimeout(),
                            settings.lifecycle().statementTimeout());
            var reads =
                    settings.readMode()
                            ? ReadParticipants.create(
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    resources.readBusiness,
                                    resources.readEffects)
                            : Map.<ReadParticipants.Name, ReadParticipants.Inert>of();
            Collection<String> assemblyOrder =
                    settings.readMode()
                            ? new TreeSet<>(settings.consumptionLimits().keySet())
                            : List.of(
                                    "IdentityRequest",
                                    "Command",
                                    "I5ReadPotProbe",
                                    "I5ReadCompletionProbe");
            for (String name : assemblyOrder) {
                if (!settings.consumptionLimits().containsKey(name)) continue;
                var definition = L2ProofSettings.definition(name);
                ConsumptionEngine engine;
                DiscoveryEngine discovery;
                if (name.equals("IdentityRequest")) {
                    engine =
                            IdentityRequestComposition.assemble(
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    guard,
                                    settings.lifecycle(),
                                    capacity,
                                    observation);
                    discovery =
                            com.kartaguez.pocoma.compose.polling.process.ConsumptionDiscoveryComposition.requests(
                                    guard,
                                    definition,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    ds,
                                    BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY, tracker);
                } else if (name.equals("Command")) {
                    boolean real = settings.identityAuthority().equals("real");
                    if (real) guard.validateIdentitySource(resources.writeBusiness);
                    engine =
                            PotCommandComposition.assemble(
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    real
                                            ? new JdbcIdentityPotActorAuthority(
                                                    resources.writeBusiness, resources.writeEffects)
                                            : new FixturePotActorAuthority(
                                                    resources.writeBusiness),
                                    Clock.systemUTC(),
                                    guard,
                                    settings.lifecycle(),
                                    capacity,
                                    observation,
                                    InvitationConfiguration.keys(env) == null
                                            ? null
                                            : new com.kartaguez.pocoma.infra.postgres.write
                                                    .JdbcInvitationStore(
                                                    resources.writeBusiness,
                                                    InvitationConfiguration.keys(env)), tracker::linkCommandEvents);
                    discovery =
                            com.kartaguez.pocoma.compose.polling.process.ConsumptionDiscoveryComposition.requests(
                                    guard,
                                    definition,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    ds,
                                    BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY, tracker);
                } else if (L2ProofSettings.READ_NAMES.contains(name)) {
                    var participant =
                            name.equals("MaterializeInvitation")
                                    ? ReadParticipants.invitation(
                                            resources.writeBusiness, resources.readBusiness)
                                    : reads.get(
                                            ReadParticipants.Name.values()[
                                                    L2ProofSettings.READ_NAMES.indexOf(
                                                            name)]);
                    engine =
                            com.kartaguez.pocoma.compose.polling.materialize.ConsumptionLifecycleComposition.assemble(
                                    guard,
                                    definition,
                                    resources.readBusiness,
                                    resources.readEffects,
                                    participant.participant(),
                                    resources.readControl,
                                    resources.readTechnical,
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    ds,
                                    settings.lifecycle(),
                                    capacity,
                                    com.kartaguez.pocoma.infra.tx.spring.ConsumptionFailureClassifier::classify,
                                    observation);
                    discovery =
                            com.kartaguez.pocoma.compose.polling.materialize.ConsumptionDiscoveryComposition.events(
                                    guard,
                                    definition,
                                    resources.readControl,
                                    resources.readTechnical,
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    ds,
                                    BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                } else {
                    engine =
                            com.kartaguez.pocoma.compose.polling.materialize.ConsumptionLifecycleComposition.assemble(
                                    guard,
                                    definition,
                                    resources.readBusiness,
                                    resources.readEffects,
                                    new TechnicalReadParticipant(
                                            resources.readBusiness, definition),
                                    resources.readControl,
                                    resources.readTechnical,
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    ds,
                                    settings.lifecycle(),
                                    capacity,
                                    com.kartaguez.pocoma.infra.tx.spring.ConsumptionFailureClassifier::classify,
                                    observation);
                    discovery =
                            com.kartaguez.pocoma.compose.polling.materialize.ConsumptionDiscoveryComposition.events(
                                    guard,
                                    definition,
                                    resources.readControl,
                                    resources.readTechnical,
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    ds,
                                    BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                }
                bindings.add(
                        new ConsumptionWorker.Binding(definition, com.kartaguez.pocoma.orchestrator.consumption.ConsumptionProtocols.forDefinition(definition, engine, discovery), settings.partitions().get(name)));
            }
            var worker =
                    new ConsumptionWorker(
                            bindings,
                            capacity,
                            settings.capacityLimits(),
                            settings.polling(),
                            observation,
                            resources::close,
                            tracker, com.kartaguez.pocoma.orchestrator.consumption.ConsumptionSafety::new);
            resources.worker = worker;
            if (observation instanceof MicrometerConsumptionObservation metrics)
                metrics.bind(worker);
            tracker.registerWakeSignal(worker::signal);
            return worker;
        }

    static final class Resources {
        final HikariDataSource writeBusiness, writeControl, readBusiness, readControl;
        final SpringAttemptTransactions writeEffects, writeTechnical, readEffects, readTechnical;
        volatile ConsumptionWorker worker;
        private boolean closed;

        Resources(L2ProofSettings settings, BootstrapGuard guard) {
            this(settings, guard, null);
        }

        Resources(L2ProofSettings settings, BootstrapGuard guard, MeterRegistry registry) {
            var opened = new ArrayList<HikariDataSource>();
            try {
                writeBusiness = open(settings, settings.writeBusiness(), opened, registry);
                writeControl = open(settings, settings.writeControl(), opened, registry);
                readBusiness = open(settings, settings.readBusiness(), opened, registry);
                readControl = open(settings, settings.readControl(), opened, registry);
                writeEffects = owner(writeBusiness, 60);
                writeTechnical = owner(writeControl, 3);
                readEffects = owner(readBusiness, 60);
                readTechnical = owner(readControl, 3);
                for (var pool : opened) {
                    var spec =
                            switch (pool.getPoolName()
                                    .substring(settings.workerName().length() + 1)) {
                                case "write-business" -> settings.writeBusiness();
                                case "write-control" -> settings.writeControl();
                                case "read-business" -> settings.readBusiness();
                                case "read-control" -> settings.readControl();
                                default -> throw new IllegalStateException("unknown pool");
                            };
                    settings.validateTarget(spec, pool);
                    guard.validateLifecycleSource(
                            pool,
                            spec.name().startsWith("write")
                                    ? ConsumptionDefinition.Authority.WRITE
                                    : ConsumptionDefinition.Authority.READ,
                            BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                }
            } catch (RuntimeException | Error failure) {
                for (var pool : opened)
                    try {
                        pool.close();
                    } catch (RuntimeException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                throw failure;
            }
        }

        private static SpringAttemptTransactions owner(HikariDataSource pool, int timeout) {
            var manager = new DataSourceTransactionManager(pool);
            manager.setNestedTransactionAllowed(false);
            return new SpringAttemptTransactions(pool, manager, timeout);
        }

        private static HikariDataSource open(
                L2ProofSettings settings,
                L2ProofSettings.Pool spec,
                List<HikariDataSource> opened,
                MeterRegistry registry) {
            var config = new HikariConfig();
            config.setPoolName(settings.workerName() + "-" + spec.name());
            config.setJdbcUrl(spec.url());
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
            var pool = new HikariDataSource(config);
            if (registry != null) {
                pool.setMetricsTrackerFactory(
                        new com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory(registry));
            }
            opened.add(pool);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (pool.getHikariPoolMXBean().getIdleConnections() < spec.minimumIdle()
                    && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("pool initialization interrupted");
                }
            }
            if (pool.getHikariPoolMXBean().getIdleConnections() < spec.minimumIdle())
                throw new IllegalStateException("physical pool reserve not established");
            return pool;
        }

        synchronized void closeIfSafe() {
            if (worker == null
                    || worker.snapshot().state() == WorkerView.State.STOPPED
                    || worker.snapshot().state() == WorkerView.State.STARTING) close();
        }

        synchronized void close() {
            if (closed) return;
            writeBusiness.close();
            readBusiness.close();
            writeControl.close();
            readControl.close();
            closed = true;
        }
    }

}
