package com.kartaguez.pocoma;

import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.authorization.ExternalAuthorityPermissionTranslator;
import com.kartaguez.pocoma.domain.pot.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.port.binding.authority.ExternalIdentityBindingPort;
import com.kartaguez.pocoma.engine.write.pot.service.PotAuthorizationGuard;
import com.kartaguez.pocoma.engine.consume.command.decode.CommandDecoder;
import com.kartaguez.pocoma.engine.consume.command.decode.CommandDecoderRegistry;
import com.kartaguez.pocoma.engine.consume.command.dispatch.CommandDispatcher;
import com.kartaguez.pocoma.engine.consume.command.execution.ExecuteRecordedCommandService;
import com.kartaguez.pocoma.engine.consume.command.execution.ExecuteRecordedCommandUseCase;
import com.kartaguez.pocoma.engine.consume.command.port.out.EventAppendPort;
import com.kartaguez.pocoma.engine.consume.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.engine.consume.command.pot.decode.PotCommandPayloadDecoders;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotHeaderPort;
import com.kartaguez.pocoma.engine.write.pot.port.persistence.PotShareholdersPort;
import com.kartaguez.pocoma.engine.consume.command.pot.dispatch.AddPotShareholdersCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.consume.command.pot.dispatch.CreateExpenseCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.consume.command.pot.dispatch.CreatePotCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.consume.command.pot.dispatch.DeleteExpenseCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.consume.command.pot.dispatch.DeletePotCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.consume.command.pot.dispatch.UpdateExpenseDetailsCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.consume.command.pot.dispatch.UpdateExpenseSharesCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.consume.command.pot.dispatch.UpdatePotDetailsCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.consume.command.pot.dispatch.UpdatePotShareholdersDetailsCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.consume.command.pot.dispatch.UpdatePotShareholdersWeightsCommandUseCaseAdapter;

/** Spring composition of the existing Pot Commands behind the generic Command engine. */
@Configuration(proxyBeanMethods = false)
public class WriteSidePotCommandBindingConfiguration {

	@Bean
	CommandDecoder potCommandDecoder(ObjectMapper objectMapper) {
		return new CommandDecoderRegistry(PotCommandPayloadDecoders.all(objectMapper));
	}

	@Bean
	CommandDispatcher potCommandDispatcher(
			PotContextPort potContexts,
			ExpenseContextPort expenseContexts,
			PotGlobalVersionPort versions,
			PotHeaderPort potHeaders,
			PotShareholdersPort shareholders,
			ExpenseHeaderPort expenseHeaders,
			ExpenseSharesPort expenseShares) {
		PotAuthorizationGuard authorizationGuard = new PotAuthorizationGuard();
		return new CommandDispatcher(List.of(
				new CreatePotCommandUseCaseAdapter(versions, potHeaders, new CreatePotAuthorizationPolicy()),
				new CreateExpenseCommandUseCaseAdapter(potContexts, versions, expenseHeaders, expenseShares,
						authorizationGuard),
				new AddPotShareholdersCommandUseCaseAdapter(potContexts, shareholders, versions,
						authorizationGuard),
				new DeletePotCommandUseCaseAdapter(potContexts, potHeaders, versions,
						authorizationGuard),
				new DeleteExpenseCommandUseCaseAdapter(expenseContexts, expenseHeaders, versions,
						authorizationGuard),
				new UpdatePotDetailsCommandUseCaseAdapter(potContexts, potHeaders, versions,
						authorizationGuard),
				new UpdateExpenseDetailsCommandUseCaseAdapter(expenseContexts, expenseHeaders, versions,
						authorizationGuard),
				new UpdateExpenseSharesCommandUseCaseAdapter(expenseContexts, expenseShares, versions,
						authorizationGuard),
				new UpdatePotShareholdersDetailsCommandUseCaseAdapter(potContexts, shareholders, versions,
						authorizationGuard),
				new UpdatePotShareholdersWeightsCommandUseCaseAdapter(potContexts, shareholders, versions,
						authorizationGuard)));
	}

	@Bean
	ExecuteRecordedCommandUseCase executeRecordedCommandUseCase(
			RecordedCommandPort commands,
			CommandDecoder decoder,
			CommandDispatcher dispatcher,
			EventAppendPort events,
			ExternalIdentityBindingPort bindings,
			Clock clock) {
		return new ExecuteRecordedCommandService(commands, decoder, dispatcher, events,
				bindings, new ExternalAuthorityPermissionTranslator(), clock);
	}
}
