package com.kartaguez.pocoma;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.engine.command.result.GetCommandResultService;
import com.kartaguez.pocoma.engine.command.result.GetCommandResultUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.read.binding.CurrentBindingProjectionPort;
import com.kartaguez.pocoma.engine.read.binding.GetCurrentBindingService;
import com.kartaguez.pocoma.engine.read.binding.GetCurrentBindingUseCase;

@Configuration
@ConditionalOnProperty(prefix = "pocoma.command-result-read", name = "enabled", havingValue = "true")
public class CommandResultReadConfiguration {
	@Bean
	GetCommandResultUseCase getCommandResultUseCase(ExactProjectionReadUseCase projections) {
		return new GetCommandResultService(projections);
	}

	@Bean
	GetCurrentBindingUseCase getCurrentBindingUseCase(CurrentBindingProjectionPort currentBindings) {
		return new GetCurrentBindingService(currentBindings);
	}
}
