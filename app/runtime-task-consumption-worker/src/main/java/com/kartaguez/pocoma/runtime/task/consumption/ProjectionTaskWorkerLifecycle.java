package com.kartaguez.pocoma.runtime.task.consumption;

import org.springframework.context.SmartLifecycle;

import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;

final class ProjectionTaskWorkerLifecycle implements SmartLifecycle {
	private final ConsumptionPollingWorker worker;

	ProjectionTaskWorkerLifecycle(ConsumptionPollingWorker worker) {
		this.worker = worker;
	}

	@Override
	public void start() {
		worker.start();
	}

	@Override
	public void stop() {
		worker.requestStop();
	}

	@Override
	public void stop(Runnable callback) {
		worker.requestStop(callback);
	}

	@Override
	public boolean isRunning() {
		return worker.isRunning();
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}
}
