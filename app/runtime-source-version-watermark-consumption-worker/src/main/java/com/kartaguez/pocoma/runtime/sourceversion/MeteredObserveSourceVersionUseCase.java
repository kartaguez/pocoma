package com.kartaguez.pocoma.runtime.sourceversion;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import com.kartaguez.pocoma.engine.read.projection.ObserveSourceVersionInput;
import com.kartaguez.pocoma.engine.read.projection.ObserveSourceVersionUseCase;
import com.kartaguez.pocoma.engine.read.projection.SourceVersionObservation;

final class MeteredObserveSourceVersionUseCase implements ObserveSourceVersionUseCase {
	private final ObserveSourceVersionUseCase delegate;
	private final Counter advanced;
	private final Counter unchanged;
	private final Counter errors;

	MeteredObserveSourceVersionUseCase(ObserveSourceVersionUseCase delegate, MeterRegistry registry) {
		this.delegate = delegate;
		this.advanced = counter(registry, "advanced");
		this.unchanged = counter(registry, "unchanged");
		this.errors = counter(registry, "error");
	}

	@Override
	public SourceVersionObservation observe(ObserveSourceVersionInput input) {
		try {
			SourceVersionObservation result = delegate.observe(input);
			if (result instanceof SourceVersionObservation.Advanced) advanced.increment();
			else unchanged.increment();
			return result;
		}
		catch (RuntimeException failure) {
			errors.increment();
			throw failure;
		}
	}

	private static Counter counter(MeterRegistry registry, String outcome) {
		return Counter.builder("pocoma.source.version.watermark.observations")
				.tag("outcome", outcome).register(registry);
	}
}
