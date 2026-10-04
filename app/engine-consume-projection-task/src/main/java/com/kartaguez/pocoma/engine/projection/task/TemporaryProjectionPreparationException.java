package com.kartaguez.pocoma.engine.projection.task;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;

/** An explicitly classified temporary preparation failure. */
public final class TemporaryProjectionPreparationException extends RuntimeException {
	private final ProcessingFailure failure;

	public TemporaryProjectionPreparationException(ProcessingFailure failure, Throwable cause) {
		super(requireNonNull(failure, "failure must not be null").message(), cause);
		this.failure = failure;
	}

	public ProcessingFailure failure() {
		return failure;
	}
}
