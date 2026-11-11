package com.kartaguez.pocoma.compose.polling.materialize.v3;

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

/** Explicit materialize selection. No runtime scan, migration or unrelated authority is activated. */
@Configuration(proxyBeanMethods=false)
@Profile("v3-read-worker")
@Import(MaterializeObservationConfiguration.class)
public class MaterializeComposeConfiguration {
    @Bean static BeanFactoryPostProcessor materializePreflight(Environment env) {
        return factory -> {
            for(String disabled:List.of("spring.flyway.enabled","spring.jpa.open-in-view","management.health.db.enabled"))
                if(!"false".equals(env.getProperty(disabled))) throw new IllegalStateException("Explicitly disable "+disabled);
            MaterializeSettings.from(env); // Validate the entire selected configuration before any resource is opened.
        };
    }
    @Bean MaterializeSettings materializeSettings(Environment env) {return MaterializeSettings.from(env);}
    @Bean(destroyMethod="closeIfSafe")
    public Resources materializeResources(MaterializeSettings settings,Environment env,Optional<MeterRegistry> registry) {
        return new Resources(settings,env,registry.orElse(null));
    }
    @Bean(destroyMethod="")
    public V3ConsumptionWorker materializeWorker(@Qualifier("materializeResources") Resources resources,
            MaterializeSettings settings,Environment env,Optional<MeterRegistry> registry,
            @Qualifier("materializeHandoffs") StageHandoffTracker tracker) {
        if(settings.consumptionLimits().isEmpty())return null;
        var profiles = Set.of(env.getActiveProfiles());
        var observationTags = profiles.containsAll(Set.of("v3-consumption-worker-dev", "v3-read-worker"))
                ? io.micrometer.core.instrument.Tags.of("compose", "materialize")
                : io.micrometer.core.instrument.Tags.empty();
        var observation=registry.<com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation>map(
                meterRegistry -> new MicrometerV3ConsumptionObservation(meterRegistry, observationTags))
                .orElse(com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.noop());
        registry.ifPresent(tracker::bindRegistry);
        var capacity=new WorkerCapacity(settings.workerLimit(),settings.capacityLimits(),observation);
        if(observation instanceof MicrometerV3ConsumptionObservation metrics)metrics.bindCapacity(capacity,settings.capacityLimits());
        var discoverySettings=new DiscoverySettings(128,settings.lifecycle().batchSize(),settings.lifecycle().batchSize(),64,settings.lifecycle().lockTimeout(),settings.lifecycle().statementTimeout());
        var bindings=new ArrayList<V3ConsumptionWorker.Binding>();
        for(String name:new TreeSet<>(settings.consumptionLimits().keySet())) {
            var selected=V3ReadParticipants.select(name,resources.writeSourceRead,resources.sourceReads,resources.readBusiness,resources.readEffects);
            var definition=selected.definition();
            var engine=V3ConsumptionLifecycleComposition.assemble(resources.guard,definition,resources.readBusiness,resources.readEffects,selected.participant(),resources.readControl,resources.readTechnical,
                resources.writeSourceRead,resources.sourceReads,discoverySettings,settings.lifecycle(),capacity,ConsumptionFailureClassifier::classify,observation);
            var discovery=V3ConsumptionDiscoveryComposition.events(resources.guard,definition,resources.readControl,resources.readTechnical,resources.writeSourceRead,resources.sourceReads,discoverySettings,V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
            bindings.add(new V3ConsumptionWorker.Binding(definition,ConsumptionProtocols.forDefinition(definition,engine,discovery),settings.partitions().get(name)));
        }
        var worker=new V3ConsumptionWorker(bindings,capacity,settings.capacityLimits(),settings.polling(),observation,resources::close,tracker,ConsumptionSafety::new);
        resources.capacityView=capacity;
        resources.worker=worker;
        if(observation instanceof MicrometerV3ConsumptionObservation metrics)metrics.bind(worker);
        return worker;
    }
    @Bean public SmartLifecycle materializeLifecycle(@Qualifier("materializeResources") Resources resources,
            @Qualifier("materializeWorker") Optional<V3ConsumptionWorker> worker) {
        return new SmartLifecycle() {
            public void start(){worker.ifPresent(V3ConsumptionWorker::start);}
            public void stop(){worker.ifPresent(V3ConsumptionWorker::requestDrain);}
            public void stop(Runnable callback){if(worker.isPresent())worker.get().requestDrain(() -> {org.slf4j.LoggerFactory.getLogger(getClass()).info("I5_WORKER_STOPPED pid={}",ProcessHandle.current().pid());callback.run();});else callback.run();}
            public boolean isRunning(){return worker.map(V3ConsumptionWorker::isRunning).orElse(false);}
            public boolean isAutoStartup(){return true;}
            public int getPhase(){return Integer.MAX_VALUE-100;}
        };
    }
    @Bean org.springframework.boot.health.contributor.HealthIndicator materializeHealth(@Qualifier("materializeWorker") Optional<V3ConsumptionWorker> worker) {
        return () -> worker.map(w -> {
            var view=w.snapshot();
            var result=view.state()==WorkerView.State.RUNNING?org.springframework.boot.health.contributor.Health.up():org.springframework.boot.health.contributor.Health.down();
            return result.withDetail("workerState",view.state().name()).withDetail("pendingHandles",view.handles())
                    .withDetail("renewalUnknown",view.unknownRenewals()).withDetail("drainDeadlineExceeded",view.deadlineExceeded()).build();
        }).orElseGet(() -> org.springframework.boot.health.contributor.Health.unknown().withDetail("workerState","INACTIVE").build());
    }
    @Bean com.kartaguez.pocoma.port.projection.v3.maintenance.ReadMaintenanceStatus materializeMaintenanceStatus(Resources resources) {
        return new com.kartaguez.pocoma.infra.postgres.read.v3.maintenance.JdbcReadMaintenanceStatus(resources.readControl);
    }
    public static final class Resources {
        public final HikariDataSource writeSourceRead,readBusiness,readControl;
        public final SpringAttemptTransactions sourceReads,readEffects,readTechnical;
        public final V3BootstrapGuard guard;
        private volatile V3ConsumptionWorker worker;
        private volatile CapacityView capacityView;
        private boolean closed;
        Resources(MaterializeSettings settings,Environment env,MeterRegistry registry) {
            var opened=new ArrayList<HikariDataSource>();
            try {
                writeSourceRead=open(settings,settings.sourceRead(),opened,registry);
                readBusiness=open(settings,settings.readBusiness(),opened,registry);readControl=open(settings,settings.readControl(),opened,registry);
                sourceReads=owner(writeSourceRead,60);readEffects=owner(readBusiness,60);readTechnical=owner(readControl,3);
                guard=V3BootstrapGuard.materializeWorker(new V3BootstrapGuard.Target(writeSourceRead,settings.sourceRead().database(),settings.sourceRead().schema(),settings.sourceRead().username()),
                    new V3BootstrapGuard.Target(readControl,settings.readControl().database(),settings.readControl().schema(),settings.readControl().username()),settings.catalog());
                settings.validateTarget(settings.sourceRead(),writeSourceRead);settings.validateTarget(settings.readBusiness(),readBusiness);settings.validateTarget(settings.readControl(),readControl);
                guard.validateWriteSourceRead(writeSourceRead);
                guard.validateLifecycleSource(readBusiness,ConsumptionDefinition.Authority.READ,V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
                guard.validateLifecycleSource(readControl,ConsumptionDefinition.Authority.READ,V3BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
            } catch(RuntimeException|Error failure) {
                for(var pool:opened)try{pool.close();}catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
                throw failure;
            }
        }
        private static SpringAttemptTransactions owner(HikariDataSource pool,int timeout) {
            var manager=new DataSourceTransactionManager(pool);manager.setNestedTransactionAllowed(false);return new SpringAttemptTransactions(pool,manager,timeout);
        }
        private static HikariDataSource open(
                MaterializeSettings settings,
                MaterializeSettings.Pool spec,
                List<HikariDataSource> opened,
                MeterRegistry registry) {
            var config = new HikariConfig();
            config.setPoolName(settings.workerName() + "-" + spec.name());
            config.setJdbcUrl(spec.url());
            config.setReadOnly(spec.name().equals("write-source-read"));
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
            readBusiness.close();readControl.close();
            writeSourceRead.close();closed=true;
        }
        public Optional<CapacityView> capacityView(){return Optional.ofNullable(capacityView);}
        public Optional<WorkerView.Snapshot> workerState(){return Optional.ofNullable(worker).map(V3ConsumptionWorker::snapshot);}
    }
}
