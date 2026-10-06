package com.kartaguez.pocoma.runtime.event.consumption;

import org.springframework.context.SmartLifecycle;

import com.kartaguez.pocoma.orchestrator.poll.consumption.ConsumptionPollingWorker;

public final class EventConsumptionWorkerLifecycle implements SmartLifecycle {
	private final ConsumptionPollingWorker worker;
	public EventConsumptionWorkerLifecycle(ConsumptionPollingWorker worker) { this.worker = worker; }
	@Override public void start() { worker.start(); }
	@Override public void stop() { worker.requestStop(); }
	@Override public void stop(Runnable callback) { worker.requestStop(callback); }
	@Override public boolean isRunning() { return worker.isRunning(); }
	@Override public boolean isAutoStartup() { return true; }
}
