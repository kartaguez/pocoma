package com.kartaguez.pocoma.supra.worker.projection.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.supra.worker.projection.core.SegmentedProjectionWorker;

@Configuration
@EnableConfigurationProperties(ProjectionWorkerProperties.class)
@ConditionalOnProperty(prefix = "pocoma.projection.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ProjectionWorkerConfiguration {

	@Bean
	@ConditionalOnMissingBean
	SegmentedProjectionWorker segmentedProjectionWorker(
			ComputePotBalancesUseCase computePotBalancesUseCase,
			ProjectionWorkerProperties properties) {
		return new SegmentedProjectionWorker(computePotBalancesUseCase, properties.toSettings());
	}

	@Bean
	@ConditionalOnMissingBean
	SpringProjectionWorkerLifecycle springProjectionWorkerLifecycle(SegmentedProjectionWorker worker) {
		return new SpringProjectionWorkerLifecycle(worker);
	}

	@Bean
	@ConditionalOnMissingBean
	ProjectionEventListener projectionEventListener(SegmentedProjectionWorker worker) {
		return new ProjectionEventListener(worker);
	}
}
