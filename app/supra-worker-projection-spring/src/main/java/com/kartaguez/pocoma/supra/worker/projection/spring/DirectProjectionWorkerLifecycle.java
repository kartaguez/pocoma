package com.kartaguez.pocoma.supra.worker.projection.spring;

import java.util.Objects;

import org.springframework.context.SmartLifecycle;

import com.kartaguez.pocoma.supra.worker.projection.core.taskexecutor.DirectSegmentedProjectionWorker;

public class DirectProjectionWorkerLifecycle implements SmartLifecycle {

	private final DirectSegmentedProjectionWorker worker;

	DirectProjectionWorkerLifecycle(DirectSegmentedProjectionWorker worker) {
		this.worker = Objects.requireNonNull(worker, "worker must not be null");
	}

	@Override
	public void start() {
		worker.start();
	}

	@Override
	public void stop() {
		worker.stop();
	}

	@Override
	public boolean isRunning() {
		return worker.isRunning();
	}

	@Override
	public int getPhase() {
		return Integer.MAX_VALUE - 100;
	}
}
