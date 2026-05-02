package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.util.function.BooleanSupplier;

import org.springframework.context.SmartLifecycle;

public class ProjectionTaskBuilderPollingWorkerLifecycle implements SmartLifecycle {

	private final Runnable start;
	private final Runnable stop;
	private final BooleanSupplier running;
	private final int phase;

	ProjectionTaskBuilderPollingWorkerLifecycle(Runnable start, Runnable stop, BooleanSupplier running, int phase) {
		this.start = start;
		this.stop = stop;
		this.running = running;
		this.phase = phase;
	}

	@Override
	public void start() {
		start.run();
	}

	@Override
	public void stop() {
		stop.run();
	}

	@Override
	public boolean isRunning() {
		return running.getAsBoolean();
	}

	@Override
	public int getPhase() {
		return phase;
	}
}
