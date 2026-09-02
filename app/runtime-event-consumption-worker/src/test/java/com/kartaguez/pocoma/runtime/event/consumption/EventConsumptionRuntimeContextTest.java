package com.kartaguez.pocoma.runtime.event.consumption;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.domain.pipeline.PipelineDefinition;

class EventConsumptionRuntimeContextTest {
	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(PipelineOnlyConfiguration.class)
			.withPropertyValues("pocoma.event-consumption.pipeline-id=balance-projection");

	@Test
	void failsFastWhenPipelineVersionIsMissing() {
		contextRunner.run(context -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure())
					.hasRootCauseMessage("pocoma.event-consumption.pipeline-version is required");
		});
	}

	@Test
	void createsTheConfiguredPipelineWithAnExplicitVersion() {
		contextRunner.withPropertyValues("pocoma.event-consumption.pipeline-version=2").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(PipelineDefinition.class).pipelineVersion()).isEqualTo(2);
		});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(EventConsumptionProperties.class)
	static class PipelineOnlyConfiguration {
		@Bean
		PipelineDefinition eventConsumptionPipeline(EventConsumptionProperties properties) {
			return new EventConsumptionRuntimeConfiguration().eventConsumptionPipeline(properties);
		}
	}
}
