package com.kartaguez.pocoma.supra.worker.projection.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProjectionTaskBuilderWorkerConfigurationTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withConfiguration(AutoConfigurations.of(ProjectionTaskBuilderWorkerConfiguration.class))
			.withPropertyValues("pocoma.projection.worker.enabled=false");

	@Test
	void backsOffWhenProjectionWorkerIsDisabled() {
		contextRunner.run(context -> assertThat(context).doesNotHaveBean(ProjectionTaskBuilderWorkerConfiguration.class));
	}
}
