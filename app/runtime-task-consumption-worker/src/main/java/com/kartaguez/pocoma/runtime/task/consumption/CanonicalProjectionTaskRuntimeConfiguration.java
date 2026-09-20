package com.kartaguez.pocoma.runtime.task.consumption;

import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.domain.projection.balance.PotBalancesCalculator;
import com.kartaguez.pocoma.domain.pot.projection.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.pot.projection.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionWritePort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.projection.balance.CalculatePotBalancesAtVersionService;
import com.kartaguez.pocoma.engine.projection.balance.PotBalancesProjectionInputLoader;
import com.kartaguez.pocoma.engine.projection.balance.PotBalancesProjector;
import com.kartaguez.pocoma.engine.projection.pot.ReadPotProjectionInputLoader;
import com.kartaguez.pocoma.engine.projection.pot.ReadPotProjector;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskConsumptionService;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskRetryPolicy;
import com.kartaguez.pocoma.engine.projection.task.engine.ExecuteProjectionTaskUseCase;
import com.kartaguez.pocoma.engine.projection.task.engine.ProjectionEngineService;
import com.kartaguez.pocoma.engine.projection.task.engine.ProjectionProducerCatalog;
import com.kartaguez.pocoma.engine.projection.task.engine.ProjectionProducerDeclaration;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.FinalizeConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalFinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JdbcProjectionTaskStoreAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaHistoricalPotBalanceSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionClaimRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.infra.projection.jsonschema.NetworkntJsonSchemaValidator;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.ProjectionTaskConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.supra.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.supra.consumption.wait.ConditionConsumptionWaiter;

@Configuration
@EnableConfigurationProperties(CanonicalProjectionTaskProperties.class)
@ConditionalOnProperty(name = "pocoma.projection-task-consumption.enabled", havingValue = "true")
public class CanonicalProjectionTaskRuntimeConfiguration {
	@Bean @ConditionalOnMissingBean Clock canonicalProjectionClock(){return Clock.systemUTC();}
	@Bean @ConditionalOnMissingBean ObjectMapper canonicalProjectionObjectMapper(){return new ObjectMapper();}
	@Bean TransactionRunner canonicalProjectionTransactions(PlatformTransactionManager manager){
		return new SpringTransactionRunner(new TransactionTemplate(manager));
	}
	@Bean JpaConsumptionLifecycleAdapter canonicalProjectionLifecycle(JpaConsumptionSlotRepository slots,
			JpaConsumptionClaimRepository claims,ObjectMapper mapper){return new JpaConsumptionLifecycleAdapter(slots,claims,mapper);}
	@Bean AcquireConsumptionUseCase canonicalProjectionAcquire(JpaConsumptionLifecycleAdapter lifecycle,
			TransactionRunner tx,Clock clock){return new TransactionalAcquireConsumptionUseCase(new AcquireConsumptionService(lifecycle,clock),tx);}
	@Bean FinalizeConsumptionUseCase canonicalProjectionFinalizer(JpaConsumptionLifecycleAdapter lifecycle,
			TransactionRunner tx,Clock clock){return new TransactionalFinalizeConsumptionUseCase(new FinalizeConsumptionService(lifecycle,clock),tx);}
	@Bean HandleConsumptionFailureUseCase canonicalProjectionRetry(JpaConsumptionLifecycleAdapter lifecycle,
			TransactionRunner tx,Clock clock,CanonicalProjectionTaskProperties properties){
		return new TransactionalHandleConsumptionFailureUseCase(new HandleConsumptionFailureService(lifecycle,lifecycle,
				new ProjectionTaskRetryPolicy(attempt -> properties.getRetryDelay()),clock),tx);
	}
	@Bean JdbcProjectionTaskStoreAdapter canonicalProjectionTasks(JdbcTemplate jdbc){return new JdbcProjectionTaskStoreAdapter(jdbc);}
	@Bean ProjectionValidator canonicalProjectionValidator(ObjectMapper mapper){
		return new ProjectionValidator(new NetworkntJsonSchemaValidator(mapper));
	}
	@Bean ProjectionProducerCatalog canonicalProjectionProducerCatalog(CanonicalProjectionTaskProperties properties,
			JpaHistoricalPotBalanceSourceAdapter balances, ReadPotProjectionInputLoader readPotLoader) {
		var available = List.of(
				new ProjectionProducerDeclaration<>(PotBalancesProjectionDefinition.PROJECTION_TYPE,
						PotBalancesProjectionDefinition.TARGET_OBJECT_TYPE, PotBalancesProjectionDefinition.DEFINITION,
						new PotBalancesProjectionInputLoader(
								new CalculatePotBalancesAtVersionService(balances, new PotBalancesCalculator())),
						new PotBalancesProjector()),
				new ProjectionProducerDeclaration<>(ReadPotProjectionDefinition.PROJECTION_TYPE,
						ReadPotProjectionDefinition.TARGET_OBJECT_TYPE, ReadPotProjectionDefinition.DEFINITION,
						readPotLoader, new ReadPotProjector()));
		Set<ProjectionType> configured = projectionTypes(properties.getCatalogProjectionTypes(), "catalog-projection-types");
		var selected = available.stream().filter(declaration -> configured.contains(declaration.projectionType())).toList();
		if (selected.size() != configured.size()) {
			throw new IllegalStateException("The producer catalog contains an unsupported ProjectionType");
		}
		return new ProjectionProducerCatalog(selected);
	}
	@Bean ExecuteProjectionTaskUseCase canonicalProjectionEngine(ProjectionProducerCatalog catalog,
			ProjectionValidator validator) {
		return new ProjectionEngineService(catalog, validator);
	}
	@Bean ProjectionTaskConsumptionService canonicalProjectionExecutor(ExecuteProjectionTaskUseCase projectionEngine,
			ProjectionWritePort writer,FinalizeConsumptionUseCase finalizer,HandleConsumptionFailureUseCase retry,Clock clock){
		return new ProjectionTaskConsumptionService(projectionEngine,writer,finalizer,retry,clock);
	}
	@Bean ConsumptionOrchestrator canonicalProjectionOrchestrator(CanonicalProjectionTaskProperties properties,
			ProjectionProducerCatalog catalog, JdbcProjectionTaskStoreAdapter tasks,
			AcquireConsumptionUseCase acquire,ProjectionTaskConsumptionService execute){
		Set<ProjectionType> locatorTypes = projectionTypes(properties.getLocatorProjectionTypes(), "locator-projection-types");
		if (!catalog.projectionTypes().containsAll(locatorTypes)) {
			throw new IllegalStateException("locator-projection-types must be a subset of catalog-projection-types");
		}
		return new ProjectionTaskConsumptionOrchestrator(locatorTypes,
				properties.getSegmentIndex(),properties.getSegmentCount(),tasks,acquire,execute);
	}
	@Bean ConsumptionPollingWorker canonicalProjectionWorker(ConsumptionOrchestrator orchestrator,
			CanonicalProjectionTaskProperties properties,Clock clock){
		return new ConsumptionPollingWorker(orchestrator,new ConsumptionWorkerSettings(properties.isEnabled(),
				new WorkerId(properties.getWorkerId()),new ClaimLease(properties.getClaimLease()),
				new ConsumptionOrchestrationBudget(properties.getMaxCandidatesInspected(),properties.getMaxConsumptionsExecuted()),
				properties.getPollInterval(),properties.getRuntimeFailureBackoff()),clock,new ConditionConsumptionWaiter());
	}
	@Bean SmartLifecycle canonicalProjectionWorkerLifecycle(ConsumptionPollingWorker worker){return new TaskConsumptionWorkerLifecycle(worker);}

	private static Set<ProjectionType> projectionTypes(List<String> values, String property) {
		if (values == null || values.isEmpty()) {
			throw new IllegalStateException(property + " must not be empty");
		}
		var result = new LinkedHashSet<ProjectionType>();
		for (String value : values) {
			if (value == null || value.isBlank()) {
				throw new IllegalStateException(property + " must contain non-blank values");
			}
			if (!result.add(new ProjectionType(value))) {
				throw new IllegalStateException(property + " must not contain duplicates");
			}
		}
		return Set.copyOf(result);
	}
}
