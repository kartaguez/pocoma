package com.kartaguez.pocoma.supra.worker.command;

public final class NoopCommandWorkerObservation implements CommandWorkerObservation {

	@Override
	public void record(CommandWorkerRunObservation observation) {
		// Deliberately empty.
	}
}
