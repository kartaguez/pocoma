package com.kartaguez.pocoma.supra.worker.balancecalculation.spring;

import java.util.Objects;

import org.springframework.context.SmartLifecycle;

import com.kartaguez.pocoma.supra.worker.balancecalculation.core.SegmentedBalanceCalculationWorker;

public class BalanceCalculationWorkerLifecycle implements SmartLifecycle {

	private final SegmentedBalanceCalculationWorker worker;

	BalanceCalculationWorkerLifecycle(SegmentedBalanceCalculationWorker worker) {
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
