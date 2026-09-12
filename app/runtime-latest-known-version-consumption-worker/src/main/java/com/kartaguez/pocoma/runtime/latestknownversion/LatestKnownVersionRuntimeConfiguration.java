package com.kartaguez.pocoma.runtime.latestknownversion;

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
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.out.processing.event.EventPort;
import com.kartaguez.pocoma.engine.port.out.processing.event.LatestKnownVersionEventDiscoveryPort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.read.projection.AdvanceLatestKnownVersionService;
import com.kartaguez.pocoma.engine.read.projection.AdvanceLatestKnownVersionUseCase;
import com.kartaguez.pocoma.engine.read.projection.LatestKnownVersionPersistencePort;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionClaimRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionInputRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionResultRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.locator.consumption.latestknownversion.LatestKnownVersionConsumptionLocator;
import com.kartaguez.pocoma.locator.consumption.latestknownversion.LatestKnownVersionFailureClassifier;
import com.kartaguez.pocoma.locator.consumption.latestknownversion.LatestKnownVersionFailurePolicy;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.SequentialConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.supra.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.supra.consumption.wait.ConditionConsumptionWaiter;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@EnableConfigurationProperties(LatestKnownVersionConsumptionProperties.class)
public class LatestKnownVersionRuntimeConfiguration {

	@Bean @ConditionalOnMissingBean
	Clock latestKnownVersionClock() { return Clock.systemUTC(); }

	@Bean @ConditionalOnMissingBean
	ObjectMapper latestKnownVersionObjectMapper() { return new ObjectMapper(); }

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
						new LatestKnownVersionFailurePolicy(), clock), transactions);
	}

	@Bean
	AdvanceLatestKnownVersionUseCase advanceLatestKnownVersionUseCase(LatestKnownVersionPersistencePort persistence,
			MeterRegistry meterRegistry) {
		return new MeteredAdvanceLatestKnownVersionUseCase(new AdvanceLatestKnownVersionService(persistence), meterRegistry);
	}

	@Bean
	LatestKnownVersionConsumptionLocator latestKnownVersionConsumptionLocator(
			LatestKnownVersionConsumptionProperties properties,
			LatestKnownVersionEventDiscoveryPort discovery, EventPort events,
			AdvanceLatestKnownVersionUseCase advance, Clock clock) {
		return new LatestKnownVersionConsumptionLocator(
				new WorkerSegment(properties.getSegmentIndex(), properties.getSegmentCount()), discovery, events,
				advance, new LatestKnownVersionFailureClassifier(clock), clock);
	}

	@Bean
	ConsumptionOrchestrator consumptionOrchestrator(LatestKnownVersionConsumptionLocator locator,
			AcquireConsumptionUseCase acquire, ExecuteConsumptionUseCase execute,
			HandleConsumptionFailureUseCase handleFailure) {
		return new SequentialConsumptionOrchestrator(locator, acquire, execute, handleFailure);
	}

	@Bean
	ConsumptionPollingWorker consumptionPollingWorker(ConsumptionOrchestrator orchestrator,
			LatestKnownVersionConsumptionProperties properties, Clock clock) {
		var settings = new ConsumptionWorkerSettings(properties.isEnabled(), new WorkerId(properties.getWorkerId()),
				new ClaimLease(properties.getClaimLease()),
				new ConsumptionOrchestrationBudget(properties.getMaxCandidatesInspected(),
						properties.getMaxConsumptionsExecuted()),
				properties.getPollInterval(), properties.getRuntimeFailureBackoff());
		return new ConsumptionPollingWorker(orchestrator, settings, clock, new ConditionConsumptionWaiter());
	}

	@Bean
	SmartLifecycle latestKnownVersionWorkerLifecycle(ConsumptionPollingWorker worker) {
		return new LatestKnownVersionWorkerLifecycle(worker);
	}
}
