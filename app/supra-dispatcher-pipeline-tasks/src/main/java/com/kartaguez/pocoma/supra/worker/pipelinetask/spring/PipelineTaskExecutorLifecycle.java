package com.kartaguez.pocoma.supra.worker.pipelinetask.spring;

import java.util.Objects;

import org.springframework.context.SmartLifecycle;

public final class PipelineTaskExecutorLifecycle implements SmartLifecycle {

	private final Runnable start;
	private final Runnable stop;
	private final java.util.function.BooleanSupplier running;
	private final int phase;

	public PipelineTaskExecutorLifecycle(
			Runnable start,
			Runnable stop,
			java.util.function.BooleanSupplier running,
			int phase) {
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
