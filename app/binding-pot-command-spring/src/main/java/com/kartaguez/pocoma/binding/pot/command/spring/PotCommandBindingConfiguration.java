package com.kartaguez.pocoma.binding.pot.command.spring;

import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pot.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.engine.service.command.PotAuthorizationGuard;
import com.kartaguez.pocoma.engine.command.decode.CommandDecoder;
import com.kartaguez.pocoma.engine.command.decode.CommandDecoderRegistry;
import com.kartaguez.pocoma.engine.command.dispatch.CommandDispatcher;
import com.kartaguez.pocoma.engine.command.execution.ExecuteRecordedCommandService;
import com.kartaguez.pocoma.engine.command.execution.ExecuteRecordedCommandUseCase;
import com.kartaguez.pocoma.engine.command.port.out.EventAppendPort;
import com.kartaguez.pocoma.engine.command.port.out.RecordedCommandPort;
import com.kartaguez.pocoma.engine.pot.command.decode.PotCommandPayloadDecoders;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ExpenseSharesPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotContextPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotGlobalVersionPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotHeaderPort;
import com.kartaguez.pocoma.engine.port.out.persistence.PotShareholdersPort;
import com.kartaguez.pocoma.engine.service.command.AddPotShareholdersCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.service.command.CreateExpenseCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.service.command.CreatePotCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.service.command.DeleteExpenseCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.service.command.DeletePotCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.service.command.UpdateExpenseDetailsCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.service.command.UpdateExpenseSharesCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.service.command.UpdatePotDetailsCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.service.command.UpdatePotShareholdersDetailsCommandUseCaseAdapter;
import com.kartaguez.pocoma.engine.service.command.UpdatePotShareholdersWeightsCommandUseCaseAdapter;

/** Spring composition of the existing Pot Commands behind the generic Command engine. */
@Configuration(proxyBeanMethods = false)
public class PotCommandBindingConfiguration {

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
			Clock clock) {
		return new ExecuteRecordedCommandService(commands, decoder, dispatcher, events, clock);
	}
}
