package com.kartaguez.pocoma.supra.worker.projection.core.wakeup;

@FunctionalInterface
public interface ProjectionWorkerWakeSubscription extends AutoCloseable {

	@Override
	void close();
}
