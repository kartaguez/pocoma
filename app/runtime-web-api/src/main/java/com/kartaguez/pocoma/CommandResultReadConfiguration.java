package com.kartaguez.pocoma;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.engine.read.commandresult.GetCommandResultService;
import com.kartaguez.pocoma.engine.read.commandresult.GetCommandResultUseCase;
import com.kartaguez.pocoma.engine.read.commandresult.CommandResultStore;
import com.kartaguez.pocoma.engine.read.currentbinding.port.CurrentBindingReadPort;
import com.kartaguez.pocoma.engine.read.currentbinding.GetCurrentBindingService;
import com.kartaguez.pocoma.engine.read.currentbinding.GetCurrentBindingUseCase;

@Configuration
@ConditionalOnProperty(prefix = "pocoma.command-result-read", name = "enabled", havingValue = "true")
public class CommandResultReadConfiguration {
	@Bean
	GetCommandResultUseCase getCommandResultUseCase(CommandResultStore results) {
		return new GetCommandResultService(results);
	}

	@Bean
	GetCurrentBindingUseCase getCurrentBindingUseCase(CurrentBindingReadPort currentBindings) {
		return new GetCurrentBindingService(currentBindings);
	}
}
