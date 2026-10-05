package com.kartaguez.pocoma.runtime.task.consumption;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
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
import com.kartaguez.pocoma.domain.projection.pot.definition.AuthProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.pot.definition.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.domain.projection.pot.definition.ReadPotProjectionDefinition;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.port.projection.ProjectionWritePort;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.consume.projectiontask.input.CalculatePotBalancesAtVersionService;
import com.kartaguez.pocoma.engine.consume.projectiontask.input.PotBalancesProjectionInputLoader;
import com.kartaguez.pocoma.projector.pot.balance.PotBalancesProjector;
import com.kartaguez.pocoma.engine.consume.projectiontask.input.AuthProjectionInputLoader;
import com.kartaguez.pocoma.projector.pot.AuthProjector;
import com.kartaguez.pocoma.engine.consume.projectiontask.input.ReadPotProjectionInputLoader;
import com.kartaguez.pocoma.projector.pot.ReadPotProjector;
import com.kartaguez.pocoma.engine.consume.projectiontask.ProjectionTaskConsumptionService;
import com.kartaguez.pocoma.engine.consume.projectiontask.ProjectionTaskRetryPolicy;
import com.kartaguez.pocoma.engine.consume.projectiontask.engine.ExecuteProjectionTaskUseCase;
import com.kartaguez.pocoma.engine.consume.projectiontask.engine.ProjectionEngineService;
import com.kartaguez.pocoma.engine.consume.projectiontask.engine.ProjectionProducerCatalog;
import com.kartaguez.pocoma.engine.consume.projectiontask.engine.ProjectionProducerDeclaration;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.FinalizeConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalFinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection.JdbcProjectionTaskStoreAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.projection.JpaHistoricalPotBalanceSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.JpaConsumptionClaimRepository;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.infra.projection.validation.networknt.NetworkntJsonSchemaValidator;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.engine.consume.projectiontask.ProjectionTaskConsumptionOrchestrator;
import com.kartaguez.pocoma.supra.consume.projectiontask.ProjectionTaskCandidateSource;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.orchestrator.poll.consumption.wait.ConditionConsumptionWaiter;

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
			JpaHistoricalPotBalanceSourceAdapter balances, ReadPotProjectionInputLoader readPotLoader,
			ObjectProvider<AuthProjectionInputLoader> authLoaders) {
		Set<ProjectionType> configured = projectionTypes(properties.getCatalogProjectionTypes(), "catalog-projection-types");
		var available = new ArrayList<ProjectionProducerDeclaration<?>>();
		available.addAll(List.of(
				new ProjectionProducerDeclaration<>(PotBalancesProjectionDefinition.PROJECTION_TYPE,
						PotBalancesProjectionDefinition.TARGET_OBJECT_TYPE, PotBalancesProjectionDefinition.DEFINITION,
						new PotBalancesProjectionInputLoader(
								new CalculatePotBalancesAtVersionService(balances, new PotBalancesCalculator())),
						new PotBalancesProjector()),
				new ProjectionProducerDeclaration<>(ReadPotProjectionDefinition.PROJECTION_TYPE,
						ReadPotProjectionDefinition.TARGET_OBJECT_TYPE, ReadPotProjectionDefinition.DEFINITION,
						readPotLoader, new ReadPotProjector())));
		if (configured.contains(AuthProjectionDefinition.PROJECTION_TYPE)) {
			AuthProjectionInputLoader authLoader = authLoaders.getIfAvailable();
			if (authLoader == null) {
				throw new IllegalStateException("AUTH requires an AuthProjectionInputLoader");
			}
			available.add(new ProjectionProducerDeclaration<>(AuthProjectionDefinition.PROJECTION_TYPE,
					AuthProjectionDefinition.TARGET_OBJECT_TYPE, AuthProjectionDefinition.DEFINITION,
					authLoader, new AuthProjector()));
		}
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
		return new ProjectionTaskConsumptionOrchestrator(
				new ProjectionTaskCandidateSource(locatorTypes, properties.getSegmentIndex(),
						properties.getSegmentCount(), tasks), acquire, execute);
	}
	@Bean ConsumptionPollingWorker canonicalProjectionWorker(ConsumptionOrchestrator orchestrator,
			CanonicalProjectionTaskProperties properties,Clock clock){
		return new ConsumptionPollingWorker(orchestrator,new ConsumptionWorkerSettings(properties.isEnabled(),
				new WorkerId(properties.getWorkerId()),new ClaimLease(properties.getClaimLease()),
				new ConsumptionOrchestrationBudget(properties.getMaxCandidatesInspected(),properties.getMaxConsumptionsExecuted()),
				properties.getPollInterval(),properties.getRuntimeFailureBackoff()),clock,new ConditionConsumptionWaiter());
	}
	@Bean SmartLifecycle canonicalProjectionWorkerLifecycle(ConsumptionPollingWorker worker){return new ProjectionTaskWorkerLifecycle(worker);}

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
