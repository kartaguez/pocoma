package com.kartaguez.pocoma.observability.spring;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.kartaguez.pocoma.observability.api.PocomaObservation;
import com.kartaguez.pocoma.observability.projection.ProjectionObservationContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public final class MicrometerPocomaObservation implements PocomaObservation {

	private static final Logger LOGGER = LoggerFactory.getLogger(MicrometerPocomaObservation.class);

	private final MeterRegistry meterRegistry;

	public MicrometerPocomaObservation(MeterRegistry meterRegistry) {
		this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
	}

	@Override
	public void commandCommitted(String operation, String eventType, long requestStartedAtNanos, long committedAtNanos) {
		Timer.builder("pocoma.command.persist.latency")
				.tag("operation", operation)
				.tag("event_type", eventType)
				.tag("status", "success")
				.register(meterRegistry)
				.record(committedAtNanos - requestStartedAtNanos, TimeUnit.NANOSECONDS);
		LOGGER.info("Command transaction committed");
	}

	@Override
	public void eventSubmitted(ProjectionObservationContext context, long submittedAtNanos) {
		try (Scope ignored = openProjectionScope(context)) {
			LOGGER.info("Projection event submitted");
		}
	}

	@Override
	public Scope openProjectionScope(ProjectionObservationContext context) {
		Objects.requireNonNull(context, "context must not be null");
		String previousTraceId = MDC.get("traceId");
		String previousPotId = MDC.get("potId");
		String previousVersion = MDC.get("version");
		String previousEventType = MDC.get("eventType");
		if (context.traceId() != null && !context.traceId().isBlank()) {
			MDC.put("traceId", context.traceId());
		}
		MDC.put("potId", context.potId());
		MDC.put("version", Long.toString(context.targetVersion()));
		MDC.put("eventType", context.sourceEventType());
		MDC.put("operation", "projection");
		return () -> {
			restore("traceId", previousTraceId);
			restore("potId", previousPotId);
			restore("version", previousVersion);
			restore("eventType", previousEventType);
			MDC.remove("operation");
		};
	}

	@Override
	public void projectionStarted(ProjectionObservationContext context, long startedAtNanos) {
		if (context.commandCommittedAtNanos() != null) {
			Timer.builder("pocoma.projection.event.start.latency")
					.tag("event_type", context.sourceEventType())
					.tag("status", "success")
					.register(meterRegistry)
					.record(startedAtNanos - context.commandCommittedAtNanos(), TimeUnit.NANOSECONDS);
		}
		LOGGER.info("Projection task started");
	}

	@Override
	public void projectionSucceeded(ProjectionObservationContext context, long startedAtNanos, long completedAtNanos) {
		recordProjectionProcessing(context, startedAtNanos, completedAtNanos, "success");
		if (context.commandCommittedAtNanos() != null) {
			Timer.builder("pocoma.projection.end_to_end.latency")
					.tag("event_type", context.sourceEventType())
					.tag("status", "success")
					.register(meterRegistry)
					.record(completedAtNanos - context.commandCommittedAtNanos(), TimeUnit.NANOSECONDS);
		}
		LOGGER.info("Projection task completed");
	}

	@Override
	public void projectionFailed(ProjectionObservationContext context, long startedAtNanos, long failedAtNanos) {
		try (Scope ignored = openProjectionScope(context)) {
			recordProjectionProcessing(context, startedAtNanos, failedAtNanos, "failure");
			LOGGER.error("Projection task failed");
		}
	}

	@Override
	public void projectionRetry(ProjectionObservationContext context, int attempt) {
		try (Scope ignored = openProjectionScope(context)) {
			Counter.builder("pocoma.projection.retry.total")
					.tag("event_type", context.sourceEventType())
					.register(meterRegistry)
					.increment();
			LOGGER.warn("Projection task retry scheduled, attempt={}", attempt);
		}
	}

	private void recordProjectionProcessing(
			ProjectionObservationContext context,
			long startedAtNanos,
			long completedAtNanos,
			String status) {
		Timer.builder("pocoma.projection.processing.duration")
				.tag("event_type", context.sourceEventType())
				.tag("status", status)
				.register(meterRegistry)
				.record(completedAtNanos - startedAtNanos, TimeUnit.NANOSECONDS);
	}

	private static void restore(String key, String value) {
		if (value == null) {
			MDC.remove(key);
		}
		else {
			MDC.put(key, value);
		}
	}
}
