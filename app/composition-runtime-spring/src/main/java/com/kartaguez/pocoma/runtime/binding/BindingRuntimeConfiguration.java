package com.kartaguez.pocoma.runtime.binding;

import com.kartaguez.pocoma.engine.materialize.currentbinding.port.CurrentBindingWritePort;
import com.kartaguez.pocoma.PocomaObjectMapperConfiguration;
import com.kartaguez.pocoma.engine.materialize.currentbinding.BindingFactDiscoveryPort;
import com.kartaguez.pocoma.engine.materialize.currentbinding.BindingFactReadPort;
import com.kartaguez.pocoma.engine.materialize.currentbinding.MaterializeCurrentBindingService;
import com.kartaguez.pocoma.supra.consume.binding.BindingFactConsumptionLocator;
import com.kartaguez.pocoma.supra.consume.binding.BindingFactFailurePolicy;

import java.time.Clock;

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
import com.kartaguez.pocoma.domain.consumption.claim.ClaimLease;
import com.kartaguez.pocoma.domain.consumption.claim.WorkerId;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.*;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.*;
import com.kartaguez.pocoma.engine.materialize.currentbinding.*;
import com.kartaguez.pocoma.engine.service.consumption.*;
import com.kartaguez.pocoma.engine.service.transaction.consumption.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.supra.consume.binding.*;
import com.kartaguez.pocoma.orchestrator.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.poll.consumption.*;
import com.kartaguez.pocoma.orchestrator.poll.consumption.wait.ConditionConsumptionWaiter;

@EnableConfigurationProperties(BindingConsumptionProperties.class)
@Import(PocomaObjectMapperConfiguration.class)
public class BindingRuntimeConfiguration {
	@Bean Clock bindingClock(){return Clock.systemUTC();}
	@Bean TransactionRunner bindingTransactionRunner(PlatformTransactionManager manager){return new SpringTransactionRunner(new TransactionTemplate(manager));}
	@Bean JpaConsumptionLifecycleAdapter bindingLifecycle(JpaConsumptionSlotRepository s, JpaConsumptionClaimRepository c,@Qualifier("webApiObjectMapper") ObjectMapper m){return new JpaConsumptionLifecycleAdapter(s,c,m);}
	@Bean JpaConsumptionProvenanceAdapter bindingProvenance(JpaConsumptionInputRepository i,JpaConsumptionResultRepository r){return new JpaConsumptionProvenanceAdapter(i,r);}
	@Bean AcquireConsumptionUseCase bindingAcquire(@Qualifier("bindingLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("bindingTransactionRunner") TransactionRunner t,@Qualifier("bindingClock") Clock c){return new TransactionalAcquireConsumptionUseCase(new AcquireConsumptionService(l,c),t);}
	@Bean ExecuteConsumptionUseCase bindingExecute(@Qualifier("bindingLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("bindingProvenance") JpaConsumptionProvenanceAdapter p,@Qualifier("bindingTransactionRunner") TransactionRunner t,@Qualifier("bindingClock") Clock c){return new TransactionalExecuteConsumptionUseCase(new ExecuteConsumptionService(l,p,c),t);}
	@Bean HandleConsumptionFailureUseCase bindingFailure(@Qualifier("bindingLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("bindingTransactionRunner") TransactionRunner t,@Qualifier("bindingClock") Clock c){return new TransactionalHandleConsumptionFailureUseCase(new HandleConsumptionFailureService(l,l,new BindingFactFailurePolicy(),c),t);}
	@Bean BindingFactConsumptionLocator bindingLocator(BindingConsumptionProperties p,BindingFactDiscoveryPort d,BindingFactReadPort f,CurrentBindingWritePort projection,@Qualifier("bindingClock") Clock c){return new BindingFactConsumptionLocator(p.getSegmentIndex(),p.getSegmentCount(),d,f,new MaterializeCurrentBindingService(projection,c),c);}
	@Bean ConsumptionOrchestrator bindingOrchestrator(BindingFactConsumptionLocator l,@Qualifier("bindingAcquire") AcquireConsumptionUseCase a,@Qualifier("bindingExecute") ExecuteConsumptionUseCase e,@Qualifier("bindingFailure") HandleConsumptionFailureUseCase f){return new SequentialConsumptionOrchestrator(l,a,e,f);}
	@Bean ConsumptionPollingWorker bindingWorker(@Qualifier("bindingOrchestrator") ConsumptionOrchestrator o,BindingConsumptionProperties p,@Qualifier("bindingClock") Clock c){return new ConsumptionPollingWorker(o,new ConsumptionWorkerSettings(p.isEnabled(),new WorkerId(p.getWorkerId()),new ClaimLease(p.getClaimLease()),new ConsumptionOrchestrationBudget(p.getMaxCandidatesInspected(),p.getMaxConsumptionsExecuted()),p.getPollInterval(),p.getRuntimeFailureBackoff()),c,new ConditionConsumptionWaiter());}
	@Bean SmartLifecycle bindingWorkerLifecycle(@Qualifier("bindingWorker") ConsumptionPollingWorker w){return new BindingWorkerLifecycle(w);}
}
