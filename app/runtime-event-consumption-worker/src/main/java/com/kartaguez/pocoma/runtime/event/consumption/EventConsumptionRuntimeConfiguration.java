package com.kartaguez.pocoma.runtime.event.consumption;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.domain.projection.ProjectionType;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.processing.event.materialization.ProjectionMaterializationPolicy;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.FinalizeConsumptionService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalFinalizeConsumptionUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.event.JdbcProjectionMaterializationDiscoveryAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JdbcProjectionTaskStoreAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionClaimRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionKeys;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionService;
import com.kartaguez.pocoma.locator.consumption.event.materialization.ProjectionMaterializationConsumptionSource;
import com.kartaguez.pocoma.orchestrator.consumption.AcquireThenFinalizeConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.supra.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.supra.consumption.wait.ConditionConsumptionWaiter;

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
	AcquireConsumptionUseCase acquireConsumptionUseCase(JpaConsumptionLifecycleAdapter lifecycle,
			TransactionRunner transactions, Clock clock) {
		return new TransactionalAcquireConsumptionUseCase(new AcquireConsumptionService(lifecycle, clock), transactions);
	}

	@Bean
	JdbcProjectionTaskStoreAdapter canonicalProjectionTaskStore(JdbcTemplate jdbc) {
		return new JdbcProjectionTaskStoreAdapter(jdbc);
	}

	@Bean
	FinalizeConsumptionUseCase finalizeConsumptionUseCase(JpaConsumptionLifecycleAdapter lifecycle,
			TransactionRunner transactions, Clock clock) {
		return new TransactionalFinalizeConsumptionUseCase(
				new FinalizeConsumptionService(lifecycle, clock), transactions);
	}

	@Bean
	ProjectionMaterializationPolicy projectionMaterializationPolicy() {
		return PocomaProjectionMaterializationPolicy.policy();
	}

	@Bean
	JdbcProjectionMaterializationDiscoveryAdapter projectionMaterializationDiscovery(JdbcTemplate jdbc) {
		return new JdbcProjectionMaterializationDiscoveryAdapter(jdbc);
	}

	@Bean
	ProjectionMaterializationConsumptionSource projectionMaterializationConsumptionSource(
			ProjectionMaterializationPolicy policy, EventConsumptionProperties properties,
			JdbcProjectionMaterializationDiscoveryAdapter discovery) {
		Set<ProjectionType> projectionTypes = projectionTypes(properties.getProjectionTypes(), policy);
		return new ProjectionMaterializationConsumptionSource(
				policy.materializationsFor(projectionTypes),
				new WorkerSegment(properties.getSegmentIndex(), properties.getSegmentCount()), discovery);
	}

	@Bean
	ProjectionMaterializationConsumptionService projectionMaterializationConsumptionService(
			FinalizeConsumptionUseCase finalizeConsumption, JdbcProjectionTaskStoreAdapter tasks) {
		return new ProjectionMaterializationConsumptionService(finalizeConsumption, tasks);
	}

	@Bean
	ConsumptionOrchestrator consumptionOrchestrator(ProjectionMaterializationConsumptionSource source,
			AcquireConsumptionUseCase acquire, ProjectionMaterializationConsumptionService finalizeConsumption) {
		return new AcquireThenFinalizeConsumptionOrchestrator<>(source,
				ProjectionMaterializationConsumptionKeys::consumptionKey, acquire, finalizeConsumption);
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

	static Set<ProjectionType> projectionTypes(
			List<String> configuredValues, ProjectionMaterializationPolicy policy) {
		if (configuredValues == null || configuredValues.isEmpty()) {
			throw new IllegalStateException("projection-types must not be empty");
		}
		var configured = new LinkedHashSet<ProjectionType>();
		for (String value : configuredValues) {
			if (value == null || value.isBlank()) {
				throw new IllegalStateException("projection-types must contain non-blank values");
			}
			if (!configured.add(new ProjectionType(value))) {
				throw new IllegalStateException("projection-types must not contain duplicates");
			}
		}
		Set<ProjectionType> materializable = policy.materializations().values().stream()
				.flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
		if (!materializable.containsAll(configured)) {
			var unsupported = new LinkedHashSet<>(configured);
			unsupported.removeAll(materializable);
			throw new IllegalStateException("projection-types contain unsupported values: " + unsupported);
		}
		return Set.copyOf(configured);
	}
}
