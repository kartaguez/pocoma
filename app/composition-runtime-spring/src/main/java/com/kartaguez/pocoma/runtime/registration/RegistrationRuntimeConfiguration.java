package com.kartaguez.pocoma.runtime.registration;

import com.kartaguez.pocoma.runtime.publication.PrimaryEventProtocol;
import com.kartaguez.pocoma.port.transaction.PublishedEvents;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.PocomaObjectMapperConfiguration;
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
import com.kartaguez.pocoma.orchestrator.consumption.*;
import com.kartaguez.pocoma.orchestrator.consumption.model.ConsumptionOrchestrationBudget;
import com.kartaguez.pocoma.orchestrator.poll.consumption.*;
import com.kartaguez.pocoma.orchestrator.poll.consumption.wait.ConditionConsumptionWaiter;
import com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerOrchestrator;
import com.kartaguez.pocoma.runtime.consumption.ConsumptionOwnerTransactionRunner;
import com.kartaguez.pocoma.runtime.consumption.ConsumptionTerminalParticipant;

@EnableConfigurationProperties(RegistrationConsumptionProperties.class)
@Import({PocomaObjectMapperConfiguration.class, com.kartaguez.pocoma.PrimaryEventPublicationConfiguration.class})
public class RegistrationRuntimeConfiguration {
    @Bean Clock registrationClock(){return Clock.systemUTC();}
	@Bean TransactionRunner registrationTransactionRunner(PrimaryEventProtocol protocol){return new ConsumptionOwnerTransactionRunner(protocol.runner());}
    @Bean JpaConsumptionLifecycleAdapter registrationLifecycle(JpaConsumptionSlotRepository s,JpaConsumptionClaimRepository c,@Qualifier("webApiObjectMapper") ObjectMapper m){return new JpaConsumptionLifecycleAdapter(s,c,m);}
    @Bean JpaConsumptionProvenanceAdapter registrationProvenance(JpaConsumptionInputRepository i,JpaConsumptionResultRepository r){return new JpaConsumptionProvenanceAdapter(i,r);}
    @Bean AcquireConsumptionUseCase registrationAcquire(@Qualifier("registrationLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("registrationTransactionRunner") TransactionRunner t,@Qualifier("registrationClock") Clock c){return new TransactionalAcquireConsumptionUseCase(new AcquireConsumptionService(l,c),t);}
	@Bean ExecuteConsumptionUseCase registrationExecute(@Qualifier("registrationLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("registrationProvenance") JpaConsumptionProvenanceAdapter p,@Qualifier("registrationTransactionRunner") TransactionRunner t,@Qualifier("registrationClock") Clock c, @Qualifier("registrationTerminalParticipant") ConsumptionTerminalParticipant terminalParticipant){return new TransactionalExecuteConsumptionUseCase(new ExecuteConsumptionService(terminalParticipant.finalizer()),t);}
	@Bean HandleConsumptionFailureUseCase registrationFailure(@Qualifier("registrationLifecycle") JpaConsumptionLifecycleAdapter l,@Qualifier("registrationTransactionRunner") TransactionRunner t,@Qualifier("registrationClock") Clock c, @Qualifier("registrationTerminalParticipant") ConsumptionTerminalParticipant terminalParticipant){return new TransactionalHandleConsumptionFailureUseCase(new HandleConsumptionFailureService(l,l,RegistrationConsumptionLocator.failurePolicy(),c, terminalParticipant.finalizer()),t);}
    @Bean ExecuteRegistrationService registrationService(RegistrationRequestReader r,RegistrationOutcomeRepository o,UserAuthorityPort u,ExternalIdentityBindingPort b,UserCreatedFactPort f, PublishedEvents publishedEvents){return new ExecuteRegistrationService(r,o,u,b,f,publishedEvents);}
    @Bean RegistrationConsumptionLocator registrationLocator(RegistrationConsumptionProperties p,RegistrationDiscoveryPort d,ExecuteRegistrationService r,@Qualifier("registrationClock") Clock c){return new RegistrationConsumptionLocator(p.getSegmentIndex(),p.getSegmentCount(),d,r,c);}
	@Bean ConsumptionOrchestrator registrationOrchestrator(RegistrationConsumptionLocator l,@Qualifier("registrationAcquire") AcquireConsumptionUseCase a,@Qualifier("registrationExecute") ExecuteConsumptionUseCase e,@Qualifier("registrationFailure") HandleConsumptionFailureUseCase f, @Qualifier("registrationLifecycle") JpaConsumptionLifecycleAdapter ownership){return guarded(new SequentialConsumptionOrchestrator(l,a,e,f, ownership));}
    @Bean ConsumptionPollingWorker registrationWorker(@Qualifier("registrationOrchestrator") ConsumptionOrchestrator o,RegistrationConsumptionProperties p,@Qualifier("registrationClock") Clock c){return new ConsumptionPollingWorker(o,new ConsumptionWorkerSettings(p.isEnabled(),new WorkerId(p.getWorkerId()),new ClaimLease(p.getClaimLease()),new ConsumptionOrchestrationBudget(p.getMaxCandidatesInspected(),p.getMaxConsumptionsExecuted()),p.getPollInterval(),p.getRuntimeFailureBackoff()),c,new ConditionConsumptionWaiter());}
    @Bean SmartLifecycle registrationWorkerLifecycle(@Qualifier("registrationWorker") ConsumptionPollingWorker w){return new RegistrationWorkerLifecycle(w);}
	private static ConsumptionOrchestrator guarded(ConsumptionOrchestrator delegate) {
		return new ConsumptionOwnerOrchestrator(delegate);
}
	@Bean ConsumptionTerminalParticipant registrationTerminalParticipant(@Qualifier("registrationLifecycle") JpaConsumptionLifecycleAdapter lifecycle,
			@Qualifier("registrationProvenance") JpaConsumptionProvenanceAdapter provenance, @Qualifier("registrationClock") Clock clock) {
		return new ConsumptionTerminalParticipant(lifecycle, provenance, clock);
	}
}
