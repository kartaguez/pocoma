package com.kartaguez.pocoma.supra.worker.command;

import static java.util.Objects.requireNonNull;

import java.time.Clock;
import java.util.Locale;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;

public final class CommandProcessingFailureMapper {

	private static final String FALLBACK_CATEGORY = "COMMAND_EXECUTION_FAILURE";
	private static final int MAX_MESSAGE_LENGTH = 1_000;

	private final Clock clock;

	public CommandProcessingFailureMapper(Clock clock) {
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	public ProcessingFailure map(Throwable failure) {
		requireNonNull(failure, "failure must not be null");
		String simpleName = failure.getClass().getSimpleName();
		String code = simpleName.isBlank() ? FALLBACK_CATEGORY
				: simpleName.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
						.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
		String category = FALLBACK_CATEGORY;
		if (simpleName.isBlank()) {
			simpleName = FALLBACK_CATEGORY;
		}
		String message = failure.getMessage();
		if (message == null || message.isBlank()) {
			message = simpleName;
		}
		message = message.replace('\r', ' ').replace('\n', ' ');
		if (message.length() > MAX_MESSAGE_LENGTH) {
			message = message.substring(0, MAX_MESSAGE_LENGTH);
		}
		return new ProcessingFailure(new ProcessingFailureCode(code), category, message, clock.instant());
	}
}
