package com.kartaguez.pocoma.eventconsumption;

import java.time.Clock;
import java.util.List;

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
import com.kartaguez.pocoma.domain.pipeline.PipelineId;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.in.taskcreation.strategy.TaskCreationStrategy;
import com.kartaguez.pocoma.engine.port.in.taskcreation.usecase.CreateTasksForEventUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.DefaultConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.taskcreation.CreateTasksForEventService;
import com.kartaguez.pocoma.engine.service.taskcreation.PlanTasksForEventService;
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
import com.kartaguez.pocoma.locator.consumption.event.EventConsumptionFailureClassifier;
import com.kartaguez.pocoma.locator.consumption.event.EventConsumptionLocator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.DefaultConsumptionOrchestrator;
import com.kartaguez.pocoma.supra.consumption.ConditionConsumptionWorkerWaitStrategy;
import com.kartaguez.pocoma.supra.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.supra.consumption.SupraConsumptionWorker;

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
						new DefaultConsumptionFailurePolicy(), clock), transactions);
	}

	@Bean
	PipelineDefinition eventConsumptionPipeline(EventConsumptionProperties properties,
			List<TaskCreationStrategy> strategies) {
		PipelineDefinition definition = new PipelineDefinition(
				PipelineId.of(properties.getPipelineId()), properties.getPipelineVersion());
		long matches = strategies.stream().filter(strategy -> strategy.definition().equals(definition)).count();
		if (properties.isEnabled() && matches != 1) {
			throw new IllegalStateException("Enabled Event consumption requires exactly one TaskCreationStrategy for "
					+ definition + ", found " + matches);
		}
		return definition;
	}

	@Bean
	CreateTasksForEventUseCase createTasksForEventUseCase(PipelineDefinition pipeline,
			List<TaskCreationStrategy> strategies, JpaTaskCreationAdapter persistence) {
		var matching = strategies.stream().filter(strategy -> strategy.definition().equals(pipeline)).toList();
		return new CreateTasksForEventService(
				new PlanTasksForEventService(new TaskCreationStrategyRegistry(matching)), persistence);
	}

	@Bean
	EventConsumptionLocator eventConsumptionLocator(PipelineDefinition pipeline, EventConsumptionProperties properties,
			EventPort events, CreateTasksForEventUseCase createTasks, Clock clock) {
		return new EventConsumptionLocator(pipeline,
				new WorkerSegment(properties.getSegmentIndex(), properties.getSegmentCount()), events, createTasks,
				new EventConsumptionFailureClassifier(clock));
	}

	@Bean
	ConsumptionOrchestrator consumptionOrchestrator(EventConsumptionLocator locator,
			AcquireConsumptionUseCase acquire, ExecuteConsumptionUseCase execute,
			HandleConsumptionFailureUseCase handleFailure) {
		return new DefaultConsumptionOrchestrator(locator, acquire, execute, handleFailure);
	}

	@Bean
	SupraConsumptionWorker supraConsumptionWorker(ConsumptionOrchestrator orchestrator,
			EventConsumptionProperties properties, Clock clock) {
		var settings = new ConsumptionWorkerSettings(properties.isEnabled(), new WorkerId(properties.getWorkerId()),
				new ClaimLease(properties.getClaimLease()),
				new ConsumptionOrchestrationBudget(properties.getMaxCandidatesInspected(),
						properties.getMaxConsumptionsExecuted()),
				properties.getPollInterval(), properties.getRuntimeFailureBackoff());
		return new SupraConsumptionWorker(orchestrator, settings, clock,
				new ConditionConsumptionWorkerWaitStrategy());
	}

	@Bean
	SmartLifecycle supraConsumptionWorkerLifecycle(SupraConsumptionWorker worker) {
		return new SupraConsumptionWorkerLifecycle(worker);
	}
}
