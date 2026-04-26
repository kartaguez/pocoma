package com.kartaguez.pocoma.observability.trace;

import java.util.Optional;

public final class TraceContextHolder {

	private static final ThreadLocal<TraceContext> CURRENT = new ThreadLocal<>();

	private TraceContextHolder() {
	}

	public static void set(TraceContext context) {
		CURRENT.set(context);
	}

	public static Optional<TraceContext> current() {
		return Optional.ofNullable(CURRENT.get());
	}

	public static void updateCommandCommittedAt(long committedAtNanos) {
		TraceContext context = CURRENT.get();
		if (context != null) {
			CURRENT.set(context.withCommandCommittedAt(committedAtNanos));
		}
	}

	public static void clear() {
		CURRENT.remove();
	}
}
