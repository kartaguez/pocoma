package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.util.Objects;

import org.springframework.context.SmartLifecycle;

import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.SegmentedProjectionTaskExecutor;

public class SpringProjectionTaskExecutorLifecycle implements SmartLifecycle {

	private final SegmentedProjectionTaskExecutor executor;

	SpringProjectionTaskExecutorLifecycle(SegmentedProjectionTaskExecutor executor) {
		this.executor = Objects.requireNonNull(executor, "executor must not be null");
	}

	@Override
	public void start() {
		executor.start();
	}

	@Override
	public void stop() {
		executor.stop();
	}

	@Override
	public boolean isRunning() {
		return executor.isRunning();
	}

	@Override
	public int getPhase() {
		return Integer.MAX_VALUE - 100;
	}
}
