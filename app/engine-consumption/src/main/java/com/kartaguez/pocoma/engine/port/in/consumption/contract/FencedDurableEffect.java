package com.kartaguez.pocoma.engine.port.in.consumption.contract;

/**
 * Participant in the owner's local transaction. Stale invocation and provisional SQL are permitted;
 * all required effects must roll back on authority loss. No independent commit, external effect,
 * lifecycle mutation or swallowed fencing failure is allowed. All business writes precede the
 * common provenance/flush/CAS/claim suffix; only claim closure follows its winning CAS.
 */
@FunctionalInterface
public interface FencedDurableEffect {
	void apply();
}
