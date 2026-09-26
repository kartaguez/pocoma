package com.kartaguez.pocoma.orchestrator.consumption.fenced;

import java.util.List;

/** One short-lived, non-transactional cursor over candidates for fenced finalization. */
@FunctionalInterface
public interface FencedConsumptionCandidateSearch<C> extends AutoCloseable {
	List<C> nextPage(int limit);

	@Override
	default void close() {
	}
}
