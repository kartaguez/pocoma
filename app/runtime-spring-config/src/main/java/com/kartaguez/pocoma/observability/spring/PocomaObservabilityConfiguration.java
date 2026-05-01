package com.kartaguez.pocoma.observability.spring;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.kartaguez.pocoma.engine.port.out.persistence.BusinessEventOutboxPort;
import com.kartaguez.pocoma.engine.port.out.persistence.ProjectionTaskPort;
import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.observability.projection.ProjectionLagProvider;
import com.kartaguez.pocoma.observability.spring.ProjectionLagMetrics.GapBucket;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
@ConditionalOnBean(MeterRegistry.class)
public class PocomaObservabilityConfiguration {

	@Bean
	@Primary
	PocomaObservation micrometerPocomaObservation(MeterRegistry meterRegistry) {
		return new MicrometerPocomaObservation(meterRegistry);
	}

	@Bean
	ProjectionLagMetrics projectionLagMetrics(
			MeterRegistry meterRegistry,
			ProjectionLagProvider projectionLagProvider) {
		ProjectionLagMetrics metrics = new ProjectionLagMetrics(projectionLagProvider);
		for (GapBucket bucket : GapBucket.values()) {
			Gauge.builder("pocoma.projection.version.gap", metrics, value -> value.ratio(bucket))
					.tag("gap_bucket", bucket.tagValue())
					.description("Ratio of pots per projection version gap bucket.")
					.register(meterRegistry);
		}
		return metrics;
	}

	@Bean
	Object projectionBackPressureMetrics(
			MeterRegistry meterRegistry,
			BusinessEventOutboxPort businessEventOutboxPort,
			ProjectionTaskPort projectionTaskPort) {
		Gauge.builder("pocoma.projection.outbox.pending", businessEventOutboxPort,
				BusinessEventOutboxPort::countPendingOrClaimed)
				.description("Number of business outbox events pending or claimed by task builders.")
				.register(meterRegistry);
		Gauge.builder("pocoma.projection.tasks.pending", projectionTaskPort,
				ProjectionTaskPort::countPendingOrInProgress)
				.description("Number of projection tasks pending or in progress.")
				.register(meterRegistry);
		return new Object();
	}
}
