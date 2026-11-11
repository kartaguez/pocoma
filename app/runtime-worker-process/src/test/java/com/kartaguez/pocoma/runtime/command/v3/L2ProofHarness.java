package com.kartaguez.pocoma.runtime.command.v3;
import com.kartaguez.pocoma.compose.polling.process.v3.*;
import com.kartaguez.pocoma.compose.polling.process.v3.dev.V3FixturePotActorAuthority;
import com.kartaguez.pocoma.compose.polling.materialize.v3.V3ReadParticipants;
import com.kartaguez.pocoma.contract.consumption.invocation.WorkerView;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.domain.consumption.v3.ConsumptionDefinition;
import com.kartaguez.pocoma.infra.postgres.core.v3.V3BootstrapGuard;
import com.kartaguez.pocoma.infra.postgres.write.v3.identity.JdbcIdentityPotActorAuthority;
import com.kartaguez.pocoma.infra.observability.micrometer.v3.*;
import com.kartaguez.pocoma.infra.security.spring.v3.V3InvitationConfiguration;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.orchestrator.consumption.v3.*;
import com.kartaguez.pocoma.runtime.consumption.v3.dev.V3TechnicalReadParticipant;
import com.kartaguez.pocoma.supra.polling.consumption.v3.V3ConsumptionWorker;
import com.zaxxer.hikari.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import java.time.*;
import java.util.*;
/** Explicit mixed-authority PostgreSQL fixture, never an application configuration. */
final class L2ProofHarness {
        V3ConsumptionWorker v3ConsumptionWorker(
                Resources resources,
                L2ProofSettings settings,
                V3BootstrapGuard guard,
                WorkerCapacity capacity,
                ConsumptionObservation observation,
                org.springframework.core.env.Environment env,StageHandoffTracker tracker) {
            var bindings = new ArrayList<V3ConsumptionWorker.Binding>();
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
                            ? V3ReadParticipants.create(
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    resources.readBusiness,
                                    resources.readEffects)
                            : Map.<V3ReadParticipants.Name, V3ReadParticipants.Inert>of();
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
                            V3IdentityRequestComposition.assemble(
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    guard,
                                    settings.lifecycle(),
                                    capacity,
                                    observation);
                    discovery =
                            com.kartaguez.pocoma.compose.polling.process.v3.V3ConsumptionDiscoveryComposition.requests(
                                    guard,
                                    definition,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    ds,
                                    V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY, tracker);
                } else if (name.equals("Command")) {
                    boolean real = settings.identityAuthority().equals("real");
                    if (real) guard.validateIdentitySource(resources.writeBusiness);
                    engine =
                            V3PotCommandComposition.assemble(
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    real
                                            ? new JdbcIdentityPotActorAuthority(
                                                    resources.writeBusiness, resources.writeEffects)
                                            : new V3FixturePotActorAuthority(
                                                    resources.writeBusiness),
                                    Clock.systemUTC(),
                                    guard,
                                    settings.lifecycle(),
                                    capacity,
                                    observation,
                                    V3InvitationConfiguration.keys(env) == null
                                            ? null
                                            : new com.kartaguez.pocoma.infra.postgres.write
                                                    .v3.JdbcInvitationStore(
                                                    resources.writeBusiness,
                                                    V3InvitationConfiguration.keys(env)), tracker::linkCommandEvents);
                    discovery =
                            com.kartaguez.pocoma.compose.polling.process.v3.V3ConsumptionDiscoveryComposition.requests(
                                    guard,
                                    definition,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    ds,
                                    V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY, tracker);
                } else if (L2ProofSettings.READ_NAMES.contains(name)) {
                    var participant =
                            name.equals("MaterializeInvitation")
                                    ? V3ReadParticipants.invitation(
                                            resources.writeBusiness, resources.readBusiness)
                                    : reads.get(
                                            V3ReadParticipants.Name.values()[
                                                    L2ProofSettings.READ_NAMES.indexOf(
                                                            name)]);
                    engine =
                            com.kartaguez.pocoma.compose.polling.materialize.v3.V3ConsumptionLifecycleComposition.assemble(
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
                                    com.kartaguez.pocoma.infra.tx.spring.v3.ConsumptionFailureClassifier::classify,
                                    observation);
                    discovery =
                            com.kartaguez.pocoma.compose.polling.materialize.v3.V3ConsumptionDiscoveryComposition.events(
                                    guard,
                                    definition,
                                    resources.readControl,
                                    resources.readTechnical,
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    ds,
                                    V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                } else {
                    engine =
                            com.kartaguez.pocoma.compose.polling.materialize.v3.V3ConsumptionLifecycleComposition.assemble(
                                    guard,
                                    definition,
                                    resources.readBusiness,
                                    resources.readEffects,
                                    new V3TechnicalReadParticipant(
                                            resources.readBusiness, definition),
                                    resources.readControl,
                                    resources.readTechnical,
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    ds,
                                    settings.lifecycle(),
                                    capacity,
                                    com.kartaguez.pocoma.infra.tx.spring.v3.ConsumptionFailureClassifier::classify,
                                    observation);
                    discovery =
                            com.kartaguez.pocoma.compose.polling.materialize.v3.V3ConsumptionDiscoveryComposition.events(
                                    guard,
                                    definition,
                                    resources.readControl,
                                    resources.readTechnical,
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    ds,
                                    V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                }
                bindings.add(
                        new V3ConsumptionWorker.Binding(definition, com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionProtocols.forDefinition(definition, engine, discovery), settings.partitions().get(name)));
            }
            var worker =
                    new V3ConsumptionWorker(
                            bindings,
                            capacity,
                            settings.capacityLimits(),
                            settings.polling(),
                            observation,
                            resources::close,
                            tracker, com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionSafety::new);
            resources.worker = worker;
            if (observation instanceof MicrometerV3ConsumptionObservation metrics)
                metrics.bind(worker);
            tracker.registerWakeSignal(worker::signal);
            return worker;
        }

    static final class Resources {
        final HikariDataSource writeBusiness, writeControl, readBusiness, readControl;
        final LocalContainerEntityManagerFactoryBean emf;
        final SpringAttemptTransactions writeEffects, writeTechnical, readEffects, readTechnical;
        volatile V3ConsumptionWorker worker;
        private boolean closed;

        Resources(L2ProofSettings settings, V3BootstrapGuard guard) {
            this(settings, guard, null);
        }

        Resources(L2ProofSettings settings, V3BootstrapGuard guard, MeterRegistry registry) {
            var opened = new ArrayList<HikariDataSource>();
            LocalContainerEntityManagerFactoryBean initialized = null;
            try {
                writeBusiness = open(settings, settings.writeBusiness(), opened, registry);
                writeControl = open(settings, settings.writeControl(), opened, registry);
                readBusiness = open(settings, settings.readBusiness(), opened, registry);
                readControl = open(settings, settings.readControl(), opened, registry);
                emf = new LocalContainerEntityManagerFactoryBean();
                initialized = emf;
                emf.setDataSource(writeBusiness);
                emf.setPersistenceUnitName("v3-write-business");
                emf.setManagedTypes(
                        org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of());
                emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                emf.setJpaPropertyMap(
                        Map.of(
                                "hibernate.hbm2ddl.auto",
                                "none",
                                "hibernate.boot.allow_jdbc_metadata_access",
                                "false",
                                "hibernate.dialect",
                                "org.hibernate.dialect.PostgreSQLDialect"));
                emf.afterPropertiesSet();
                var manager = new JpaTransactionManager(Objects.requireNonNull(emf.getObject()));
                manager.setDataSource(writeBusiness);
                manager.setNestedTransactionAllowed(false);
                writeEffects = new SpringAttemptTransactions(writeBusiness, manager, 60);
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
                            V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                }
            } catch (RuntimeException | Error failure) {
                if (initialized != null)
                    try {
                        initialized.destroy();
                    } catch (RuntimeException cleanup) {
                        failure.addSuppressed(cleanup);
                    }
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
            emf.destroy();
            writeBusiness.close();
            readBusiness.close();
            writeControl.close();
            readControl.close();
            closed = true;
        }
    }

}
