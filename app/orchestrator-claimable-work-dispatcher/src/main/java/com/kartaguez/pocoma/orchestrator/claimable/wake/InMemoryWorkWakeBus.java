package com.kartaguez.pocoma.orchestrator.claimable.wake;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

public class InMemoryWorkWakeBus<S, K> implements WorkWakeBus<S, K> {

	private final CopyOnWriteArrayList<Subscription<S, K>> subscriptions = new CopyOnWriteArrayList<>();

	@Override
	public void publish(WorkWakeEvent<S, K> event) {
		Objects.requireNonNull(event, "event must not be null");
		for (Subscription<S, K> subscription : subscriptions) {
			if (subscription.signals.contains(event.signal()) && subscription.keyPredicate.test(event.key())) {
				subscription.listener.run();
			}
		}
	}

	@Override
	public WorkWakeSubscription subscribe(Set<S> signals, Predicate<K> keyPredicate, Runnable listener) {
		Objects.requireNonNull(signals, "signals must not be null");
		Objects.requireNonNull(keyPredicate, "keyPredicate must not be null");
		Objects.requireNonNull(listener, "listener must not be null");
		if (signals.isEmpty()) {
			throw new IllegalArgumentException("signals must not be empty");
		}
		Subscription<S, K> subscription = new Subscription<>(Set.copyOf(signals), keyPredicate, listener);
		subscriptions.add(subscription);
		return () -> subscriptions.remove(subscription);
	}

	private record Subscription<S, K>(Set<S> signals, Predicate<K> keyPredicate, Runnable listener) {
	}
}
