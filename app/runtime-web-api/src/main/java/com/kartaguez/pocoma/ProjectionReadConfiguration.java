package com.kartaguez.pocoma;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.domain.projection.ProjectionValidator;
import com.kartaguez.pocoma.engine.read.projection.port.ExactProjectionReadUseCase;
import com.kartaguez.pocoma.port.projection.ProjectionReadPort;
import com.kartaguez.pocoma.engine.read.projection.service.ExactProjectionReads;
import com.kartaguez.pocoma.infra.projection.validation.networknt.NetworkntJsonSchemaValidator;

@Configuration
public class ProjectionReadConfiguration {
	@Bean
	ProjectionValidator projectionValidator(ObjectMapper mapper) {
		return new ProjectionValidator(new NetworkntJsonSchemaValidator(mapper));
	}

	@Bean
	ExactProjectionReadUseCase exactProjectionRead(ProjectionReadPort projections, ProjectionValidator validator) {
		return ExactProjectionReads.create(projections, validator);
	}
}
