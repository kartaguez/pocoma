package com.kartaguez.pocoma.compose.polling.materialize;

import com.kartaguez.pocoma.contract.consumption.invocation.*;
import com.kartaguez.pocoma.contract.consumption.spi.*;
import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.*;
import com.kartaguez.pocoma.supra.polling.consumption.ConsumptionWorker;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.*;
import com.kartaguez.pocoma.infra.postgres.core.consumption.*;
import com.kartaguez.pocoma.infra.postgres.core.history.*;
import com.kartaguez.pocoma.infra.tx.spring.ConsumptionFailureClassifier;
import com.kartaguez.pocoma.infra.observability.micrometer.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
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
    public MaterializeResources materializeResources(MaterializeSettings settings,Environment env,Optional<MeterRegistry> registry) {
        return new MaterializeResources(settings,env,registry.orElse(null));
    }
    @Bean(destroyMethod="")
    public ConsumptionWorker materializeWorker(@Qualifier("materializeResources") MaterializeResources resources,
            MaterializeSettings settings,Environment env,Optional<MeterRegistry> registry,
            @Qualifier("materializeHandoffs") StageHandoffTracker tracker) {
        if(settings.consumptionLimits().isEmpty())return null;
        var profiles = Set.of(env.getActiveProfiles());
        var observationTags = profiles.containsAll(Set.of("v3-consumption-worker-dev", "v3-read-worker"))
                ? io.micrometer.core.instrument.Tags.of("compose", "materialize")
                : io.micrometer.core.instrument.Tags.empty();
        var observation=registry.<com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation>map(
                meterRegistry -> new MicrometerConsumptionObservation(meterRegistry, observationTags))
                .orElse(com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation.noop());
        registry.ifPresent(tracker::bindRegistry);
        var capacity=new WorkerCapacity(settings.workerLimit(),settings.capacityLimits(),observation);
        if(observation instanceof MicrometerConsumptionObservation metrics)metrics.bindCapacity(capacity,settings.capacityLimits());
        var discoverySettings=new DiscoverySettings(128,settings.lifecycle().batchSize(),settings.lifecycle().batchSize(),64,settings.lifecycle().lockTimeout(),settings.lifecycle().statementTimeout());
        var bindings=new ArrayList<ConsumptionWorker.Binding>();
        for(String name:new TreeSet<>(settings.consumptionLimits().keySet())) {
            var selected=ReadParticipants.select(name,resources.writeSourceRead,resources.sourceReads,resources.readBusiness,resources.readEffects);
            var definition=selected.definition();
            var engine=ConsumptionLifecycleComposition.assemble(resources.guard,definition,resources.readBusiness,resources.readEffects,selected.participant(),resources.readControl,resources.readTechnical,
                resources.writeSourceRead,resources.sourceReads,discoverySettings,settings.lifecycle(),capacity,ConsumptionFailureClassifier::classify,observation);
            var discovery=ConsumptionDiscoveryComposition.events(resources.guard,definition,resources.readControl,resources.readTechnical,resources.writeSourceRead,resources.sourceReads,discoverySettings,BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY);
            bindings.add(new ConsumptionWorker.Binding(definition,ConsumptionProtocols.forDefinition(definition,engine,discovery),settings.partitions().get(name)));
        }
        var worker=new ConsumptionWorker(bindings,capacity,settings.capacityLimits(),settings.polling(),observation,resources::close,tracker,ConsumptionSafety::new);
        resources.bindWorker(worker, capacity);
        if(observation instanceof MicrometerConsumptionObservation metrics)metrics.bind(worker);
        return worker;
    }
    @Bean public SmartLifecycle materializeLifecycle(@Qualifier("materializeResources") MaterializeResources resources,
            @Qualifier("materializeWorker") Optional<ConsumptionWorker> worker) {
        return new SmartLifecycle() {
            public void start(){worker.ifPresent(ConsumptionWorker::start);}
            public void stop(){worker.ifPresent(ConsumptionWorker::requestDrain);}
            public void stop(Runnable callback){if(worker.isPresent())worker.get().requestDrain(() -> {org.slf4j.LoggerFactory.getLogger(getClass()).info("I5_WORKER_STOPPED pid={}",ProcessHandle.current().pid());callback.run();});else callback.run();}
            public boolean isRunning(){return worker.map(ConsumptionWorker::isRunning).orElse(false);}
            public boolean isAutoStartup(){return true;}
            public int getPhase(){return Integer.MAX_VALUE-100;}
        };
    }
    @Bean org.springframework.boot.health.contributor.HealthIndicator materializeHealth(@Qualifier("materializeWorker") Optional<ConsumptionWorker> worker) {
        return () -> worker.map(w -> {
            var view=w.snapshot();
            var result=view.state()==WorkerView.State.RUNNING?org.springframework.boot.health.contributor.Health.up():org.springframework.boot.health.contributor.Health.down();
            return result.withDetail("workerState",view.state().name()).withDetail("pendingHandles",view.handles())
                    .withDetail("renewalUnknown",view.unknownRenewals()).withDetail("drainDeadlineExceeded",view.deadlineExceeded()).build();
        }).orElseGet(() -> org.springframework.boot.health.contributor.Health.unknown().withDetail("workerState","INACTIVE").build());
    }
    @Bean com.kartaguez.pocoma.port.projection.maintenance.ReadMaintenanceStatus materializeMaintenanceStatus(MaterializeResources resources) {
        return new com.kartaguez.pocoma.infra.postgres.read.maintenance.JdbcReadMaintenanceStatus(resources.readControl);
    }
}
