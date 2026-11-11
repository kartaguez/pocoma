package com.kartaguez.pocoma.engine.consume.projectiontask.historical;

public final class HistoricalPotReconstructionException extends RuntimeException {
	private final String failureCode;

	public HistoricalPotReconstructionException(String failureCode, String message) {
		super(message);
		this.failureCode = failureCode;
	}

	public String failureCode() {
		return failureCode;
	}
}
