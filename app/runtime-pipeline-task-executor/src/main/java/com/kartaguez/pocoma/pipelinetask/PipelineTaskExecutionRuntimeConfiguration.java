package com.kartaguez.pocoma.pipelinetask;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.engine.port.in.taskexecution.usecase.ExecuteTaskUseCase;
import com.kartaguez.pocoma.engine.service.taskexecution.ExecuteTaskService;
import com.kartaguez.pocoma.engine.service.taskexecution.TaskExecutionHandlerRegistry;

@Configuration
public class PipelineTaskExecutionRuntimeConfiguration {

	@Bean
	@ConditionalOnMissingBean
	ObjectMapper pipelineTaskObjectMapper() {
		return new ObjectMapper();
	}

	@Bean
	ExecuteBalanceProjectionTaskHandler executeBalanceProjectionTaskHandler(
			ComputePotBalancesUseCase computePotBalancesUseCase) {
		return new ExecuteBalanceProjectionTaskHandler(computePotBalancesUseCase);
	}

	@Bean
	TaskExecutionHandlerRegistry taskExecutionHandlerRegistry(
			ExecuteBalanceProjectionTaskHandler balanceHandler) {
		return new TaskExecutionHandlerRegistry(List.of(balanceHandler));
	}

	@Bean
	ExecuteTaskUseCase executeTaskUseCase(TaskExecutionHandlerRegistry registry) {
		return new ExecuteTaskService(registry);
	}

	@Bean
	ComputeBalancesPipelineTaskExecutionStrategy computeBalancesPipelineTaskExecutionStrategy(
			ExecuteTaskUseCase executeTaskUseCase,
			ObjectMapper objectMapper) {
		return new ComputeBalancesPipelineTaskExecutionStrategy(executeTaskUseCase, objectMapper);
	}
}
