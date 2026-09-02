package com.kartaguez.pocoma.runtime.task.consumption;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

class TaskConsumptionPipelineConfigurationTest {
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(PipelineOnlyConfiguration.class)
			.withPropertyValues("pocoma.task-consumption.pipeline-id=balance-projection");

	@Test
	void failsFastWhenPipelineVersionIsMissing() {
		contextRunner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
					.hasRootCauseMessage("pocoma.task-consumption.pipeline-version is required");
		});
	}

	@Test
	void createsTheConfiguredPipelineWithAnExplicitVersion() {
		contextRunner.withPropertyValues("pocoma.task-consumption.pipeline-version=2").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(PipelineDefinition.class).pipelineVersion()).isEqualTo(2);
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(TaskConsumptionProperties.class)
	static class PipelineOnlyConfiguration {
		@Bean
		PipelineDefinition taskPipeline(TaskConsumptionProperties properties) {
			return new TaskConsumptionRuntimeConfiguration().taskPipeline(properties);
		}
	}
}
