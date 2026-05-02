package com.kartaguez.pocoma.orchestrator.claimable.wake;

import java.time.Duration;
import java.util.Set;
import java.util.function.Predicate;

@Deprecated(forRemoval = true)
public class WakeablePollingLoop<S, K> extends WakeSignalWaiter<S, K> {
	public WakeablePollingLoop(
			WorkWakeBus<S, K> wakeBus,
			Set<S> wakeSignals,
			Predicate<K> keyPredicate,
			Duration timeout,
			boolean wakeSignalsEnabled) {
		super(wakeBus, wakeSignals, keyPredicate, timeout, wakeSignalsEnabled);
	}
}
