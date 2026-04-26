package com.kartaguez.pocoma.observability.trace;

import java.util.Objects;

public record TraceContext(
		String traceId,
		String userId,
		String httpMethod,
		String httpPath,
		String operation,
		long requestStartedAtNanos,
		Long commandCommittedAtNanos) {

	public TraceContext {
		Objects.requireNonNull(traceId, "traceId must not be null");
		Objects.requireNonNull(httpMethod, "httpMethod must not be null");
		Objects.requireNonNull(httpPath, "httpPath must not be null");
		Objects.requireNonNull(operation, "operation must not be null");
		if (traceId.isBlank()) {
			throw new IllegalArgumentException("traceId must not be blank");
		}
		if (operation.isBlank()) {
			throw new IllegalArgumentException("operation must not be blank");
		}
		if (requestStartedAtNanos < 0) {
			throw new IllegalArgumentException("requestStartedAtNanos must be positive or zero");
		}
	}

	public TraceContext withCommandCommittedAt(long committedAtNanos) {
		if (committedAtNanos < requestStartedAtNanos) {
			throw new IllegalArgumentException("committedAtNanos must be greater than or equal to requestStartedAtNanos");
		}
		return new TraceContext(
				traceId,
				userId,
				httpMethod,
				httpPath,
				operation,
				requestStartedAtNanos,
				committedAtNanos);
	}
}
