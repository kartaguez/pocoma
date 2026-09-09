package com.kartaguez.pocoma.engine.read.projection;

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
