package com.kartaguez.pocoma.supra.worker.projection.taskexecutor.spring;

import java.util.Objects;
import java.util.function.BooleanSupplier;

import org.springframework.context.SmartLifecycle;

public class SpringPollingWorkerLifecycle implements SmartLifecycle {

	private final Runnable start;
	private final Runnable stop;
	private final BooleanSupplier running;
	private final int phase;

	SpringPollingWorkerLifecycle(Runnable start, Runnable stop, BooleanSupplier running, int phase) {
		this.start = Objects.requireNonNull(start, "start must not be null");
		this.stop = Objects.requireNonNull(stop, "stop must not be null");
		this.running = Objects.requireNonNull(running, "running must not be null");
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
