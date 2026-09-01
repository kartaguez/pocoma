package com.kartaguez.pocoma.runtime.event.consumption;

import java.time.Clock;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import com.kartaguez.pocoma.engine.port.out.processing.event.EventConsumptionDiscoveryPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
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
	PipelineDefinition eventConsumptionPipeline(EventConsumptionProperties properties) {
		return new PipelineDefinition(
				PipelineId.of(properties.getPipelineId()), properties.getPipelineVersion());
	}

	@Bean
	@ConditionalOnProperty(prefix = "pocoma.event-consumption", name = "pipeline-id", havingValue = "balance-projection")
	TaskCreationStrategy balanceTaskCreationStrategy(PipelineDefinition pipeline, ObjectMapper mapper) {
		return new BalanceTaskCreationStrategy(pipeline, mapper);
	}

	@Bean
	CreateTasksForEventUseCase createTasksForEventUseCase(PipelineDefinition pipeline,
			List<TaskCreationStrategy> strategies, JpaTaskCreationAdapter persistence,
			EventConsumptionProperties properties) {
		var matching = strategies.stream().filter(strategy -> strategy.definition().equals(pipeline)).toList();
		if (properties.isEnabled() && matching.size() != 1) {
			throw new IllegalStateException("Enabled Event consumption requires exactly one TaskCreationStrategy for "
					+ pipeline + ", found " + matching.size());
		}
		return new CreateTasksForEventService(
				new PlanTasksForEventService(new TaskCreationStrategyRegistry(matching)), persistence);
	}

	@Bean
	EventConsumptionLocator eventConsumptionLocator(PipelineDefinition pipeline, EventConsumptionProperties properties,
			EventConsumptionDiscoveryPort discovery, EventPort events,
			CreateTasksForEventUseCase createTasks, Clock clock) {
		return new EventConsumptionLocator(pipeline,
				new WorkerSegment(properties.getSegmentIndex(), properties.getSegmentCount()), discovery, events,
				createTasks, new EventConsumptionTechnicalFailureClassifier(clock), clock);
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
