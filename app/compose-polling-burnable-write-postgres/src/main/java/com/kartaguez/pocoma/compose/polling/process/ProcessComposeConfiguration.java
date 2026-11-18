package com.kartaguez.pocoma.compose.polling.process;

import com.kartaguez.pocoma.contract.consumption.invocation.*;
import com.kartaguez.pocoma.contract.consumption.spi.*;
import com.kartaguez.pocoma.domain.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.*;
import com.kartaguez.pocoma.supra.polling.consumption.ConsumptionWorker;
import com.kartaguez.pocoma.infra.postgres.core.bootstrap.*;
import com.kartaguez.pocoma.infra.postgres.core.consumption.*;
import com.kartaguez.pocoma.infra.postgres.core.history.*;
import com.kartaguez.pocoma.infra.observability.micrometer.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import java.time.*;
import java.util.*;
import com.kartaguez.pocoma.infra.postgres.write.admission.*;
import com.kartaguez.pocoma.infra.postgres.write.pot.*;
import com.kartaguez.pocoma.infra.postgres.write.identity.JdbcIdentityPotActorAuthority;
import com.kartaguez.pocoma.infra.security.spring.InvitationConfiguration;
import com.kartaguez.pocoma.compose.polling.process.dev.FixturePotActorAuthority;

/** Explicit process selection. No runtime scan, migration or unrelated authority is activated. */
@Configuration(proxyBeanMethods=false)
@Profile("v3-consumption-worker-dev")
@Import(ProcessObservationConfiguration.class)
public class ProcessComposeConfiguration {
    @Bean
    static BeanFactoryPostProcessor processPreflight(Environment env) {
        return factory -> {
            for (String disabled :
                    List.of(
                            "spring.flyway.enabled",
                            "spring.jpa.open-in-view",
                            "management.health.db.enabled")) {
                if (!"false".equals(env.getProperty(disabled))) {
                    throw new IllegalStateException("Explicitly disable " + disabled);
                }
            }
            ProcessSettings.from(env);
            var authorities =
                    factory.getBeanNamesForType(
                            com.kartaguez.pocoma.engine.write.pot.port.execution
                                    .PotActorAuthority.class,
                            false,
                            false);
            if (authorities.length > 0) {
                throw new IllegalStateException("Process owns its Pot actor authority");
            }
            for (String name : factory.getBeanDefinitionNames()) {
                rejectUnallocatedFactoryBean(factory, name);
            }
        };
    }

    private static void rejectUnallocatedFactoryBean(
            org.springframework.beans.factory.config.ConfigurableListableBeanFactory factory,
            String name) {
        var factoryType = factory.getType("&" + name, false);
        if (factoryType == null
                || !org.springframework.beans.factory.FactoryBean.class.isAssignableFrom(
                        factoryType)) {
            return;
        }
        String owner = factory.getBeanDefinition(name).getFactoryBeanName();
        var ownerType = owner == null ? factoryType : factory.getType(owner, false);
        if (ownerType != null && ownerType.getName().startsWith("com.kartaguez.pocoma.")) {
            throw new IllegalStateException("Unallocated Process FactoryBean: " + name);
        }
    }

    @Bean
    ProcessSettings processSettings(Environment env) {
        return ProcessSettings.from(env);
    }

    @Bean(destroyMethod = "closeIfSafe")
    public ProcessResources processResources(
            ProcessSettings settings, Environment env, Optional<MeterRegistry> registry) {
        return new ProcessResources(settings, env, registry.orElse(null));
    }

    @Bean(destroyMethod = "")
    public ConsumptionWorker processWorker(
            @Qualifier("processResources") ProcessResources resources,
            ProcessSettings settings,
            Environment env,
            Optional<MeterRegistry> registry,
            @Qualifier("processHandoffs") StageHandoffTracker tracker) {
        if (settings.consumptionLimits().isEmpty()) return null;
        var observation = createObservation(env, registry);
        registry.ifPresent(tracker::bindRegistry);
        var capacity = new WorkerCapacity(settings.workerLimit(), settings.capacityLimits(), observation);
        if (observation instanceof MicrometerConsumptionObservation metrics) {
            metrics.bindCapacity(capacity, settings.capacityLimits());
        }
        var bindings =
                buildBindings(resources, settings, env, capacity, observation, tracker);
        var worker =
                new ConsumptionWorker(
                        bindings,
                        capacity,
                        settings.capacityLimits(),
                        settings.polling(),
                        observation,
                        resources::close,
                        tracker,
                        ConsumptionSafety::new);
        resources.bindWorker(worker, capacity);
        if (observation instanceof MicrometerConsumptionObservation metrics) metrics.bind(worker);
        tracker.registerWakeSignal(worker::signal);
        return worker;
    }

    private static com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation
            createObservation(Environment env, Optional<MeterRegistry> registry) {
        var profiles = Set.of(env.getActiveProfiles());
        var observationTags =
                profiles.containsAll(Set.of("v3-consumption-worker-dev", "v3-read-worker"))
                        ? io.micrometer.core.instrument.Tags.of("compose", "process")
                        : io.micrometer.core.instrument.Tags.empty();
        return registry
                .<com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation>map(
                        meterRegistry ->
                                new MicrometerConsumptionObservation(
                                        meterRegistry, observationTags))
                .orElse(
                        com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation
                                .noop());
    }

    private static List<ConsumptionWorker.Binding> buildBindings(
            ProcessResources resources,
            ProcessSettings settings,
            Environment env,
            WorkerCapacity capacity,
            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation
                    observation,
            StageHandoffTracker tracker) {
        var discoverySettings =
                new DiscoverySettings(
                        128,
                        settings.lifecycle().batchSize(),
                        settings.lifecycle().batchSize(),
                        64,
                        settings.lifecycle().lockTimeout(),
                        settings.lifecycle().statementTimeout());
        var bindings = new ArrayList<ConsumptionWorker.Binding>();
        for (String name : List.of("IdentityRequest", "Command")) {
            if (!settings.consumptionLimits().containsKey(name)) continue;
            var definition = ProcessSettings.definition(name);
            var engine = buildEngine(name, resources, settings, env, capacity, observation, tracker);
            var discovery =
                    ConsumptionDiscoveryComposition.requests(
                            resources.guard,
                            definition,
                            resources.writeControl,
                            resources.writeTechnical,
                            discoverySettings,
                            BootstrapGuard.Privileges.LIFECYCLE_AND_DISCOVERY,
                            tracker);
            bindings.add(
                    new ConsumptionWorker.Binding(
                            definition,
                            ConsumptionProtocols.forDefinition(definition, engine, discovery),
                            settings.partitions().get(name)));
        }
        return bindings;
    }

    private static ConsumptionEngine buildEngine(
            String name,
            ProcessResources resources,
            ProcessSettings settings,
            Environment env,
            WorkerCapacity capacity,
            com.kartaguez.pocoma.contract.consumption.observation.ConsumptionObservation
                    observation,
            StageHandoffTracker tracker) {
        if (name.equals("IdentityRequest")) {
            return IdentityRequestComposition.assemble(
                    resources.writeBusiness,
                    resources.writeEffects,
                    resources.writeControl,
                    resources.writeTechnical,
                    resources.guard,
                    settings.lifecycle(),
                    capacity,
                    observation);
        }
        var keys = InvitationConfiguration.keys(env);
        if (keys != null) requireInvitation(resources.writeBusiness);
        var authority =
                settings.identityAuthority().equals("real")
                        ? new JdbcIdentityPotActorAuthority(
                                resources.writeBusiness, resources.writeEffects)
                        : new FixturePotActorAuthority(resources.writeBusiness);
        return PotCommandComposition.assemble(
                resources.writeBusiness,
                resources.writeEffects,
                resources.writeControl,
                resources.writeTechnical,
                authority,
                Clock.systemUTC(),
                resources.guard,
                settings.lifecycle(),
                capacity,
                observation,
                keys == null ? null : new JdbcInvitationStore(resources.writeBusiness, keys),
                tracker::linkCommandEvents);
    }
    @Bean public SmartLifecycle processLifecycle(@Qualifier("processResources") ProcessResources resources,
            @Qualifier("processWorker") Optional<ConsumptionWorker> worker) {
        return new SmartLifecycle() {
            public void start(){worker.ifPresent(ConsumptionWorker::start);}
            public void stop(){worker.ifPresent(ConsumptionWorker::requestDrain);}
            public void stop(Runnable callback){if(worker.isPresent())worker.get().requestDrain(() -> {org.slf4j.LoggerFactory.getLogger(getClass()).info("I5_WORKER_STOPPED pid={}",ProcessHandle.current().pid());callback.run();});else callback.run();}
            public boolean isRunning(){return worker.map(ConsumptionWorker::isRunning).orElse(false);}
            public boolean isAutoStartup(){return true;}
            public int getPhase(){return Integer.MAX_VALUE-100;}
        };
    }
    private static void requireInvitation(javax.sql.DataSource source) {
        if(!Boolean.TRUE.equals(new org.springframework.jdbc.core.JdbcTemplate(source).queryForObject("select to_regclass('v3_invitation') is not null and to_regclass('v3_invitation_event') is not null and exists(select 1 from v3_event_counter where family='INVITATION')",Boolean.class)))
            throw new IllegalStateException("WRITE Invitation migrations required");
    }
    @Bean org.springframework.boot.health.contributor.HealthIndicator processHealth(@Qualifier("processWorker") Optional<ConsumptionWorker> worker) {
        return () -> worker.map(w -> {
            var view=w.snapshot();
            var result=view.state()==WorkerView.State.RUNNING?org.springframework.boot.health.contributor.Health.up():org.springframework.boot.health.contributor.Health.down();
            return result.withDetail("workerState",view.state().name()).withDetail("pendingHandles",view.handles())
                    .withDetail("renewalUnknown",view.unknownRenewals()).withDetail("drainDeadlineExceeded",view.deadlineExceeded()).build();
        }).orElseGet(() -> org.springframework.boot.health.contributor.Health.unknown().withDetail("workerState","INACTIVE").build());
    }
}
