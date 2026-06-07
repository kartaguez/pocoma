package com.kartaguez.pocoma.pipelinetask;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;

@Configuration
public class PipelineTaskExecutionRuntimeConfiguration {

	@Bean
	@ConditionalOnMissingBean
	ObjectMapper pipelineTaskObjectMapper() {
		return new ObjectMapper();
	}

	@Bean
	ComputeBalancesPipelineTaskExecutionStrategy computeBalancesPipelineTaskExecutionStrategy(
			ComputePotBalancesUseCase computePotBalancesUseCase,
			ObjectMapper objectMapper) {
		return new ComputeBalancesPipelineTaskExecutionStrategy(computePotBalancesUseCase, objectMapper);
	}
}
