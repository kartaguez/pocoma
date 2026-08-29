package com.kartaguez.pocoma.supra.worker.task;

@FunctionalInterface
public interface TaskWorkerObservation {
	void record(TaskWorkerRunObservation observation);
}
