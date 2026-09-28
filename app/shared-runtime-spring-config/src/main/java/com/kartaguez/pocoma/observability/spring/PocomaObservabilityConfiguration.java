package com.kartaguez.pocoma.observability.spring;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.kartaguez.pocoma.observability.api.PocomaObservation;

import io.micrometer.core.instrument.MeterRegistry;

@Configuration
public class PocomaObservabilityConfiguration {

	@Bean
	@Primary
	PocomaObservation micrometerPocomaObservation(MeterRegistry meterRegistry) {
		return new MicrometerPocomaObservation(meterRegistry);
	}

}
