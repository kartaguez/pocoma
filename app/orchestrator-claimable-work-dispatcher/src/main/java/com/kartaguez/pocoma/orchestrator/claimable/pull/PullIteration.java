package com.kartaguez.pocoma.orchestrator.claimable.pull;

/**
 * Performs at most one complete pull-processing cycle.
 *
 * @return {@code true} when one item reached its technical outcome, or
 *         {@code false} when no item was available
 */
@FunctionalInterface
public interface PullIteration {

	boolean runOnce();
}
