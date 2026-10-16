package com.kartaguez.pocoma.engine.exception.consumption;

public final class ConsumptionTransactionResolutionException extends RuntimeException {
	public enum Outcome { ROLLED_BACK, COMMITTED, UNKNOWN }
	public enum Phase { COMMIT, ROLLBACK }
	private final Outcome outcome;
	private final Phase phase;
	public ConsumptionTransactionResolutionException(Outcome outcome, Phase phase, Throwable cause) {
		super("Consumption transaction " + phase + " resolved " + outcome, cause);
		this.outcome = outcome; this.phase = phase;
	}
	public Outcome outcome() { return outcome; }
	public Phase phase() { return phase; }
}
