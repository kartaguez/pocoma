package com.kartaguez.pocoma;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.engine.command.result.GetCommandResultService;
import com.kartaguez.pocoma.engine.command.result.GetCommandResultUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;

@Configuration
@ConditionalOnProperty(prefix = "pocoma.command-result-read", name = "enabled", havingValue = "true")
public class CommandResultReadConfiguration {
	@Bean
	GetCommandResultUseCase getCommandResultUseCase(ExactProjectionReadUseCase projections) {
		return new GetCommandResultService(projections);
	}
}
