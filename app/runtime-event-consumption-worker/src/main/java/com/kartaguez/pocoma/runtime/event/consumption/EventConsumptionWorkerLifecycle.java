package com.kartaguez.pocoma.runtime.event.consumption;

import org.springframework.context.SmartLifecycle;

import com.kartaguez.pocoma.supra.consumption.ConsumptionPollingWorker;

final class EventConsumptionWorkerLifecycle implements SmartLifecycle {
	private final ConsumptionPollingWorker worker;
	EventConsumptionWorkerLifecycle(ConsumptionPollingWorker worker) { this.worker = worker; }
	@Override public void start() { worker.start(); }
	@Override public void stop() { worker.stop(); }
	@Override public boolean isRunning() { return worker.isRunning(); }
	@Override public boolean isAutoStartup() { return true; }
}
