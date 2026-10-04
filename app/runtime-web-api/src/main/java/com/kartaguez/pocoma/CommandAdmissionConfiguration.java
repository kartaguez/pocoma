package com.kartaguez.pocoma;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.port.transaction.TransactionRunner;
import com.kartaguez.pocoma.orchestrator.command.admission.CommandAuthenticationEvidenceFactory;
import com.kartaguez.pocoma.orchestrator.command.admission.SubmitRecordedCommandService;
import com.kartaguez.pocoma.orchestrator.command.admission.model.CommandAuthorizationTtl;
import com.kartaguez.pocoma.orchestrator.command.admission.port.in.SubmitRecordedCommandUseCase;
import com.kartaguez.pocoma.orchestrator.command.admission.port.out.CommandIdGenerator;

@Configuration
@ConditionalOnProperty(prefix = "pocoma.command-admission", name = "enabled", havingValue = "true")
public class CommandAdmissionConfiguration {

	@Bean
	@ConditionalOnMissingBean
	Clock commandAdmissionClock() {
		return Clock.systemUTC();
	}

	@Bean
	CommandAuthenticationEvidenceFactory commandAuthenticationEvidenceFactory(
			@Value("${pocoma.command-admission.authorization-ttl:PT15M}") Duration ttl) {
		return new CommandAuthenticationEvidenceFactory(new CommandAuthorizationTtl(ttl));
	}

	@Bean
	CommandIdGenerator commandIdGenerator() {
		return () -> new CommandId(UUID.randomUUID());
	}

	@Bean
	SubmitRecordedCommandUseCase submitRecordedCommandUseCase(
			RecordedCommandPort commands,
			CommandIdGenerator commandIds,
			CommandAuthenticationEvidenceFactory authenticationEvidence,
			Clock clock,
			TransactionRunner transactions) {
		return new SubmitRecordedCommandService(
				commands, commandIds, authenticationEvidence, clock, transactions);
	}
}
