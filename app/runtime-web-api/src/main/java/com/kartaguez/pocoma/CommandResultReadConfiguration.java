package com.kartaguez.pocoma;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.engine.command.result.GetCommandResultService;
import com.kartaguez.pocoma.engine.command.result.GetCommandResultUseCase;
import com.kartaguez.pocoma.engine.port.in.projection.read.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.engine.port.out.projection.ProjectionReadPort;
import com.kartaguez.pocoma.engine.service.projection.read.ExactProjectionReads;
import com.kartaguez.pocoma.infra.projection.jsonschema.NetworkntJsonSchemaValidator;

@Configuration
@ConditionalOnProperty(prefix = "pocoma.command-result-read", name = "enabled", havingValue = "true")
public class CommandResultReadConfiguration {
	@Bean
	ProjectionValidator commandResultProjectionValidator(ObjectMapper mapper) {
		return new ProjectionValidator(new NetworkntJsonSchemaValidator(mapper));
	}

	@Bean
	ExactProjectionReadUseCase commandResultExactProjectionRead(
			ProjectionReadPort projections, ProjectionValidator validator) {
		return ExactProjectionReads.create(projections, validator);
	}

	@Bean
	GetCommandResultUseCase getCommandResultUseCase(ExactProjectionReadUseCase projections) {
		return new GetCommandResultService(projections);
	}
}
