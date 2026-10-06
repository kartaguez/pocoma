package com.kartaguez.pocoma.runtime.command.consumption;

import java.time.Clock;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.PocomaObjectMapperConfiguration;
import com.kartaguez.pocoma.runtime.command.consumption.PotCommandBindingConfiguration;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.engine.consume.command.execution.ExecuteRecordedCommandUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command.JpaCommandConsumptionDiscoveryAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.command.JdbcCommandOutcomeAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.JpaConsumptionClaimRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.JpaConsumptionInputRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.JpaConsumptionResultRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.engine.consume.command.consumption.CommandConsumptionExecution;
import com.kartaguez.pocoma.engine.consume.command.consumption.BindingFenceRecoveryExecuteUseCase;
import com.kartaguez.pocoma.supra.consume.command.CommandConsumptionLocator;
import com.kartaguez.pocoma.supra.consume.command.failure.CommandConsumptionFailurePolicy;
import com.kartaguez.pocoma.supra.consume.command.failure.CommandConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.SequentialConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorkerObservation;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.orchestrator.poll.consumption.wait.ConditionConsumptionWaiter;

import io.micrometer.core.instrument.MeterRegistry;

@EnableConfigurationProperties(CommandConsumptionProperties.class)
@Import({PocomaObjectMapperConfiguration.class, PotCommandBindingConfiguration.class})
public class CommandConsumptionRuntimeConfiguration {

	@Bean
	Clock commandConsumptionClock() { return Clock.systemUTC(); }

	@Bean
	TransactionRunner commandConsumptionTransactionRunner(PlatformTransactionManager manager) {
		return new SpringTransactionRunner(new TransactionTemplate(manager));
	}

	@Bean
	JpaConsumptionLifecycleAdapter commandConsumptionLifecycle(
			JpaConsumptionSlotRepository slots,
			JpaConsumptionClaimRepository claims,
			@Qualifier("webApiObjectMapper") ObjectMapper mapper) {
		return new JpaConsumptionLifecycleAdapter(slots, claims, mapper);
	}

	@Bean
	JpaConsumptionProvenanceAdapter commandConsumptionProvenance(
			JpaConsumptionInputRepository inputs,
			JpaConsumptionResultRepository results) {
		return new JpaConsumptionProvenanceAdapter(inputs, results);
	}

	@Bean
	AcquireConsumptionUseCase commandConsumptionAcquire(
			@Qualifier("commandConsumptionLifecycle") JpaConsumptionLifecycleAdapter lifecycle,
			@Qualifier("commandConsumptionTransactionRunner") TransactionRunner transactions,
			@Qualifier("commandConsumptionClock") Clock clock) {
		return new TransactionalAcquireConsumptionUseCase(
				new AcquireConsumptionService(lifecycle, clock), transactions);
	}

	@Bean
	ExecuteConsumptionUseCase commandConsumptionExecute(
			@Qualifier("commandConsumptionLifecycle") JpaConsumptionLifecycleAdapter lifecycle,
			@Qualifier("commandConsumptionProvenance") JpaConsumptionProvenanceAdapter provenance,
			ExternalIdentityBindingPort bindings,
			JdbcCommandOutcomeAdapter outcomes,
			@Qualifier("commandConsumptionTransactionRunner") TransactionRunner transactions,
			@Qualifier("commandConsumptionClock") Clock clock) {
		return new BindingFenceRecoveryExecuteUseCase(
				new TransactionalExecuteConsumptionUseCase(
						new ExecuteConsumptionService(lifecycle, provenance, clock), transactions),
				transactions, lifecycle, bindings, outcomes, clock);
	}

	@Bean
	HandleConsumptionFailureUseCase commandConsumptionHandleFailure(
			@Qualifier("commandConsumptionLifecycle") JpaConsumptionLifecycleAdapter lifecycle,
			@Qualifier("commandConsumptionTransactionRunner") TransactionRunner transactions,
			@Qualifier("commandConsumptionClock") Clock clock) {
		return new TransactionalHandleConsumptionFailureUseCase(
				new HandleConsumptionFailureService(
						lifecycle, lifecycle, new CommandConsumptionFailurePolicy(), clock), transactions);
	}

	@Bean
	CommandConsumptionExecution commandConsumptionExecution(ExecuteRecordedCommandUseCase commands,
			JdbcCommandOutcomeAdapter outcomes, @Qualifier("commandConsumptionClock") Clock clock) {
		return new CommandConsumptionExecution(commands, outcomes, clock);
	}

	@Bean
	CommandConsumptionLocator commandConsumptionLocator(
			JpaCommandConsumptionDiscoveryAdapter discovery,
			CommandConsumptionExecution execution,
			@Qualifier("commandConsumptionClock") Clock clock) {
		return new CommandConsumptionLocator(
				discovery, execution, new CommandConsumptionTechnicalFailureClassifier(clock), clock);
	}

	@Bean
	ConsumptionOrchestrator commandConsumptionOrchestrator(
			CommandConsumptionLocator locator,
			@Qualifier("commandConsumptionAcquire") AcquireConsumptionUseCase acquire,
			@Qualifier("commandConsumptionExecute") ExecuteConsumptionUseCase execute,
			@Qualifier("commandConsumptionHandleFailure") HandleConsumptionFailureUseCase failure) {
		return new SequentialConsumptionOrchestrator(locator, acquire, execute, failure);
	}

	@Bean
	ConsumptionPollingWorkerObservation commandConsumptionPollingObservation(MeterRegistry registry) {
		return new MicrometerConsumptionPollingWorkerObservation(registry);
	}

	@Bean
	ConsumptionPollingWorker commandConsumptionPollingWorker(
			@Qualifier("commandConsumptionOrchestrator") ConsumptionOrchestrator orchestrator,
			CommandConsumptionProperties properties,
			@Qualifier("commandConsumptionPollingObservation") ConsumptionPollingWorkerObservation observation,
			@Qualifier("commandConsumptionClock") Clock clock) {
		var settings = new ConsumptionWorkerSettings(
				properties.isEnabled(),
				new WorkerId(workerId(properties)),
				new ClaimLease(properties.getClaimLease()),
				new ConsumptionOrchestrationBudget(
						properties.getMaxCandidatesInspected(), properties.getMaxConsumptionsExecuted()),
				properties.getPollInterval(),
				properties.getRuntimeFailureBackoff());
		return new ConsumptionPollingWorker(
				orchestrator, settings, clock, new ConditionConsumptionWaiter(), observation);
	}

	@Bean
	SmartLifecycle commandConsumptionWorkerLifecycle(
			@Qualifier("commandConsumptionPollingWorker") ConsumptionPollingWorker worker) {
		return new CommandConsumptionWorkerLifecycle(worker);
	}

	private static String workerId(CommandConsumptionProperties properties) {
		String configured = properties.getWorkerId();
		return configured == null || configured.isBlank()
				? "command-consumption-" + UUID.randomUUID()
				: configured;
	}
}
