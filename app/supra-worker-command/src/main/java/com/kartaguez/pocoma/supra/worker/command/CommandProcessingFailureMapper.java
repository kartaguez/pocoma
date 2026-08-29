package com.kartaguez.pocoma.supra.worker.command;

import static java.util.Objects.requireNonNull;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

public final class CommandProcessingFailureMapper {

	private static final String FALLBACK_CATEGORY = "COMMAND_EXECUTION_FAILURE";
	private static final int MAX_MESSAGE_LENGTH = 1_000;

	private final Clock clock;

	public CommandProcessingFailureMapper(Clock clock) {
		this.clock = requireNonNull(clock, "clock must not be null");
	}

	public ProcessingFailure map(Throwable failure) {
		requireNonNull(failure, "failure must not be null");
		String category = failure.getClass().getSimpleName();
		if (category.isBlank()) {
			category = FALLBACK_CATEGORY;
		}
		String message = failure.getMessage();
		if (message == null || message.isBlank()) {
			message = category;
		}
		message = message.replace('\r', ' ').replace('\n', ' ');
		if (message.length() > MAX_MESSAGE_LENGTH) {
			message = message.substring(0, MAX_MESSAGE_LENGTH);
		}
		return new ProcessingFailure(category, message, clock.instant());
	}
}
