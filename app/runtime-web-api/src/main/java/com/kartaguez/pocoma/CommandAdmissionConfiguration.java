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
import com.kartaguez.pocoma.engine.port.out.transaction.TransactionRunner;
import com.kartaguez.pocoma.orchestrator.command.admission.AuthorizationSnapshotFactory;
import com.kartaguez.pocoma.orchestrator.command.admission.ExternalAuthorityPermissionTranslator;
import com.kartaguez.pocoma.orchestrator.command.admission.SubmitRecordedCommandService;
import com.kartaguez.pocoma.orchestrator.command.admission.model.CommandAuthorizationTtl;
import com.kartaguez.pocoma.orchestrator.command.admission.port.in.SubmitRecordedCommandUseCase;
import com.kartaguez.pocoma.orchestrator.command.admission.port.out.CommandIdGenerator;
import com.kartaguez.pocoma.orchestrator.command.admission.port.out.ExternalIdentityResolverPort;

@Configuration
@ConditionalOnProperty(prefix = "pocoma.command-admission", name = "enabled", havingValue = "true")
public class CommandAdmissionConfiguration {

	@Bean
	@ConditionalOnMissingBean
	Clock commandAdmissionClock() {
		return Clock.systemUTC();
	}

	@Bean
	ExternalAuthorityPermissionTranslator externalAuthorityPermissionTranslator() {
		return new ExternalAuthorityPermissionTranslator();
	}

	@Bean
	AuthorizationSnapshotFactory authorizationSnapshotFactory(
			@Value("${pocoma.command-admission.authorization-ttl:PT15M}") Duration ttl,
			ExternalAuthorityPermissionTranslator permissions) {
		return new AuthorizationSnapshotFactory(new CommandAuthorizationTtl(ttl), permissions);
	}

	@Bean
	CommandIdGenerator commandIdGenerator() {
		return () -> new CommandId(UUID.randomUUID());
	}

	@Bean
	SubmitRecordedCommandUseCase submitRecordedCommandUseCase(
			ExternalIdentityResolverPort identities,
			RecordedCommandPort commands,
			CommandIdGenerator commandIds,
			AuthorizationSnapshotFactory snapshots,
			Clock clock,
			TransactionRunner transactions) {
		return new SubmitRecordedCommandService(
				identities, commands, commandIds, snapshots, clock, transactions);
	}
}
