package com.kartaguez.pocoma.supra.worker.projection.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.observability.api.NoopPocomaObservation;
import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.DirectSegmentedProjectionWorker;

@Configuration
@EnableConfigurationProperties(ProjectionWorkerProperties.class)
@ConditionalOnProperty(prefix = "pocoma.projection.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "pocoma.projection.worker", name = "mode", havingValue = "direct")
public class DirectProjectionWorkerConfiguration {

	@Bean
	@ConditionalOnMissingBean
	PocomaObservation pocomaObservation() {
		return new NoopPocomaObservation();
	}

	@Bean
	@ConditionalOnMissingBean
	DirectSegmentedProjectionWorker directSegmentedProjectionWorker(
			ComputePotBalancesUseCase computePotBalancesUseCase,
			ProjectionWorkerProperties properties,
			PocomaObservation observation) {
		return new DirectSegmentedProjectionWorker(computePotBalancesUseCase, properties.toSettings(), observation);
	}

	@Bean
	@ConditionalOnMissingBean
	DirectProjectionWorkerLifecycle directProjectionWorkerLifecycle(DirectSegmentedProjectionWorker worker) {
		return new DirectProjectionWorkerLifecycle(worker);
	}

	@Bean
	@ConditionalOnMissingBean
	DirectProjectionEventListener directProjectionEventListener(DirectSegmentedProjectionWorker worker) {
		return new DirectProjectionEventListener(worker);
	}
}
