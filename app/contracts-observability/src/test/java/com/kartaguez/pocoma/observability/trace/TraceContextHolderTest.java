package com.kartaguez.pocoma.observability.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TraceContextHolderTest {

	@AfterEach
	void clearContext() {
		TraceContextHolder.clear();
	}

	@Test
	void storesAndUpdatesTheCurrentTraceContext() {
		TraceContextHolder.set(new TraceContext(
				"trace-1",
				"user-1",
				"POST",
				"/api/pots",
				"command",
				100L,
				null));

		TraceContextHolder.updateCommandCommittedAt(150L);

		assertTrue(TraceContextHolder.current().isPresent());
		TraceContext context = TraceContextHolder.current().orElseThrow();
		assertEquals("trace-1", context.traceId());
		assertEquals(150L, context.commandCommittedAtNanos());
	}

	@Test
	void clearRemovesTheCurrentTraceContext() {
		TraceContextHolder.set(new TraceContext(
				"trace-1",
				null,
				"GET",
				"/api/pots",
				"query",
				100L,
				null));

		TraceContextHolder.clear();

		assertTrue(TraceContextHolder.current().isEmpty());
	}
}
