package com.kartaguez.pocoma.runtime.consumption.v3;
import com.kartaguez.pocoma.contract.consumption.invocation.WorkerView;
import com.kartaguez.pocoma.contract.consumption.spi.DiscoverySettings;
import com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.DiscoveryEngine;
import com.kartaguez.pocoma.orchestrator.consumption.v3.WorkerCapacity;
import com.kartaguez.pocoma.contract.readquery.IdentityResultRead;
import com.kartaguez.pocoma.contract.readquery.CommandResultRead;
import com.kartaguez.pocoma.contract.readquery.BindingRead;
import com.kartaguez.pocoma.contract.readquery.InvitationRead;
import com.kartaguez.pocoma.contract.readquery.PotRead;

import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.v3.identity.JdbcIdentityPotActorAuthority;
import com.kartaguez.pocoma.infra.tx.spring.v3.SpringAttemptTransactions;
import com.kartaguez.pocoma.supra.polling.consumption.v3.V3ConsumptionWorker;
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
@Import({V3ConsumptionWorkerConfiguration.Active.class, V3ReadFacadeConfiguration.class})
public class V3ConsumptionWorkerConfiguration {
    @Bean
    static BeanFactoryPostProcessor v3WorkerPreflight(Environment env) {
        return new Preflight(env);
    }

    private static Set<String> admissionTypes() {
        return Set.of(
                "com.kartaguez.pocoma.runtime.consumption.v3.V3AdmissionHttpConfiguration",
                "com.kartaguez.pocoma.runtime.consumption.v3.V3AdmissionHttpConfiguration$Resources",
                "com.kartaguez.pocoma.runtime.consumption.v3.V3AdmissionHttpConfiguration$Preflight",
                "com.kartaguez.pocoma.engine.admit.command.v3.AdmitCommandV3",
                "com.kartaguez.pocoma.engine.admit.identity.v3.AdmitIdentityRequestV3",
                "com.kartaguez.pocoma.engine.admit.identity.v3.AdmitLinkProposal",
                "com.kartaguez.pocoma.supra.web.write.v3.V3LinkProposalController",
                "com.kartaguez.pocoma.supra.web.write.v3.V3AdmissionController",
                "com.kartaguez.pocoma.supra.web.write.v3.V3AdmissionTransport");
    }

    private record Preflight(Environment env) implements BeanFactoryPostProcessor {
        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory factory) {

            boolean active = V3ConsumptionWorkerSettings.requested(env);
            var exact =
                    new HashSet<>(
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
                                    StageHandoffTracker.class.getName(),
                                    "com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication",
                                    "com.kartaguez.pocoma.PocomaRuntimeMonolithApplication"));
            boolean readMode =
                    Arrays.asList(env.getActiveProfiles())
                            .contains(V3ConsumptionWorkerSettings.READ_PROFILE);
            if (readMode)
                exact.addAll(
                        Set.of(
                                V3ReadFacadeConfiguration.class.getName(),
                                V3ReadFacadeComposition.Services.class.getName(),
                                "com.kartaguez.pocoma.PocomaTaskConsumptionWorkerApplication",
                                com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceState.class.getName(),
                                com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceAuthority.class.getName(),
                                com.kartaguez.pocoma.runtime.consumption.v3.maintenance.JdbcReadMaintenanceAuthority.class.getName(),
                                com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadinessOracle.class.getName(),
                                com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadRebuildObservation.class.getName()));
            if (readMode
                    && Arrays.asList(env.getActiveProfiles())
                            .contains(V3ReadHttpConfiguration.PROFILE))
                exact.addAll(
                        Set.of(
                                V3HttpSecurityConfiguration.class.getName(),
                                V3HttpSecurityConfiguration.PrincipalResolver.class.getName(),
                                V3ReadHttpConfiguration.class.getName(),
                                V3ReadHttpConfiguration.PrincipalResolver.class.getName(),
                                V3ReadHttpConfiguration.PotQueries.class.getName(),
                                com.kartaguez.pocoma.contract.readquery.PotRead.Queries.class
                                        .getName(),
                                V3ReadHttpConfiguration.HttpPreflight.class.getName(),
                                com.kartaguez.pocoma.supra.web.read.v3.V3ReadController.class
                                        .getName(),
                                com.kartaguez.pocoma.supra.web.read.v3.V3InvitationController.class
                                        .getName(),
                                com.kartaguez.pocoma.contract.readquery.InvitationRead.Queries.class
                                        .getName(),
                                V3ReadHttpConfiguration.InvitationQueries.class.getName(),
                                com.kartaguez.pocoma.supra.web.read.v3.V3ReadTransport.class
                                        .getName(),
                                com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceAuthority.class
                                        .getName(),
                                com.kartaguez.pocoma.runtime.consumption.v3.maintenance.JdbcReadMaintenanceAuthority.class
                                        .getName(),
                                "com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceFilter",
                                com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadRebuildObservation.class
                                        .getName(),
                                com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadinessOracle.class
                                        .getName(),
                                com.kartaguez.pocoma.contract.readquery.BindingRead.Query.class
                                        .getName(),
                                com.kartaguez.pocoma.contract.readquery.CommandResultRead.Query.class
                                        .getName(),
                                com.kartaguez.pocoma.contract.readquery.IdentityResultRead.Query.class
                                        .getName(),
                                "com.kartaguez.pocoma.runtime.consumption.v3.V3StageHandoffController"));
            boolean admission =
                    readMode
                            && Arrays.asList(env.getActiveProfiles()).contains("v3-http-read")
                            && Arrays.asList(env.getActiveProfiles()).contains("v3-http-admission");
            if (admission) exact.addAll(admissionTypes());
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
                                        "com.kartaguez.pocoma.supra.polling.consumption.v3.")
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
            if (readMode) {
                V3ReadFacadeConfiguration.limits(env); // Validate before any resource allocation.
                if (Arrays.stream(factory.getBeanDefinitionNames())
                        .map(n -> factory.getType(n, false))
                        .filter(Objects::nonNull)
                        .anyMatch(
                                t ->
                                        t.getName()
                                                .equals(
                                                        "com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication")))
                    throw new IllegalStateException("READ profile forbidden on Command root");
                boolean projection =
                        Arrays.stream(factory.getBeanDefinitionNames())
                                .map(n -> factory.getType(n, false))
                                .filter(Objects::nonNull)
                                .anyMatch(
                                        t ->
                                                t.getName()
                                                        .equals(
                                                                "com.kartaguez.pocoma.PocomaTaskConsumptionWorkerApplication"));
                if (projection
                        && Arrays.asList(env.getActiveProfiles())
                                .contains(V3ReadHttpConfiguration.PROFILE))
                    throw new IllegalStateException("HTTP READ requires the WEB or Monolith root");
                if (projection && (settings.commandEnabled() || settings.identityRequest()))
                    throw new IllegalStateException(
                            "PROJECTION cannot activate WRITE participants");
            }
            var w = settings.writeControl().preflightSource();
            var r = settings.readControl().preflightSource();
            settings.validateTarget(settings.writeControl(), w);
            settings.validateTarget(settings.readControl(), r);
            var guard =
                    guard(
                            settings,
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
                            settings.catalog());
            guard.validateLifecyclePrivileges(V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
            if (settings.identityAuthority().equals("real")) guard.validateIdentitySource(w);
            factory.registerSingleton("v3WorkerSettings", settings);
            factory.registerSingleton("v3WorkerGuard", guard);
        }
    }

    private static V3BootstrapGuard guard(
            V3ConsumptionWorkerSettings settings,
            V3BootstrapGuard.Target write,
            V3BootstrapGuard.Target read,
            List<ConsumptionDefinition> catalog) {
        return settings.readMode()
                ? V3BootstrapGuard.readWorker(write, read, catalog)
                : V3BootstrapGuard.development(
                        write,
                        read,
                        catalog,
                        settings.technicalProbes(),
                        settings.fixtureIdentity());
    }

    /** Empty selections still allocate consultation resources but no worker/capacity/lifecycle. */
    static class HasPairs implements Condition {
        public boolean matches(
                ConditionContext context,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            return !V3ConsumptionWorkerSettings.from(context.getEnvironment())
                    .consumptionLimits()
                    .isEmpty();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Profile({V3ConsumptionWorkerSettings.PROFILE, V3ConsumptionWorkerSettings.READ_PROFILE})
    static class Active {
        @Bean(destroyMethod = "closeIfSafe")
        Resources v3WorkerResources(
                V3ConsumptionWorkerSettings settings,
                V3BootstrapGuard guard,
                Environment env,
                Optional<MeterRegistry> meterRegistry) {
            var resources = new Resources(settings, guard, meterRegistry.orElse(null));
            try {
                if (settings.readParticipants().contains("MaterializeInvitation")
                        || env.getProperty("pocoma.v3.invitation.current-key") != null)
                    V3InvitationConfiguration.requireInstalled(
                            resources.writeBusiness, resources.readBusiness);
                return resources;
            } catch (RuntimeException failure) {
                resources.closeIfSafe();
                throw failure;
            }
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
        @Profile(V3ConsumptionWorkerSettings.READ_PROFILE)
        com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceAuthority v3WorkerReadMaintenanceAuthority(
                Resources r) {
            return new com.kartaguez.pocoma.runtime.consumption.v3.maintenance.JdbcReadMaintenanceAuthority(
                    r.readBusiness);
        }

        @Bean
        @Conditional(HasPairs.class)
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
        @Conditional(HasPairs.class)
        MicrometerV3ConsumptionObservation v3WorkerObservation(MeterRegistry registry) {
            StageHandoffTracker.getInstance().bindRegistry(registry);
            return new MicrometerV3ConsumptionObservation(registry);
        }

        @Bean
        @Conditional(HasPairs.class)
        V3ConsumptionWorker v3ConsumptionWorker(
                Resources resources,
                V3ConsumptionWorkerSettings settings,
                V3BootstrapGuard guard,
                WorkerCapacity capacity,
                ConsumptionObservation observation,
                org.springframework.core.env.Environment env) {
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
                                            : new com.kartaguez.pocoma.infra.persistence.primary.jpa
                                                    .v3.JdbcInvitationStore(
                                                    resources.writeBusiness,
                                                    V3InvitationConfiguration.keys(env)));
                    discovery =
                            V3ConsumptionDiscoveryComposition.requests(
                                    guard,
                                    definition,
                                    resources.writeControl,
                                    resources.writeTechnical,
                                    ds,
                                    V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                } else if (V3ConsumptionWorkerSettings.READ_NAMES.contains(name)) {
                    var participant =
                            name.equals("MaterializeInvitation")
                                    ? V3ReadParticipants.invitation(
                                            resources.writeBusiness, resources.readBusiness)
                                    : reads.get(
                                            V3ReadParticipants.Name.values()[
                                                    V3ConsumptionWorkerSettings.READ_NAMES.indexOf(
                                                            name)]);
                    engine =
                            V3ConsumptionLifecycleComposition.assemble(
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
                                    resources.writeBusiness,
                                    resources.writeEffects,
                                    ds,
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
                            StageHandoffTracker.getInstance(), com.kartaguez.pocoma.orchestrator.consumption.v3.ConsumptionSafety::new);
            resources.worker = worker;
            if (observation instanceof MicrometerV3ConsumptionObservation metrics)
                metrics.bind(worker);
            StageHandoffTracker.getInstance().registerWakeSignal(worker::signal);
            return worker;
        }

        @Bean
        org.springframework.beans.factory.SmartInitializingSingleton v3ResourceValidation(
                Resources resources, ConfigurableListableBeanFactory factory) {
            return () -> Lifecycle.validate(resources, factory);
        }

        @Bean
        @Conditional(HasPairs.class)
        Lifecycle v3WorkerLifecycle(
                V3ConsumptionWorker worker,
                Resources resources,
                ConfigurableListableBeanFactory factory) {
            return new Lifecycle(worker, resources, factory);
        }

        @Bean
        @Conditional(HasPairs.class)
        HealthIndicator v3WorkerHealth(V3ConsumptionWorker worker) {
            return () -> {
                var snapshot = worker.snapshot();
                var health =
                        snapshot.state() == WorkerView.State.RUNNING
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
            this(settings, guard, null);
        }

        Resources(V3ConsumptionWorkerSettings settings, V3BootstrapGuard guard, MeterRegistry registry) {
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
                V3ConsumptionWorkerSettings settings,
                V3ConsumptionWorkerSettings.Pool spec,
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
            validate(resources, factory);
            worker.start();
        }

        static void validate(Resources resources, ConfigurableListableBeanFactory factory) {
            // Validate actual singleton identities after construction, before Worker threads or
            // DML.
            var expected =
                    new HashSet<javax.sql.DataSource>(
                            Set.of(
                                    resources.writeBusiness,
                                    resources.writeControl,
                                    resources.readBusiness,
                                    resources.readControl));
            boolean admission =
                    Arrays.asList(factory.getBean(Environment.class).getActiveProfiles())
                            .contains("v3-http-admission");
            if (admission)
                expected.add(factory.getBean("v3AdmissionPool", javax.sql.DataSource.class));
            var actual =
                    Collections.newSetFromMap(new IdentityHashMap<javax.sql.DataSource, Boolean>());
            int capacities = 0;
            boolean http =
                    Arrays.asList(factory.getBean(Environment.class).getActiveProfiles())
                            .contains(V3ReadHttpConfiguration.PROFILE);
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
                            bean == resources.worker
                                    || bean == resources
                                    || bean instanceof Lifecycle
                                    || bean instanceof WorkerCapacity
                                    || bean instanceof V3BootstrapGuard
                                    || bean instanceof V3ConsumptionWorkerSettings
                                    || bean instanceof MicrometerV3ConsumptionObservation
                                    || bean instanceof Preflight
                                    || bean instanceof V3ConsumptionWorkerConfiguration
                                    || bean instanceof Active
                                    || bean instanceof V3ReadFacadeConfiguration
                                    || bean instanceof V3ReadFacadeComposition.Services
                                    || bean instanceof com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceAuthority
                                    || value.equals("com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadMaintenanceFilter")
                                    || bean instanceof com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadRebuildObservation
                                    || bean instanceof com.kartaguez.pocoma.runtime.consumption.v3.maintenance.ReadinessOracle
                                    || value.equals(
                                            "com.kartaguez.pocoma.PocomaTaskConsumptionWorkerApplication")
                                    || value.equals(
                                            "com.kartaguez.pocoma.PocomaCommandConsumptionWorkerApplication")
                                    || value.equals(
                                            "com.kartaguez.pocoma.PocomaRuntimeMonolithApplication")
                                    || (http
                                            && (bean instanceof V3HttpSecurityConfiguration
                                                    || bean
                                                            instanceof
                                                            V3HttpSecurityConfiguration
                                                                    .PrincipalResolver
                                                    || bean instanceof V3ReadHttpConfiguration
                                                    || bean
                                                            instanceof
                                                            V3ReadHttpConfiguration.HttpPreflight
                                                    || bean
                                                            instanceof
                                                            V3ReadHttpConfiguration
                                                                    .PrincipalResolver
                                                    || (name.equals("v3HttpPot")
                                                            && bean
                                                                    instanceof
                                                                    V3ReadHttpConfiguration
                                                                            .PotQueries)
                                                    || (name.equals("v3HttpInvitations")
                                                            && bean
                                                                    instanceof
                                                                    V3ReadHttpConfiguration
                                                                            .InvitationQueries)
                                                    || bean
                                                            instanceof
                                                            com.kartaguez.pocoma.supra.web.read.v3
                                                                    .V3InvitationController
                                                    || bean
                                                            instanceof
                                                            com.kartaguez.pocoma.supra.web.read.v3
                                                                    .V3ReadController
                                                    || bean
                                                            instanceof
                                                            com.kartaguez.pocoma.runtime.consumption.v3
                                                                    .V3StageHandoffController
                                                    || bean
                                                            instanceof
                                                            com.kartaguez.pocoma.supra.web.read.v3
                                                                    .V3ReadTransport
                                                    || (name.equals("v3HttpBinding")
                                                            && bean
                                                                    instanceof
                                                                    com.kartaguez.pocoma.contract.readquery.BindingRead.Query)
                                                    || (name.equals("v3HttpCommandResult")
                                                            && bean
                                                                    instanceof
                                                                    com.kartaguez.pocoma.contract.readquery.CommandResultRead.Query)
                                                    || (name.equals("v3HttpIdentityResult")
                                                            && bean
                                                                    instanceof
                                                                    com.kartaguez.pocoma.contract.readquery.IdentityResultRead.Query)
                                                    || (name.equals("v3HttpServerBounds")
                                                            && bean
                                                                    instanceof
                                                                    org.springframework.boot.web
                                                                            .server
                                                                            .WebServerFactoryCustomizer)))
                                    || (http && admission && admissionTypes().contains(value))
                                    || name.equals("v3ResourceValidation")
                                    || (name.equals("v3WorkerHealth")
                                            && bean instanceof HealthIndicator);
                    if (!known)
                        throw new IllegalStateException(
                                "unallocated active singleton in I5: " + name + "/" + value);
                }
            }
            int expectedCapacities =
                    factory.getBean(V3ConsumptionWorkerSettings.class).consumptionLimits().isEmpty()
                            ? 0
                            : 1;
            if (capacities != expectedCapacities || !actual.equals(expected))
                throw new IllegalStateException("actual capacity/pools differ from preflight");
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
