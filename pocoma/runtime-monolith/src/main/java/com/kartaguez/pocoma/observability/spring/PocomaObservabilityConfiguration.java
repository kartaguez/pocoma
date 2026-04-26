package com.kartaguez.pocoma.observability.spring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.observability.projection.ProjectionLagProvider;
import com.kartaguez.pocoma.observability.spring.ProjectionLagMetrics.GapBucket;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Configuration
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
}
