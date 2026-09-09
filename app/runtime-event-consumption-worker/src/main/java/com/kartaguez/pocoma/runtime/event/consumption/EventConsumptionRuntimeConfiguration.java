package com.kartaguez.pocoma.runtime.event.consumption;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;
import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.domain.pipeline.PocomaPipelineDefinitions;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.ScheduleProjectionTasksForEventUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.taskcreation.EventPipelineRelevanceRegistry;
import com.kartaguez.pocoma.engine.service.taskcreation.ScheduleProjectionTasksForEventService;
import com.kartaguez.pocoma.engine.service.taskcreation.TaskCreationStrategyRegistry;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.pipeline.JpaTaskCreationAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionClaimRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionInputRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionResultRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.locator.consumption.event.EventConsumptionLocator;
import com.kartaguez.pocoma.locator.consumption.event.failure.EventConsumptionFailurePolicy;
import com.kartaguez.pocoma.locator.consumption.event.failure.EventConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.SequentialConsumptionOrchestrator;
import com.kartaguez.pocoma.supra.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.supra.consumption.wait.ConditionConsumptionWaiter;
import com.kartaguez.pocoma.pipeline.balance.BalanceTaskCreationStrategy;
import com.kartaguez.pocoma.pipeline.balance.BalanceEventPipelineRelevance;
import com.kartaguez.pocoma.pipeline.balance.BalancePipeline;
import com.kartaguez.pocoma.pipeline.pot.PotProjectionPipeline;
import com.kartaguez.pocoma.pipeline.pot.PotTaskCreationStrategy;
import com.kartaguez.pocoma.pipeline.pot.PotEventPipelineRelevance;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@EnableConfigurationProperties(EventConsumptionProperties.class)
public class EventConsumptionRuntimeConfiguration {

	@Bean @ConditionalOnMissingBean
	Clock eventConsumptionClock() { return Clock.systemUTC(); }

	@Bean @ConditionalOnMissingBean
	ObjectMapper eventConsumptionObjectMapper() { return new ObjectMapper(); }

	@Bean
	TransactionRunner consumptionTransactionRunner(PlatformTransactionManager manager) {
		return new SpringTransactionRunner(new TransactionTemplate(manager));
	}

	@Bean
	JpaConsumptionLifecycleAdapter consumptionLifecycleAdapter(JpaConsumptionSlotRepository slots,
			JpaConsumptionClaimRepository claims, ObjectMapper mapper) {
		return new JpaConsumptionLifecycleAdapter(slots, claims, mapper);
	}

	@Bean
	JpaConsumptionProvenanceAdapter consumptionProvenanceAdapter(JpaConsumptionInputRepository inputs,
			JpaConsumptionResultRepository results) {
		return new JpaConsumptionProvenanceAdapter(inputs, results);
	}

	@Bean
	AcquireConsumptionUseCase acquireConsumptionUseCase(JpaConsumptionLifecycleAdapter lifecycle,
			TransactionRunner transactions, Clock clock) {
		return new TransactionalAcquireConsumptionUseCase(new AcquireConsumptionService(lifecycle, clock), transactions);
	}

	@Bean
	ExecuteConsumptionUseCase executeConsumptionUseCase(JpaConsumptionLifecycleAdapter lifecycle,
			JpaConsumptionProvenanceAdapter provenance, TransactionRunner transactions, Clock clock) {
		return new TransactionalExecuteConsumptionUseCase(
				new ExecuteConsumptionService(lifecycle, provenance, clock), transactions);
	}

	@Bean
	HandleConsumptionFailureUseCase handleConsumptionFailureUseCase(JpaConsumptionLifecycleAdapter lifecycle,
			TransactionRunner transactions, Clock clock) {
		return new TransactionalHandleConsumptionFailureUseCase(
				new HandleConsumptionFailureService(lifecycle, lifecycle,
						new EventConsumptionFailurePolicy(), clock), transactions);
	}

	@Bean
	PipelineDefinitionRegistry eventConsumptionPipelineDefinitions() {
		return new PipelineDefinitionRegistry(PocomaPipelineDefinitions.all());
	}

	@Bean
	TaskCreationStrategyRegistry eventTaskCreationStrategies(PipelineDefinitionRegistry definitions,
			ObjectMapper mapper) {
		var strategies = definitions.all().stream().map(definition -> {
			PipelineDefinition identity = definition.identity();
			if (BalancePipeline.PIPELINE_ID.equals(identity.pipelineId().value()))
				return (TaskCreationStrategy) new BalanceTaskCreationStrategy(identity, mapper);
			if (PotProjectionPipeline.PIPELINE_ID.equals(identity.pipelineId().value()))
				return (TaskCreationStrategy) new PotTaskCreationStrategy(identity, mapper);
			throw new IllegalStateException("No Event Task binding for " + identity);
		}).toList();
		return new TaskCreationStrategyRegistry(strategies);
	}

	@Bean
	EventPipelineRelevanceRegistry eventPipelineRelevances() {
		return new EventPipelineRelevanceRegistry(java.util.List.of(new BalanceEventPipelineRelevance(),
				new PotEventPipelineRelevance()));
	}

	@Bean
	ScheduleProjectionTasksForEventUseCase scheduleProjectionTasksForEventUseCase(
			PipelineDefinitionRegistry definitions, EventPipelineRelevanceRegistry relevances,
			TaskCreationStrategyRegistry strategies, JpaTaskCreationAdapter persistence, MeterRegistry meters) {
		return new MeteredProjectionTaskScheduler(
				new ScheduleProjectionTasksForEventService(definitions, relevances, strategies, persistence), meters);
	}

	@Bean
	EventConsumptionLocator eventConsumptionLocator(PipelineDefinitionRegistry definitions,
			EventConsumptionProperties properties,
			EventConsumptionDiscoveryPort discovery, EventPort events,
			ScheduleProjectionTasksForEventUseCase scheduleTasks, Clock clock) {
		return new EventConsumptionLocator(definitions,
				new WorkerSegment(properties.getSegmentIndex(), properties.getSegmentCount()), discovery, events,
				scheduleTasks, new EventConsumptionTechnicalFailureClassifier(clock), clock);
	}

	@Bean
	ConsumptionOrchestrator consumptionOrchestrator(EventConsumptionLocator locator,
			AcquireConsumptionUseCase acquire, ExecuteConsumptionUseCase execute,
			HandleConsumptionFailureUseCase handleFailure) {
		return new SequentialConsumptionOrchestrator(locator, acquire, execute, handleFailure);
	}

	@Bean
	ConsumptionPollingWorker consumptionPollingWorker(ConsumptionOrchestrator orchestrator,
			EventConsumptionProperties properties, Clock clock) {
		var settings = new ConsumptionWorkerSettings(properties.isEnabled(), new WorkerId(properties.getWorkerId()),
				new ClaimLease(properties.getClaimLease()),
				new ConsumptionOrchestrationBudget(properties.getMaxCandidatesInspected(),
						properties.getMaxConsumptionsExecuted()),
				properties.getPollInterval(), properties.getRuntimeFailureBackoff());
		return new ConsumptionPollingWorker(orchestrator, settings, clock,
				new ConditionConsumptionWaiter());
	}

	@Bean
	SmartLifecycle eventConsumptionWorkerLifecycle(ConsumptionPollingWorker worker) {
		return new EventConsumptionWorkerLifecycle(worker);
	}
}
