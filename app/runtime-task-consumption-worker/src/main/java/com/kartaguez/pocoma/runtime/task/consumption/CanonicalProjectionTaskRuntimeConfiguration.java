package com.kartaguez.pocoma.runtime.task.consumption;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
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
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.FinalizeConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionWritePort;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.projection.balance.CalculatePotBalancesAtVersionService;
import com.kartaguez.pocoma.engine.projection.balance.PotBalancesProjector;
import com.kartaguez.pocoma.engine.projection.balance.PotBalancesProjectionDefinition;
import com.kartaguez.pocoma.engine.projection.balance.PreparePotBalancesProjection;
import com.kartaguez.pocoma.engine.projection.pot.PrepareReadPotProjection;
import com.kartaguez.pocoma.engine.projection.pot.ReadPotProjectionInputLoader;
import com.kartaguez.pocoma.engine.projection.pot.ReadPotProjector;
import com.kartaguez.pocoma.engine.projection.task.ExecuteProjectionTaskService;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskPreparation;
import com.kartaguez.pocoma.engine.projection.task.ProjectionTaskRetryPolicy;
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
	@Bean ProjectionTaskPreparation canonicalProjectionPreparation(CanonicalProjectionTaskProperties properties,
			ProjectionValidator validator,JpaHistoricalPotBalanceSourceAdapter balances,
			ObjectProvider<ReadPotProjectionInputLoader> readPotLoader) {
		return switch (properties.getProjectionType()) {
			case "POT_BALANCES" -> new PreparePotBalancesProjection(
					new CalculatePotBalancesAtVersionService(balances,new PotBalancesCalculator()),new PotBalancesProjector(),validator);
			case "READ_POT" -> new PrepareReadPotProjection(readPotLoader.getIfAvailable(() -> {
				throw new IllegalStateException("READ_POT requires a canonical ReadPotProjectionInputLoader");
			}),new ReadPotProjector(),validator);
			default -> throw new IllegalStateException("Unsupported canonical ProjectionType " + properties.getProjectionType());
		};
	}
	@Bean ExecuteProjectionTaskService canonicalProjectionExecutor(ProjectionTaskPreparation preparation,
			ProjectionWritePort writer,FinalizeConsumptionUseCase finalizer,HandleConsumptionFailureUseCase retry,Clock clock){
		return new ExecuteProjectionTaskService(preparation,writer,finalizer,retry,clock);
	}
	@Bean ConsumptionOrchestrator canonicalProjectionOrchestrator(CanonicalProjectionTaskProperties properties,
			JdbcProjectionTaskStoreAdapter tasks,AcquireConsumptionUseCase acquire,ExecuteProjectionTaskService execute){
		return new ProjectionTaskConsumptionOrchestrator(new ProjectionType(properties.getProjectionType()),
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
}
