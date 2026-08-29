package com.kartaguez.pocoma.supra.worker.command;

@FunctionalInterface
public interface CommandWorkerObservation {

	void record(CommandWorkerRunObservation observation);
}
