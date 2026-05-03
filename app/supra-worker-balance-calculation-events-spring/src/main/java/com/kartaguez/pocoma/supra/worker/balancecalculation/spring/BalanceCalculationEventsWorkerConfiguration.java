package com.kartaguez.pocoma.supra.worker.balancecalculation.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.kartaguez.pocoma.engine.port.in.projection.usecase.ComputePotBalancesUseCase;
import com.kartaguez.pocoma.observability.api.NoopPocomaObservation;
import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.supra.worker.balancecalculation.core.SegmentedBalanceCalculationWorker;

@Configuration
@EnableConfigurationProperties(BalanceCalculationWorkerProperties.class)
@ConditionalOnProperty(prefix = "pocoma.projection.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "pocoma.projection.worker", name = "mode", havingValue = "spring-events")
public class BalanceCalculationEventsWorkerConfiguration {

	@Bean
	@ConditionalOnMissingBean
	PocomaObservation pocomaObservation() {
		return new NoopPocomaObservation();
	}

	@Bean
	@ConditionalOnMissingBean
	SegmentedBalanceCalculationWorker segmentedBalanceCalculationWorker(
			ComputePotBalancesUseCase computePotBalancesUseCase,
			BalanceCalculationWorkerProperties properties,
			PocomaObservation observation) {
		return new SegmentedBalanceCalculationWorker(computePotBalancesUseCase, properties.toSettings(), observation);
	}

	@Bean
	@ConditionalOnMissingBean
	BalanceCalculationWorkerLifecycle balanceCalculationWorkerLifecycle(SegmentedBalanceCalculationWorker worker) {
		return new BalanceCalculationWorkerLifecycle(worker);
	}

	@Bean
	@ConditionalOnMissingBean
	BalanceCalculationSpringEventListener balanceCalculationSpringEventListener(SegmentedBalanceCalculationWorker worker) {
		return new BalanceCalculationSpringEventListener(worker);
	}
}
