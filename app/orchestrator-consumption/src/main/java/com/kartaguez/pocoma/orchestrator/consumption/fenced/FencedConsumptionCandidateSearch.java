package com.kartaguez.pocoma.orchestrator.consumption.fenced;

import java.util.List;

/** One short-lived, non-transactional cursor over candidates for fenced finalization. */
@FunctionalInterface
public interface FencedConsumptionCandidateSearch<C> extends AutoCloseable {
	List<C> nextPage(int limit);

	/** Called in page order immediately before the candidate is mapped and acquired. */
	default void candidateInspected(C candidate) {
	}

	@Override
	default void close() {
	}
}
