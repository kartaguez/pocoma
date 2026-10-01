package com.kartaguez.pocoma.locator.consumption.latestknownversion;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Locale;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.engine.exception.processing.event.RecordedEventNotFoundException;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionTechnicalFailureClassifier;

public final class LatestKnownVersionFailureClassifier implements ConsumptionTechnicalFailureClassifier {
	public static final String INPUT_NOT_FOUND = "SOURCE_VERSION_WATERMARK_INPUT_NOT_FOUND";
	public static final String EXECUTION_FAILURE = "SOURCE_VERSION_WATERMARK_EXECUTION_FAILURE";

	private final Clock clock;

	public LatestKnownVersionFailureClassifier(Clock clock) {
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	@Override
	public ProcessingFailure classify(RuntimeException failure) {
		requireNonNull(failure, "failure must not be null");
		if (failure instanceof LostClaimException) {
			throw new IllegalArgumentException("LostClaimException must never be classified", failure);
		}
		boolean missing = failure instanceof RecordedEventNotFoundException;
		String message = failure.getMessage();
		if (message == null || message.isBlank()) message = failure.getClass().getSimpleName();
		return new ProcessingFailure(new ProcessingFailureCode(missing ? "RECORDED_EVENT_NOT_FOUND" : code(failure)),
				missing ? INPUT_NOT_FOUND : EXECUTION_FAILURE, message, clock.instant());
	}

	private static String code(RuntimeException failure) {
		String name = failure.getClass().getSimpleName();
		if (name.isBlank()) return EXECUTION_FAILURE;
		return name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
				.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
	}
}
