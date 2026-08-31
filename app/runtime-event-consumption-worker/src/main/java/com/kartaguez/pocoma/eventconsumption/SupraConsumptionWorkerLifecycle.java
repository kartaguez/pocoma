package com.kartaguez.pocoma.eventconsumption;

import org.springframework.context.SmartLifecycle;

import com.kartaguez.pocoma.supra.consumption.SupraConsumptionWorker;

final class SupraConsumptionWorkerLifecycle implements SmartLifecycle {
	private final SupraConsumptionWorker worker;
	SupraConsumptionWorkerLifecycle(SupraConsumptionWorker worker) { this.worker = worker; }
	@Override public void start() { worker.start(); }
	@Override public void stop() { worker.stop(); }
	@Override public boolean isRunning() { return worker.isRunning(); }
	@Override public boolean isAutoStartup() { return true; }
}
