package com.kartaguez.pocoma.runtime.binding;

import java.time.Clock;
import java.util.Optional;

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
import com.kartaguez.pocoma.domain.useridentity.ExternalIdentityBindingFactPort;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.*;
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.read.binding.*;
import com.kartaguez.pocoma.engine.service.consumption.*;
import com.kartaguez.pocoma.engine.service.transaction.consumption.*;
import com.kartaguez.pocoma.infra.persistence.jpa.adapter.consumption.*;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.locator.consumption.binding.*;
import com.kartaguez.pocoma.orchestrator.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.supra.consumption.*;
import com.kartaguez.pocoma.supra.consumption.wait.ConditionConsumptionWaiter;

@Configuration
@EnableConfigurationProperties(BindingConsumptionProperties.class)
public class BindingRuntimeConfiguration {
	@Bean @ConditionalOnMissingBean Clock bindingClock(){return Clock.systemUTC();}
	@Bean @ConditionalOnMissingBean ObjectMapper bindingObjectMapper(){return new ObjectMapper();}
	@Bean TransactionRunner bindingTransactionRunner(PlatformTransactionManager manager){return new SpringTransactionRunner(new TransactionTemplate(manager));}
	@Bean JpaConsumptionLifecycleAdapter bindingLifecycle(JpaConsumptionSlotRepository s, JpaConsumptionClaimRepository c,ObjectMapper m){return new JpaConsumptionLifecycleAdapter(s,c,m);}
	@Bean JpaConsumptionProvenanceAdapter bindingProvenance(JpaConsumptionInputRepository i,JpaConsumptionResultRepository r){return new JpaConsumptionProvenanceAdapter(i,r);}
	@Bean AcquireConsumptionUseCase bindingAcquire(JpaConsumptionLifecycleAdapter l,TransactionRunner t,Clock c){return new TransactionalAcquireConsumptionUseCase(new AcquireConsumptionService(l,c),t);}
	@Bean ExecuteConsumptionUseCase bindingExecute(JpaConsumptionLifecycleAdapter l,JpaConsumptionProvenanceAdapter p,TransactionRunner t,Clock c){return new TransactionalExecuteConsumptionUseCase(new ExecuteConsumptionService(l,p,c),t);}
	@Bean HandleConsumptionFailureUseCase bindingFailure(JpaConsumptionLifecycleAdapter l,TransactionRunner t,Clock c){return new TransactionalHandleConsumptionFailureUseCase(new HandleConsumptionFailureService(l,l,new BindingFactFailurePolicy(),c),t);}
	@Bean BindingFactConsumptionLocator bindingLocator(BindingConsumptionProperties p,BindingFactDiscoveryPort d,ExternalIdentityBindingFactPort f,CurrentBindingProjectionPort projection,Clock c){return new BindingFactConsumptionLocator(p.getSegmentIndex(),p.getSegmentCount(),d,f,projection,c);}
	@Bean ConsumptionOrchestrator bindingOrchestrator(BindingFactConsumptionLocator l,AcquireConsumptionUseCase a,ExecuteConsumptionUseCase e,HandleConsumptionFailureUseCase f){return new SequentialConsumptionOrchestrator(l,a,e,f);}
	@Bean ConsumptionPollingWorker bindingWorker(ConsumptionOrchestrator o,BindingConsumptionProperties p,Clock c){return new ConsumptionPollingWorker(o,new ConsumptionWorkerSettings(p.isEnabled(),new WorkerId(p.getWorkerId()),new ClaimLease(p.getClaimLease()),new ConsumptionOrchestrationBudget(p.getMaxCandidatesInspected(),p.getMaxConsumptionsExecuted()),p.getPollInterval(),p.getRuntimeFailureBackoff()),c,new ConditionConsumptionWaiter());}
	@Bean Runnable historicalBindingBootstrap(HistoricalBindingSourcePort s,CurrentBindingProjectionPort p,Clock c,TransactionRunner t,BindingConsumptionProperties properties){
		var bootstrap=new HistoricalBindingBootstrap(s,p,c);
		return ()->{if(!properties.isBootstrapEnabled())return; Optional<HistoricalBindingSourcePort.ExternalIdentityCursor> cursor=Optional.empty(); do {var current=cursor; cursor=t.runInTransaction(()->bootstrap.runPage(current,properties.getBootstrapPageSize()));} while(cursor.isPresent());};
	}
	@Bean SmartLifecycle bindingWorkerLifecycle(ConsumptionPollingWorker w,Runnable historicalBindingBootstrap){return new BindingWorkerLifecycle(w,historicalBindingBootstrap);}
}
