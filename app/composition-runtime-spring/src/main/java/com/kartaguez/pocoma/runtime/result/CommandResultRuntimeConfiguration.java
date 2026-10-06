package com.kartaguez.pocoma.runtime.result;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.engine.read.commandresult.CommandResultStore;
import com.kartaguez.pocoma.engine.materialize.commandresult.MaterializeCommandResultService;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.*;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.service.consumption.*;
import com.kartaguez.pocoma.engine.service.transaction.consumption.*;
import com.kartaguez.pocoma.supra.consume.commandresult.CommandResultConsumptionLocator;
import com.kartaguez.pocoma.engine.materialize.commandresult.CommandResultDiscovery;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.orchestrator.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.poll.consumption.*;
import com.kartaguez.pocoma.orchestrator.poll.consumption.wait.ConditionConsumptionWaiter;

@EnableConfigurationProperties(CommandResultConsumptionProperties.class)
public class CommandResultRuntimeConfiguration {
	@Bean @ConditionalOnMissingBean Clock commandResultClock(){return Clock.systemUTC();}
	@Bean @ConditionalOnMissingBean ObjectMapper commandResultObjectMapper(){return new ObjectMapper();}
	@Bean TransactionRunner commandResultTransactionRunner(PlatformTransactionManager manager){return new SpringTransactionRunner(new TransactionTemplate(manager));}
	@Bean JpaConsumptionLifecycleAdapter commandResultLifecycle(JpaConsumptionSlotRepository s,JpaConsumptionClaimRepository c,ObjectMapper m){return new JpaConsumptionLifecycleAdapter(s,c,m);}
	@Bean JpaConsumptionProvenanceAdapter commandResultProvenance(JpaConsumptionInputRepository i,JpaConsumptionResultRepository r){return new JpaConsumptionProvenanceAdapter(i,r);}
	@Bean AcquireConsumptionUseCase commandResultAcquire(@Qualifier("commandResultLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("commandResultTransactionRunner") TransactionRunner t,Clock c){return new TransactionalAcquireConsumptionUseCase(new AcquireConsumptionService(l,c),t);}
	@Bean ExecuteConsumptionUseCase commandResultExecute(@Qualifier("commandResultLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("commandResultProvenance") JpaConsumptionProvenanceAdapter p,@Qualifier("commandResultTransactionRunner") TransactionRunner t,Clock c){return new TransactionalExecuteConsumptionUseCase(new ExecuteConsumptionService(l,p,c),t);}
	@Bean HandleConsumptionFailureUseCase commandResultFailure(@Qualifier("commandResultLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("commandResultTransactionRunner") TransactionRunner t,Clock c){return new TransactionalHandleConsumptionFailureUseCase(new HandleConsumptionFailureService(l,l,CommandResultConsumptionLocator.failurePolicy(),c),t);}
	@Bean MaterializeCommandResultService commandResultMaterializer(CommandResultStore store){return new MaterializeCommandResultService(store);}
	@Bean CommandResultConsumptionLocator commandResultLocator(CommandResultConsumptionProperties p,CommandResultDiscovery s,MaterializeCommandResultService m,Clock c){return new CommandResultConsumptionLocator(p.getSegmentIndex(),p.getSegmentCount(),s,m,c);}
	@Bean ConsumptionOrchestrator commandResultOrchestrator(CommandResultConsumptionLocator l,@Qualifier("commandResultAcquire") AcquireConsumptionUseCase a,@Qualifier("commandResultExecute") ExecuteConsumptionUseCase e,@Qualifier("commandResultFailure") HandleConsumptionFailureUseCase f){return new SequentialConsumptionOrchestrator(l,a,e,f);}
	@Bean ConsumptionPollingWorker commandResultWorker(@Qualifier("commandResultOrchestrator") ConsumptionOrchestrator o,CommandResultConsumptionProperties p,Clock c){return new ConsumptionPollingWorker(o,new ConsumptionWorkerSettings(p.isEnabled(),new WorkerId(p.getWorkerId()),new ClaimLease(p.getClaimLease()),new ConsumptionOrchestrationBudget(p.getMaxCandidatesInspected(),p.getMaxConsumptionsExecuted()),p.getPollInterval(),p.getRuntimeFailureBackoff()),c,new ConditionConsumptionWaiter());}
	@Bean SmartLifecycle commandResultWorkerLifecycle(@Qualifier("commandResultWorker") ConsumptionPollingWorker w){return new CommandResultWorkerLifecycle(w);}
}
