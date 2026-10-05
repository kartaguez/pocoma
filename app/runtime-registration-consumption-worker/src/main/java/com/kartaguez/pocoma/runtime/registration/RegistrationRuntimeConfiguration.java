package com.kartaguez.pocoma.runtime.registration;

import java.time.Clock;

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
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.port.binding.authority.UserAuthorityPort;
import com.kartaguez.pocoma.engine.port.in.consumption.usecase.*;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.engine.consume.registration.*;
import com.kartaguez.pocoma.supra.consume.registration.RegistrationConsumptionLocator;
import com.kartaguez.pocoma.engine.service.consumption.*;
import com.kartaguez.pocoma.engine.service.transaction.consumption.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.adapter.consumption.*;
import com.kartaguez.pocoma.infra.persistence.primary.jpa.repository.consumption.*;
import com.kartaguez.pocoma.infra.tx.spring.SpringTransactionRunner;
import com.kartaguez.pocoma.orchestrator.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.poll.consumption.*;
import com.kartaguez.pocoma.orchestrator.poll.consumption.wait.ConditionConsumptionWaiter;

@Configuration
@EnableConfigurationProperties(RegistrationConsumptionProperties.class)
public class RegistrationRuntimeConfiguration {
    @Bean @ConditionalOnMissingBean Clock registrationClock(){return Clock.systemUTC();}
    @Bean @ConditionalOnMissingBean ObjectMapper registrationObjectMapper(){return new ObjectMapper();}
    @Bean TransactionRunner registrationTransactionRunner(PlatformTransactionManager manager){return new SpringTransactionRunner(new TransactionTemplate(manager));}
    @Bean JpaConsumptionLifecycleAdapter registrationLifecycle(JpaConsumptionSlotRepository s,JpaConsumptionClaimRepository c,ObjectMapper m){return new JpaConsumptionLifecycleAdapter(s,c,m);}
    @Bean JpaConsumptionProvenanceAdapter registrationProvenance(JpaConsumptionInputRepository i,JpaConsumptionResultRepository r){return new JpaConsumptionProvenanceAdapter(i,r);}
    @Bean AcquireConsumptionUseCase registrationAcquire(JpaConsumptionLifecycleAdapter l,TransactionRunner t,Clock c){return new TransactionalAcquireConsumptionUseCase(new AcquireConsumptionService(l,c),t);}
    @Bean ExecuteConsumptionUseCase registrationExecute(JpaConsumptionLifecycleAdapter l,JpaConsumptionProvenanceAdapter p,TransactionRunner t,Clock c){return new TransactionalExecuteConsumptionUseCase(new ExecuteConsumptionService(l,p,c),t);}
    @Bean HandleConsumptionFailureUseCase registrationFailure(JpaConsumptionLifecycleAdapter l,TransactionRunner t,Clock c){return new TransactionalHandleConsumptionFailureUseCase(new HandleConsumptionFailureService(l,l,RegistrationConsumptionLocator.failurePolicy(),c),t);}
    @Bean ExecuteRegistrationService registrationService(RegistrationRequestReader r,RegistrationOutcomeRepository o,UserAuthorityPort u,ExternalIdentityBindingPort b,UserCreatedFactPort f){return new ExecuteRegistrationService(r,o,u,b,f);}
    @Bean RegistrationConsumptionLocator registrationLocator(RegistrationConsumptionProperties p,RegistrationDiscoveryPort d,ExecuteRegistrationService r,Clock c){return new RegistrationConsumptionLocator(p.getSegmentIndex(),p.getSegmentCount(),d,r,c);}
    @Bean ConsumptionOrchestrator registrationOrchestrator(RegistrationConsumptionLocator l,AcquireConsumptionUseCase a,ExecuteConsumptionUseCase e,HandleConsumptionFailureUseCase f){return new SequentialConsumptionOrchestrator(l,a,e,f);}
    @Bean ConsumptionPollingWorker registrationWorker(ConsumptionOrchestrator o,RegistrationConsumptionProperties p,Clock c){return new ConsumptionPollingWorker(o,new ConsumptionWorkerSettings(p.isEnabled(),new WorkerId(p.getWorkerId()),new ClaimLease(p.getClaimLease()),new ConsumptionOrchestrationBudget(p.getMaxCandidatesInspected(),p.getMaxConsumptionsExecuted()),p.getPollInterval(),p.getRuntimeFailureBackoff()),c,new ConditionConsumptionWaiter());}
    @Bean SmartLifecycle registrationWorkerLifecycle(ConsumptionPollingWorker w){return new RegistrationWorkerLifecycle(w);}
}
