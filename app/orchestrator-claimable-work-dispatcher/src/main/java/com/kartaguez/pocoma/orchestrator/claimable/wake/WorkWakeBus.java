package com.kartaguez.pocoma.orchestrator.claimable.wake;

import java.util.Set;
import java.util.function.Predicate;

public interface WorkWakeBus<S, K> {

	void publish(WorkWakeEvent<S, K> event);

	WorkWakeSubscription subscribe(Set<S> signals, Predicate<K> keyPredicate, Runnable listener);

	default void publish(S signal, K key) {
		publish(new WorkWakeEvent<>(signal, key));
	}

	static <S, K> WorkWakeBus<S, K> noop() {
		return new WorkWakeBus<>() {
			@Override
			public void publish(WorkWakeEvent<S, K> event) {
			}

			@Override
			public WorkWakeSubscription subscribe(Set<S> signals, Predicate<K> keyPredicate, Runnable listener) {
				return () -> {
				};
			}
		};
	}
}
