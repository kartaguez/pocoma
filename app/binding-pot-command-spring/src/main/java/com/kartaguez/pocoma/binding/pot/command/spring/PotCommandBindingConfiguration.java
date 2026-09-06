package com.kartaguez.pocoma.binding.pot.command.spring;

import java.time.Clock;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.pot.policy.AddPotShareholdersAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.CreateExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.CreatePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.DeleteExpenseAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.DeletePotAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.UpdateExpenseDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.UpdateExpenseSharesAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.UpdatePotDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.UpdatePotShareholdersDetailsAuthorizationPolicy;
import com.kartaguez.pocoma.domain.pot.policy.UpdatePotShareholdersWeightsAuthorizationPolicy;
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
		return new CommandDispatcher(List.of(
				new CreatePotCommandUseCaseAdapter(versions, potHeaders, new CreatePotAuthorizationPolicy()),
				new CreateExpenseCommandUseCaseAdapter(potContexts, versions, expenseHeaders, expenseShares,
						new CreateExpenseAuthorizationPolicy()),
				new AddPotShareholdersCommandUseCaseAdapter(potContexts, shareholders, versions,
						new AddPotShareholdersAuthorizationPolicy()),
				new DeletePotCommandUseCaseAdapter(potContexts, potHeaders, versions,
						new DeletePotAuthorizationPolicy()),
				new DeleteExpenseCommandUseCaseAdapter(expenseContexts, expenseHeaders, versions,
						new DeleteExpenseAuthorizationPolicy()),
				new UpdatePotDetailsCommandUseCaseAdapter(potContexts, potHeaders, versions,
						new UpdatePotDetailsAuthorizationPolicy()),
				new UpdateExpenseDetailsCommandUseCaseAdapter(expenseContexts, expenseHeaders, versions,
						new UpdateExpenseDetailsAuthorizationPolicy()),
				new UpdateExpenseSharesCommandUseCaseAdapter(expenseContexts, expenseShares, versions,
						new UpdateExpenseSharesAuthorizationPolicy()),
				new UpdatePotShareholdersDetailsCommandUseCaseAdapter(potContexts, shareholders, versions,
						new UpdatePotShareholdersDetailsAuthorizationPolicy()),
				new UpdatePotShareholdersWeightsCommandUseCaseAdapter(potContexts, shareholders, versions,
						new UpdatePotShareholdersWeightsAuthorizationPolicy())));
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
