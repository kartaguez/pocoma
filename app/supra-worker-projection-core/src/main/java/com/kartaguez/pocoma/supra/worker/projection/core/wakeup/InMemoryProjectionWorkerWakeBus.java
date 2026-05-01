package com.kartaguez.pocoma.supra.worker.projection.core.wakeup;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.kartaguez.pocoma.engine.model.PotPartitioner;
import com.kartaguez.pocoma.engine.model.ProjectionPartition;

public class InMemoryProjectionWorkerWakeBus implements ProjectionWorkerWakeBus {

	private final CopyOnWriteArrayList<Subscription> subscriptions = new CopyOnWriteArrayList<>();

	@Override
	public void publish(ProjectionWorkerWakeEvent event) {
		Objects.requireNonNull(event, "event must not be null");
		for (Subscription subscription : subscriptions) {
			if (subscription.signals.contains(event.signal())
					&& PotPartitioner.belongsTo(event.potId(), subscription.partition)) {
				subscription.listener.run();
			}
		}
	}

	@Override
	public ProjectionWorkerWakeSubscription subscribe(
			Set<ProjectionWorkerWakeSignal> signals,
			ProjectionPartition partition,
			Runnable listener) {
		Objects.requireNonNull(signals, "signals must not be null");
		Objects.requireNonNull(partition, "partition must not be null");
		Objects.requireNonNull(listener, "listener must not be null");
		if (signals.isEmpty()) {
			throw new IllegalArgumentException("signals must not be empty");
		}
		Subscription subscription = new Subscription(Set.copyOf(signals), partition, listener);
		subscriptions.add(subscription);
		return () -> subscriptions.remove(subscription);
	}

	private record Subscription(
			Set<ProjectionWorkerWakeSignal> signals,
			ProjectionPartition partition,
			Runnable listener) {
	}
}
