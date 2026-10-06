package com.kartaguez.pocoma.runtime.registrationresult;

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
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.*;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.read.registrationresult.RegistrationResultStore;
import com.kartaguez.pocoma.engine.materialize.registrationresult.MaterializeRegistrationResultService;
import com.kartaguez.pocoma.engine.materialize.registrationresult.RegistrationResultSourcePort;
import com.kartaguez.pocoma.engine.service.consumption.*;
import com.kartaguez.pocoma.engine.service.transaction.consumption.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.*;
import com.kartaguez.pocoma.supra.consume.registrationresult.RegistrationResultConsumptionLocator;
import com.kartaguez.pocoma.engine.materialize.registrationresult.RegistrationResultDiscovery;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.orchestrator.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.poll.consumption.*;
import com.kartaguez.pocoma.orchestrator.poll.consumption.wait.ConditionConsumptionWaiter;

@EnableConfigurationProperties(RegistrationResultConsumptionProperties.class)
public class RegistrationResultRuntimeConfiguration {
    @Bean @ConditionalOnMissingBean Clock registrationResultClock(){return Clock.systemUTC();}
    @Bean @ConditionalOnMissingBean ObjectMapper registrationResultObjectMapper(){return new ObjectMapper();}
    @Bean TransactionRunner registrationResultTransactionRunner(PlatformTransactionManager manager){return new SpringTransactionRunner(new TransactionTemplate(manager));}
    @Bean JpaConsumptionLifecycleAdapter registrationResultLifecycle(JpaConsumptionSlotRepository s,JpaConsumptionClaimRepository c,ObjectMapper m){return new JpaConsumptionLifecycleAdapter(s,c,m);}
    @Bean JpaConsumptionProvenanceAdapter registrationResultProvenance(JpaConsumptionInputRepository i,JpaConsumptionResultRepository r){return new JpaConsumptionProvenanceAdapter(i,r);}
    @Bean AcquireConsumptionUseCase registrationResultAcquire(@Qualifier("registrationResultLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("registrationResultTransactionRunner") TransactionRunner t,Clock c){return new TransactionalAcquireConsumptionUseCase(new AcquireConsumptionService(l,c),t);}
    @Bean ExecuteConsumptionUseCase registrationResultExecute(@Qualifier("registrationResultLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("registrationResultProvenance") JpaConsumptionProvenanceAdapter p,@Qualifier("registrationResultTransactionRunner") TransactionRunner t,Clock c){return new TransactionalExecuteConsumptionUseCase(new ExecuteConsumptionService(l,p,c),t);}
    @Bean HandleConsumptionFailureUseCase registrationResultFailure(@Qualifier("registrationResultLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("registrationResultTransactionRunner") TransactionRunner t,Clock c){return new TransactionalHandleConsumptionFailureUseCase(new HandleConsumptionFailureService(l,l,RegistrationResultConsumptionLocator.failurePolicy(),c),t);}
    @Bean MaterializeRegistrationResultService registrationResultMaterializer(RegistrationResultSourcePort source,RegistrationResultStore s){return new MaterializeRegistrationResultService(source,s);}
    @Bean RegistrationResultConsumptionLocator registrationResultLocator(RegistrationResultConsumptionProperties p,RegistrationResultDiscovery d,MaterializeRegistrationResultService m,Clock c){return new RegistrationResultConsumptionLocator(p.getSegmentIndex(),p.getSegmentCount(),d,m,c);}
    @Bean ConsumptionOrchestrator registrationResultOrchestrator(RegistrationResultConsumptionLocator l,@Qualifier("registrationResultAcquire") AcquireConsumptionUseCase a,@Qualifier("registrationResultExecute") ExecuteConsumptionUseCase e,@Qualifier("registrationResultFailure") HandleConsumptionFailureUseCase f){return new SequentialConsumptionOrchestrator(l,a,e,f);}
    @Bean ConsumptionPollingWorker registrationResultWorker(@Qualifier("registrationResultOrchestrator") ConsumptionOrchestrator o,RegistrationResultConsumptionProperties p,Clock c){return new ConsumptionPollingWorker(o,new ConsumptionWorkerSettings(p.isEnabled(),new WorkerId(p.getWorkerId()),new ClaimLease(p.getClaimLease()),new ConsumptionOrchestrationBudget(p.getMaxCandidatesInspected(),p.getMaxConsumptionsExecuted()),p.getPollInterval(),p.getRuntimeFailureBackoff()),c,new ConditionConsumptionWaiter());}
    @Bean SmartLifecycle registrationResultWorkerLifecycle(@Qualifier("registrationResultWorker") ConsumptionPollingWorker w){return new RegistrationResultWorkerLifecycle(w);}
}
