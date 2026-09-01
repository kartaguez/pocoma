package com.kartaguez.pocoma.runtime.task.consumption;

import java.time.Clock;
import java.util.List;
import java.util.Set;

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
import com.kartaguez.pocoma.domain.projection.balance.PotBalancesCalculator;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.AcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.ExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.HandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.engine.port.in.taskexecution.handler.TaskExecutionHandler;
import com.kartaguez.pocoma.engine.port.in.taskexecution.mapper.RecordedTaskExecutionMapper;
import com.kartaguez.pocoma.engine.port.in.taskexecution.usecase.ExecuteTaskUseCase;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.processing.segmentation.WorkerSegment;
import com.kartaguez.pocoma.engine.service.consumption.AcquireConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.ExecuteConsumptionService;
import com.kartaguez.pocoma.engine.service.consumption.HandleConsumptionFailureService;
import com.kartaguez.pocoma.engine.service.projection.CalculatePotBalancesAtVersionService;
import com.kartaguez.pocoma.engine.service.taskexecution.ExecuteTaskService;
import com.kartaguez.pocoma.engine.service.taskexecution.RecordedTaskExecutionMapperRegistry;
import com.kartaguez.pocoma.engine.service.taskexecution.TaskExecutionHandlerRegistry;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalAcquireConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalExecuteConsumptionUseCase;
import com.kartaguez.pocoma.engine.service.transaction.consumption.TransactionalHandleConsumptionFailureUseCase;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionLifecycleAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.JpaConsumptionProvenanceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.processing.task.JpaTaskPort;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaHistoricalPotBalanceSourceAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection.JpaImmutableBalanceProjectionAdapter;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionClaimRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionInputRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionResultRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.JpaConsumptionSlotRepository;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.locator.consumption.task.TaskConsumptionLocator;
import com.kartaguez.pocoma.locator.consumption.task.failure.TaskConsumptionFailurePolicy;
import com.kartaguez.pocoma.locator.consumption.task.failure.TaskConsumptionTechnicalFailureClassifier;
import com.kartaguez.pocoma.orchestrator.consumption.ConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.SequentialConsumptionOrchestrator;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.pipeline.balance.BalancePipeline;
import com.kartaguez.pocoma.pipeline.balance.ComputeBalancesRecordedTaskMapper;
import com.kartaguez.pocoma.pipeline.balance.ExecuteBalanceProjectionTaskHandler;
import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;
import com.kartaguez.pocoma.supra.consumption.ConsumptionWorkerSettings;
import com.kartaguez.pocoma.supra.consumption.wait.ConditionConsumptionWaiter;

@Configuration
@EnableConfigurationProperties(TaskConsumptionProperties.class)
public class TaskConsumptionRuntimeConfiguration {
	@Bean @ConditionalOnMissingBean Clock taskConsumptionClock(){return Clock.systemUTC();}
	@Bean @ConditionalOnMissingBean ObjectMapper taskConsumptionObjectMapper(){return new ObjectMapper();}
	@Bean TransactionRunner taskConsumptionTransactionRunner(PlatformTransactionManager manager){
		return new SpringTransactionRunner(new TransactionTemplate(manager));
	}
	@Bean JpaConsumptionLifecycleAdapter taskConsumptionLifecycle(JpaConsumptionSlotRepository slots,
			JpaConsumptionClaimRepository claims, ObjectMapper mapper){return new JpaConsumptionLifecycleAdapter(slots,claims,mapper);}
	@Bean JpaConsumptionProvenanceAdapter taskConsumptionProvenance(JpaConsumptionInputRepository inputs,
			JpaConsumptionResultRepository results){return new JpaConsumptionProvenanceAdapter(inputs,results);}
	@Bean AcquireConsumptionUseCase taskAcquire(JpaConsumptionLifecycleAdapter lifecycle,TransactionRunner tx,Clock clock){
		return new TransactionalAcquireConsumptionUseCase(new AcquireConsumptionService(lifecycle,clock),tx);}
	@Bean ExecuteConsumptionUseCase taskExecute(JpaConsumptionLifecycleAdapter lifecycle,
			JpaConsumptionProvenanceAdapter provenance,TransactionRunner tx,Clock clock){
		return new TransactionalExecuteConsumptionUseCase(new ExecuteConsumptionService(lifecycle,provenance,clock),tx);}
	@Bean HandleConsumptionFailureUseCase taskHandleFailure(JpaConsumptionLifecycleAdapter lifecycle,
			TransactionRunner tx,Clock clock){return new TransactionalHandleConsumptionFailureUseCase(
				new HandleConsumptionFailureService(lifecycle,lifecycle,new TaskConsumptionFailurePolicy(),clock),tx);}

	@Bean PipelineDefinition taskPipeline(TaskConsumptionProperties properties){
		PipelineDefinition pipeline=new PipelineDefinition(PipelineId.of(properties.getPipelineId()),properties.getPipelineVersion());
		if(properties.isEnabled()&&!BalancePipeline.PIPELINE_ID.equals(properties.getPipelineId()))
			throw new IllegalStateException("Lot 5 runtime only provides the Balance binding");
		return pipeline;
	}
	@Bean RecordedTaskExecutionMapper<?> balanceTaskMapper(PipelineDefinition pipeline,ObjectMapper mapper){
		return new ComputeBalancesRecordedTaskMapper(pipeline,mapper);}
	@Bean TaskExecutionHandler<?> balanceTaskHandler(PipelineDefinition pipeline,
			JpaHistoricalPotBalanceSourceAdapter sources,JpaImmutableBalanceProjectionAdapter projections){
		return new ExecuteBalanceProjectionTaskHandler(pipeline,
				new CalculatePotBalancesAtVersionService(sources,new PotBalancesCalculator()),projections);}
	@Bean RecordedTaskExecutionMapperRegistry taskMapperRegistry(List<RecordedTaskExecutionMapper<?>> mappers){
		return new RecordedTaskExecutionMapperRegistry(mappers);}
	@Bean TaskExecutionHandlerRegistry taskHandlerRegistry(List<TaskExecutionHandler<?>> handlers){
		return new TaskExecutionHandlerRegistry(handlers);}
	@Bean ExecuteTaskUseCase executeTaskUseCase(TaskExecutionHandlerRegistry handlers){return new ExecuteTaskService(handlers);}
	@Bean TaskConsumptionLocator taskConsumptionLocator(PipelineDefinition pipeline,TaskConsumptionProperties properties,
			JpaTaskPort tasks,RecordedTaskExecutionMapperRegistry mappers,ExecuteTaskUseCase executeTask,Clock clock){
		return new TaskConsumptionLocator(pipeline,new WorkerSegment(properties.getSegmentIndex(),properties.getSegmentCount()),
				Set.copyOf(properties.getTaskTypes()),tasks,mappers,executeTask,new TaskConsumptionTechnicalFailureClassifier(clock));}
	@Bean ConsumptionOrchestrator taskConsumptionOrchestrator(TaskConsumptionLocator locator,
			AcquireConsumptionUseCase acquire,ExecuteConsumptionUseCase execute,HandleConsumptionFailureUseCase failure){
		return new SequentialConsumptionOrchestrator(locator,acquire,execute,failure);}
	@Bean ConsumptionPollingWorker taskConsumptionPollingWorker(ConsumptionOrchestrator orchestrator,
			TaskConsumptionProperties properties,Clock clock){
		var settings=new ConsumptionWorkerSettings(properties.isEnabled(),new WorkerId(properties.getWorkerId()),
				new ClaimLease(properties.getClaimLease()),new ConsumptionOrchestrationBudget(
				properties.getMaxCandidatesInspected(),properties.getMaxConsumptionsExecuted()),
				properties.getPollInterval(),properties.getRuntimeFailureBackoff());
		return new ConsumptionPollingWorker(orchestrator,settings,clock,new ConditionConsumptionWaiter());}
	@Bean SmartLifecycle taskConsumptionWorkerLifecycle(ConsumptionPollingWorker worker){return new TaskConsumptionWorkerLifecycle(worker);}
}
