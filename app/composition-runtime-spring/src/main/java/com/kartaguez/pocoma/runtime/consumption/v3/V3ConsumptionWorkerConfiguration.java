package com.kartaguez.pocoma.runtime.consumption.v3;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.*;
import com.kartaguez.pocoma.engine.consumption.v3.port.ConsumptionObservation;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.orchestrator.poll.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.runtime.command.v3.V3PotCommandComposition;
import com.kartaguez.pocoma.runtime.consumption.v3.dev.*;
import com.zaxxer.hikari.*;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.persistence.EntityManagerFactory;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.*;
import org.springframework.boot.health.contributor.*;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

import java.time.*;
import java.util.*;

/** Always imported preflight; only the inner resource configuration is profile-dependent. */
@Configuration(proxyBeanMethods = false)
@Import(V3ConsumptionWorkerConfiguration.Active.class)
public class V3ConsumptionWorkerConfiguration {
    @Bean
    static BeanFactoryPostProcessor v3WorkerPreflight(Environment env) {
        return new Preflight(env);
    }

    private record Preflight(Environment env) implements BeanFactoryPostProcessor {
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {

            boolean active = V3ConsumptionWorkerSettings.requested(env);
            var exact =
                    Set.of(
                            V3ConsumptionWorkerConfiguration.class.getName(),
                            Active.class.getName(),
                            Resources.class.getName(),
                            Preflight.class.getName(),
                            Lifecycle.class.getName(),
                            V3ConsumptionWorker.class.getName(),
                            WorkerCapacity.class.getName(),
                            V3ConsumptionWorkerSettings.class.getName(),
                            V3BootstrapGuard.class.getName(),
                            MicrometerV3ConsumptionObservation.class.getName(),
                            "com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication",
                            "com.kartaguez.pocoma.PocomaRuntimeMonolithApplication");
            for (var name : factory.getBeanDefinitionNames()) {
                var type = factory.getType(name, false);
                var definition = factory.getBeanDefinition(name);
                if (type == null) {
                    String owner = definition.getFactoryBeanName();
                    Class<?> ownerType = owner == null ? null : factory.getType(owner, false);
                    if (active
                            && ((definition.getBeanClassName() != null
                                            && definition
                                                    .getBeanClassName()
                                                    .startsWith("com.kartaguez.pocoma."))
                                    || (ownerType != null
                                            && ownerType
                                                    .getName()
                                                    .startsWith("com.kartaguez.pocoma."))))
                        throw new IllegalStateException(
                                "indeterminate application factory in I5: " + name);
                    continue;
                }
                String value = type.getName();
                boolean fixture =
                        V3TechnicalReadParticipant.class.isAssignableFrom(type)
                                || V3FixturePotActorAuthority.class.isAssignableFrom(type)
                                || value.startsWith(
                                        "com.kartaguez.pocoma.orchestrator.poll.consumption.v3.")
                                || type == Lifecycle.class
                                || type == Resources.class;
                if (!active && fixture)
                    throw new IllegalStateException("I5 component without explicit authorization");
                if (active && value.startsWith("com.kartaguez.pocoma.") && !exact.contains(value))
                    throw new IllegalStateException(
                            "unallocated application component in I5: " + value);
                if (active
                        && FactoryBean.class.isAssignableFrom(type)
                        && value.startsWith("com.kartaguez.pocoma."))
                    throw new IllegalStateException("application FactoryBean forbidden in I5");
            }
            if (!active) return;
            // These cannot be bypassed by importing an auto-configuration indirectly.
            if (env.getProperty("management.health.db.enabled", Boolean.class, true)
                    || env.getProperty("spring.jpa.open-in-view", Boolean.class, true)
                    || env.getProperty("spring.flyway.enabled", Boolean.class, true))
                throw new IllegalStateException(
                        "JDBC health, OSIV and runtime migration must be disabled");
            var settings = V3ConsumptionWorkerSettings.from(env);
            var w = settings.writeControl().preflightSource();
            var r = settings.readControl().preflightSource();
            settings.validateTarget(settings.writeControl(), w);
            settings.validateTarget(settings.readControl(), r);
            var guard =
                    V3BootstrapGuard.development(
                            new V3BootstrapGuard.Target(
                                    w,
                                    settings.writeControl().database(),
                                    settings.writeControl().schema(),
                                    settings.writeControl().username()),
                            new V3BootstrapGuard.Target(
                                    r,
                                    settings.readControl().database(),
                                    settings.readControl().schema(),
                                    settings.readControl().username()),
                            settings.catalog(),
                            settings.technicalProbes(),
                            settings.fixtureIdentity());
            guard.validateLifecyclePrivileges(V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
            if (settings.identityRequest()) guard.validateIdentitySource(w);
            factory.registerSingleton("v3WorkerSettings", settings);
            factory.registerSingleton("v3WorkerGuard", guard);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile(V3ConsumptionWorkerSettings.PROFILE)
    static class Active {
        @Bean(destroyMethod = "closeIfSafe")
        Resources v3WorkerResources(V3ConsumptionWorkerSettings settings, V3BootstrapGuard guard) {
            return new Resources(settings, guard);
        }

        @Bean(destroyMethod = "")
        HikariDataSource writeBusiness(Resources r) {
            return r.writeBusiness;
        }

        @Bean(destroyMethod = "")
        HikariDataSource writeControl(Resources r) {
            return r.writeControl;
        }

        @Bean(destroyMethod = "")
        HikariDataSource readBusiness(Resources r) {
            return r.readBusiness;
        }

        @Bean(destroyMethod = "")
        HikariDataSource readControl(Resources r) {
            return r.readControl;
        }

        @Bean
        WorkerCapacity v3WorkerCapacity(
                V3ConsumptionWorkerSettings settings,
                MicrometerV3ConsumptionObservation observation) {
            var capacity =
                    new WorkerCapacity(
                            settings.workerLimit(), settings.capacityLimits(), observation);
            observation.bindCapacity(capacity, settings.capacityLimits());
            return capacity;
        }

        @Bean
        MicrometerV3ConsumptionObservation v3WorkerObservation(MeterRegistry registry) {
            return new MicrometerV3ConsumptionObservation(registry);
        }

        @Bean
        V3ConsumptionWorker v3ConsumptionWorker(
                Resources resources,
                V3ConsumptionWorkerSettings settings,
                V3BootstrapGuard guard,
                WorkerCapacity capacity,
                ConsumptionObservation observation) {
            var bindings = new ArrayList<V3ConsumptionWorker.Binding>();
            var ds =
                    new DiscoveryEngine.Settings(
                            128,
                            settings.lifecycle().batchSize(),
                            settings.lifecycle().batchSize(),
                            64,
                            settings.lifecycle().lockTimeout(),
                            settings.lifecycle().statementTimeout());
            for (String name :
                    List.of(
                            "IdentityRequest",
                            "Command",
                            "I5ReadPotProbe",
                            "I5ReadCompletionProbe")) {
                if (!settings.consumptionLimits().containsKey(name)) continue;
                var definition = V3ConsumptionWorkerSettings.definition(name);
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
                            V3ConsumptionDiscoveryComposition.requests(
                                    guard,
                                    definition,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    ds,
                                    V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                } else if (name.equals("Command")) {
                    engine =
                            V3PotCommandComposition.assemble(
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    new V3FixturePotActorAuthority(resources.writeBusiness),
                                    Clock.systemUTC(),
                                    guard,
                                    settings.lifecycle(),
                                    capacity,
                                    observation);
                    discovery =
                            V3ConsumptionDiscoveryComposition.requests(
                                    guard,
                                    definition,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    ds,
                                    V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                } else {
                    engine =
                            V3ConsumptionLifecycleComposition.assemble(
                                    guard,
                                    definition,
                                    resources.readBusiness,
                                    resources.readEffects,
                                    new V3TechnicalReadParticipant(
                                            resources.readBusiness, definition),
                                    resources.readControl,
                                    resources.readTechnical,
                                    settings.lifecycle(),
                                    capacity,
                                    V3ConsumptionLifecycleComposition::classify,
                                    observation);
                    discovery =
                            V3ConsumptionDiscoveryComposition.events(
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
                        new V3ConsumptionWorker.Binding(
                                definition, engine, discovery, settings.partitions().get(name)));
            }
            var worker =
                    new V3ConsumptionWorker(
                            bindings,
                            capacity,
                            settings.capacityLimits(),
                            settings.polling(),
                            observation,
                            resources::close);
            resources.worker = worker;
            if (observation instanceof MicrometerV3ConsumptionObservation metrics)
                metrics.bind(worker);
            return worker;
        }

        @Bean
        Lifecycle v3WorkerLifecycle(
                V3ConsumptionWorker worker,
                Resources resources,
                ConfigurableListableBeanFactory factory) {
            return new Lifecycle(worker, resources, factory);
        }

        @Bean
        HealthIndicator v3WorkerHealth(V3ConsumptionWorker worker) {
            return () -> {
                var snapshot = worker.snapshot();
                var health =
                        snapshot.state() == V3ConsumptionWorker.State.RUNNING
                                ? Health.up()
                                : Health.down();
                return health.withDetail("workerState", snapshot.state().name())
                        .withDetail("pendingHandles", snapshot.handles())
                        .withDetail("renewalUnknown", snapshot.unknownRenewals())
                        .withDetail("drainDeadlineExceeded", snapshot.deadlineExceeded())
                        .build();
            };
        }
    }

    /**
     * Single owner of closing initialized resources. Spring timeout never proves physical drain.
     */
    static final class Resources {
        final HikariDataSource writeBusiness, writeControl, readBusiness, readControl;
        final LocalContainerEntityManagerFactoryBean emf;
        final SpringAttemptTransactions writeEffects, writeTechnical, readEffects, readTechnical;
        volatile V3ConsumptionWorker worker;
        private boolean closed;

        Resources(V3ConsumptionWorkerSettings settings, V3BootstrapGuard guard) {
            var opened = new ArrayList<HikariDataSource>();
            LocalContainerEntityManagerFactoryBean initialized = null;
            try {
                writeBusiness = open(settings, settings.writeBusiness(), opened);
                writeControl = open(settings, settings.writeControl(), opened);
                readBusiness = open(settings, settings.readBusiness(), opened);
                readControl = open(settings, settings.readControl(), opened);
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
                V3ConsumptionWorkerSettings settings,
                V3ConsumptionWorkerSettings.Pool spec,
                List<HikariDataSource> opened) {
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
            opened.add(pool);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (pool.getHikariPoolMXBean().getIdleConnections() != spec.maximum()
                    && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("pool initialization interrupted");
                }
            }
            if (pool.getHikariPoolMXBean().getIdleConnections() != spec.maximum())
                throw new IllegalStateException("physical pool reserve not established");
            return pool;
        }

        synchronized void closeIfSafe() {
            if (worker == null
                    || worker.snapshot().state() == V3ConsumptionWorker.State.STOPPED
                    || worker.snapshot().state() == V3ConsumptionWorker.State.STARTING) close();
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

    static final class Lifecycle implements SmartLifecycle {
        private final V3ConsumptionWorker worker;
        private final Resources resources;
        private final ConfigurableListableBeanFactory factory;

        Lifecycle(
                V3ConsumptionWorker worker,
                Resources resources,
                ConfigurableListableBeanFactory factory) {
            this.worker = worker;
            this.resources = resources;
            this.factory = factory;
        }

        public void start() {
            // Validate actual singleton identities after construction, before Worker threads or
            // DML.
            var expected =
                    Set.of(
                            resources.writeBusiness,
                            resources.writeControl,
                            resources.readBusiness,
                            resources.readControl);
            var actual =
                    Collections.newSetFromMap(new IdentityHashMap<javax.sql.DataSource, Boolean>());
            int capacities = 0;
            for (String name : factory.getSingletonNames()) {
                Object bean = factory.getSingleton(name);
                if (bean instanceof javax.sql.DataSource ds) actual.add(ds);
                if (bean instanceof WorkerCapacity) capacities++;
                if (bean instanceof org.springframework.transaction.PlatformTransactionManager
                        || bean instanceof EntityManagerFactory)
                    throw new IllegalStateException("unallocated transaction owner bean in I5");
                if (bean != null && bean.getClass().getName().startsWith("com.kartaguez.pocoma.")) {
                    String value = org.springframework.util.ClassUtils.getUserClass(bean).getName();
                    boolean known =
                            bean == worker
                                    || bean == resources
                                    || bean == this
                                    || bean instanceof WorkerCapacity
                                    || bean instanceof V3BootstrapGuard
                                    || bean instanceof V3ConsumptionWorkerSettings
                                    || bean instanceof MicrometerV3ConsumptionObservation
                                    || bean instanceof Preflight
                                    || bean instanceof V3ConsumptionWorkerConfiguration
                                    || bean instanceof Active
                                    || value.equals(
                                            "com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication")
                                    || value.equals(
                                            "com.kartaguez.pocoma.PocomaRuntimeMonolithApplication")
                                    || (name.equals("v3WorkerHealth")
                                            && bean instanceof HealthIndicator);
                    if (!known)
                        throw new IllegalStateException("unallocated active singleton in I5");
                }
            }
            if (capacities != 1 || !actual.equals(expected))
                throw new IllegalStateException("actual capacity/pools differ from preflight");
            worker.start();
        }

        public void stop() {
            worker.requestDrain();
        }

        public void stop(Runnable callback) {
            worker.requestDrain(
                    () -> {
                        org.slf4j.LoggerFactory.getLogger(V3ConsumptionWorkerConfiguration.class)
                                .info("I5_WORKER_STOPPED pid={}", ProcessHandle.current().pid());
                        callback.run();
                    });
        }

        public boolean isRunning() {
            return worker.isRunning();
        }

        public boolean isAutoStartup() {
            return true;
        }

        public int getPhase() {
            return Integer.MAX_VALUE - 100;
        }
    }
}
