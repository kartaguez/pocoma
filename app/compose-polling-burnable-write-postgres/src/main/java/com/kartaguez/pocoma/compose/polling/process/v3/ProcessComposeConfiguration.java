package com.kartaguez.pocoma.compose.polling.process.v3;

import com.kartaguez.pocoma.contract.consumption.invocation.*;
import com.kartaguez.pocoma.contract.consumption.spi.*;
import com.kartaguez.pocoma.domain.consumption.v3.*;
import com.kartaguez.pocoma.orchestrator.consumption.v3.*;
import com.kartaguez.pocoma.supra.polling.consumption.v3.V3ConsumptionWorker;
import com.kartaguez.pocoma.infra.postgres.core.v3.*;
import com.kartaguez.pocoma.infra.tx.spring.v3.*;
import com.kartaguez.pocoma.infra.observability.micrometer.v3.*;
import com.zaxxer.hikari.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.time.*;
import java.util.*;
import com.kartaguez.pocoma.infra.postgres.write.v3.*;
import com.kartaguez.pocoma.infra.postgres.write.v3.identity.JdbcIdentityPotActorAuthority;
import com.kartaguez.pocoma.infra.security.spring.v3.V3InvitationConfiguration;
import com.kartaguez.pocoma.compose.polling.process.v3.dev.V3FixturePotActorAuthority;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;

/** Explicit process selection. No runtime scan, migration or unrelated authority is activated. */
@Configuration(proxyBeanMethods=false)
@Profile("v3-consumption-worker-dev")
@Import(ProcessObservationConfiguration.class)
public class ProcessComposeConfiguration {
    @Bean static BeanFactoryPostProcessor processPreflight(Environment env) {
        return factory -> {
            for(String disabled:List.of("spring.flyway.enabled","spring.jpa.open-in-view","management.health.db.enabled"))
                if(!"false".equals(env.getProperty(disabled))) throw new IllegalStateException("Explicitly disable "+disabled);
            ProcessSettings.from(env);
            var authorities = factory.getBeanNamesForType(
                    com.kartaguez.pocoma.engine.write.pot.port.execution.v3.PotActorAuthority.class, false, false);
            if (authorities.length > 0)
                throw new IllegalStateException("Process owns its Pot actor authority");
            for (String name : factory.getBeanDefinitionNames()) {
                var factoryType = factory.getType("&" + name, false);
                if (factoryType == null
                        || !org.springframework.beans.factory.FactoryBean.class.isAssignableFrom(factoryType))
                    continue;
                var definition = factory.getBeanDefinition(name);
                String owner = definition.getFactoryBeanName();
                var ownerType = owner == null ? factoryType : factory.getType(owner, false);
                if (ownerType != null && ownerType.getName().startsWith("com.kartaguez.pocoma."))
                    throw new IllegalStateException("Unallocated Process FactoryBean: " + name);
            }
        };
    }
    @Bean ProcessSettings processSettings(Environment env) {return ProcessSettings.from(env);}
    @Bean(destroyMethod="closeIfSafe")
    public Resources processResources(ProcessSettings settings,Environment env,Optional<MeterRegistry> registry) {
        return new Resources(settings,env,registry.orElse(null));
    }
    @Bean(destroyMethod="")
    public V3ConsumptionWorker processWorker(@Qualifier("processResources") Resources resources,
            ProcessSettings settings,Environment env,Optional<MeterRegistry> registry,
            @Qualifier("processHandoffs") StageHandoffTracker tracker) {
        if(settings.consumptionLimits().isEmpty())return null;
        var profiles = Set.of(env.getActiveProfiles());
        var observationTags = profiles.containsAll(Set.of("v3-consumption-worker-dev", "v3-read-worker"))
                ? io.micrometer.core.instrument.Tags.of("compose", "process")
                : io.micrometer.core.instrument.Tags.empty();
        var observation=registry.<com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation>map(
                meterRegistry -> new MicrometerV3ConsumptionObservation(meterRegistry, observationTags))
                .orElse(com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.noop());
        registry.ifPresent(tracker::bindRegistry);
        var capacity=new WorkerCapacity(settings.workerLimit(),settings.capacityLimits(),observation);
        if(observation instanceof MicrometerV3ConsumptionObservation metrics)metrics.bindCapacity(capacity,settings.capacityLimits());
        var discoverySettings=new DiscoverySettings(128,settings.lifecycle().batchSize(),settings.lifecycle().batchSize(),64,settings.lifecycle().lockTimeout(),settings.lifecycle().statementTimeout());
        var bindings=new ArrayList<V3ConsumptionWorker.Binding>();
        for(String name:List.of("IdentityRequest","Command")) {
            if(!settings.consumptionLimits().containsKey(name))continue;
            var definition=ProcessSettings.definition(name);
            ConsumptionEngine engine;
            if(name.equals("IdentityRequest")) engine=V3IdentityRequestComposition.assemble(resources.writeBusiness,resources.writeEffects,resources.writeControl,resources.writeTechnical,resources.guard,settings.lifecycle(),capacity,observation);
            else {
                var keys=V3InvitationConfiguration.keys(env);
                if(keys!=null) requireInvitation(resources.writeBusiness);
                engine=V3PotCommandComposition.assemble(resources.writeBusiness,resources.writeEffects,resources.writeControl,resources.writeTechnical,
                    settings.identityAuthority().equals("real")?new JdbcIdentityPotActorAuthority(resources.writeBusiness,resources.writeEffects):new V3FixturePotActorAuthority(resources.writeBusiness),
                    Clock.systemUTC(),resources.guard,settings.lifecycle(),capacity,observation,
                    keys==null?null:new JdbcInvitationStore(resources.writeBusiness,keys),tracker::linkCommandEvents);
            }
            var discovery=V3ConsumptionDiscoveryComposition.requests(resources.guard,definition,resources.writeControl,resources.writeTechnical,discoverySettings,V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY,tracker);
            bindings.add(new V3ConsumptionWorker.Binding(definition,ConsumptionProtocols.forDefinition(definition,engine,discovery),settings.partitions().get(name)));
        }
        var worker=new V3ConsumptionWorker(bindings,capacity,settings.capacityLimits(),settings.polling(),observation,resources::close,tracker,ConsumptionSafety::new);
        resources.capacityView=capacity;
        resources.worker=worker;
        if(observation instanceof MicrometerV3ConsumptionObservation metrics)metrics.bind(worker);
        tracker.registerWakeSignal(worker::signal);
        return worker;
    }
    @Bean public SmartLifecycle processLifecycle(@Qualifier("processResources") Resources resources,
            @Qualifier("processWorker") Optional<V3ConsumptionWorker> worker) {
        return new SmartLifecycle() {
            public void start(){worker.ifPresent(V3ConsumptionWorker::start);}
            public void stop(){worker.ifPresent(V3ConsumptionWorker::requestDrain);}
            public void stop(Runnable callback){if(worker.isPresent())worker.get().requestDrain(() -> {org.slf4j.LoggerFactory.getLogger(getClass()).info("I5_WORKER_STOPPED pid={}",ProcessHandle.current().pid());callback.run();});else callback.run();}
            public boolean isRunning(){return worker.map(V3ConsumptionWorker::isRunning).orElse(false);}
            public boolean isAutoStartup(){return true;}
            public int getPhase(){return Integer.MAX_VALUE-100;}
        };
    }
    private static void requireInvitation(javax.sql.DataSource source) {
        if(!Boolean.TRUE.equals(new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject("select to_regclass('v3_invitation') is not null and to_regclass('v3_invitation_event') is not null and exists(select 1 from v3_event_counter where family='INVITATION')",Boolean.class)))
            throw new IllegalStateException("WRITE Invitation migrations required");
    }
    @Bean org.springframework.boot.health.contributor.HealthIndicator processHealth(@Qualifier("processWorker") Optional<V3ConsumptionWorker> worker) {
        return () -> worker.map(w -> {
            var view=w.snapshot();
            var result=view.state()==WorkerView.State.RUNNING?org.springframework.boot.health.contributor.Health.up():org.springframework.boot.health.contributor.Health.down();
            return result.withDetail("workerState",view.state().name()).withDetail("pendingHandles",view.handles())
                    .withDetail("renewalUnknown",view.unknownRenewals()).withDetail("drainDeadlineExceeded",view.deadlineExceeded()).build();
        }).orElseGet(() -> org.springframework.boot.health.contributor.Health.unknown().withDetail("workerState","INACTIVE").build());
    }
    public static final class Resources {
        public final HikariDataSource writeBusiness,writeControl;
        public final SpringAttemptTransactions writeEffects,writeTechnical;
        public final V3BootstrapGuard guard;
        private volatile V3ConsumptionWorker worker;
        private volatile CapacityView capacityView;
        private boolean closed;
        private final LocalContainerEntityManagerFactoryBean emf;
        Resources(ProcessSettings settings,Environment env,MeterRegistry registry) {
            var opened=new ArrayList<HikariDataSource>();
            LocalContainerEntityManagerFactoryBean initialized=null;
            try {
                writeBusiness=open(settings,settings.writeBusiness(),opened,registry);
                writeControl=open(settings,settings.writeControl(),opened,registry);
                emf=new LocalContainerEntityManagerFactoryBean();initialized=emf;
                emf.setDataSource(writeBusiness);emf.setPersistenceUnitName("v3-process-write-business");
                emf.setManagedTypes(org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes.of());
                emf.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
                emf.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto","none","hibernate.boot.allow_jdbc_metadata_access","false","hibernate.dialect","org.hibernate.dialect.PostgreSQLDialect"));emf.afterPropertiesSet();
                var manager=new JpaTransactionManager(Objects.requireNonNull(emf.getObject()));manager.setDataSource(writeBusiness);manager.setNestedTransactionAllowed(false);
                writeEffects=new SpringAttemptTransactions(writeBusiness,manager,60);writeTechnical=owner(writeControl,3);
                var target=new V3BootstrapGuard.Target(writeControl,settings.writeControl().database(),settings.writeControl().schema(),settings.writeControl().username());
                String sourceRole=env.getProperty("pocoma.v3.process.source-read-role",env.getProperty("pocoma.v3.pilot.read.username","pocoma_write_source_read"));
                guard=V3BootstrapGuard.processWorker(target,sourceRole,settings.catalog(),settings.fixtureIdentity());
                for(var spec:List.of(settings.writeBusiness(),settings.writeControl()))settings.validateTarget(spec,spec.business()?writeBusiness:writeControl);
                guard.validateLifecycleSource(writeBusiness,ConsumptionDefinition.Authority.WRITE,V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                guard.validateLifecycleSource(writeControl,ConsumptionDefinition.Authority.WRITE,V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                if (settings.identityAuthority().equals("real")) {
                    String readEffectRole = env.getProperty("pocoma.v3.pilot.read.username");
                    guard.validateIdentitySource(writeBusiness, readEffectRole);
                    guard.validateIdentitySource(writeControl, readEffectRole);
                }
            } catch(RuntimeException|Error failure) {
                if(initialized!=null)try{initialized.destroy();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
                for(var pool:opened)try{pool.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        private static SpringAttemptTransactions owner(HikariDataSource pool,int timeout) {
            var manager=new DataSourceTransactionManager(pool);manager.setNestedTransactionAllowed(false);return new SpringAttemptTransactions(pool,manager,timeout);
        }
        private static HikariDataSource open(
                ProcessSettings settings,
                ProcessSettings.Pool spec,
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

        public synchronized void closeIfSafe() {
            if(worker==null || worker.snapshot().state()==WorkerView.State.STARTING || worker.snapshot().state()==WorkerView.State.STOPPED)close();
        }
        private synchronized void close() {
            if(closed)return;
            emf.destroy();writeControl.close();
            writeBusiness.close();closed=true;
        }
        public Optional<CapacityView> capacityView(){return Optional.ofNullable(capacityView);}
        public Optional<WorkerView.Snapshot> workerState(){return Optional.ofNullable(worker).map(V3ConsumptionWorker::snapshot);}
    }
}
