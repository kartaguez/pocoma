package com.kartaguez.pocoma.infra.pipeline.lifecycle.persistence;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinitionRegistry;
import com.kartaguez.pocoma.engine.pipeline.lifecycle.catalog.ProjectionProducerCatalog;
import com.kartaguez.pocoma.engine.port.in.pipeline.lifecycle.PipelineVersionLifecycleUseCase;
import com.kartaguez.pocoma.engine.service.pipeline.lifecycle.PipelineLifecycleIntegrityService;
import com.kartaguez.pocoma.engine.service.pipeline.lifecycle.PipelineVersionLifecycleService;

@AutoConfiguration
public class PipelineLifecyclePersistenceAutoConfiguration {
	@Bean
	@ConditionalOnMissingBean
	JdbcPipelineLifecycleAdapter jdbcPipelineLifecycleAdapter(JdbcTemplate jdbc) {
		return new JdbcPipelineLifecycleAdapter(jdbc);
	}

	@Bean
	@ConditionalOnBean({PipelineDefinitionRegistry.class, ProjectionProducerCatalog.class})
	PipelineVersionLifecycleUseCase pipelineVersionLifecycleUseCase(PipelineDefinitionRegistry definitions,
			ProjectionProducerCatalog producers, JdbcPipelineLifecycleAdapter persistence,
			ObjectProvider<Clock> clocks) {
		return new PipelineVersionLifecycleService(definitions, producers, persistence,
				clocks.getIfUnique(Clock::systemUTC));
	}

	@Bean
	@ConditionalOnBean({PipelineDefinitionRegistry.class, ProjectionProducerCatalog.class})
	SmartInitializingSingleton pipelineLifecycleIntegrityValidator(PipelineDefinitionRegistry definitions,
			ProjectionProducerCatalog producers, JdbcPipelineLifecycleAdapter persistence) {
		var validator = new PipelineLifecycleIntegrityService(definitions, producers, persistence);
		return validator::validate;
	}
}
