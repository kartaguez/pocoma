package com.kartaguez.pocoma.engine.exception;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.consumption.key.ConsumptionKey;

/** Signals a corrupted FAILED slot whose terminal claim failure is missing. */
public final class MissingTerminalConsumptionFailureException extends IllegalStateException {

	private final ConsumptionKey consumptionKey;

	public MissingTerminalConsumptionFailureException(ConsumptionKey consumptionKey) {
		super("terminal failure is missing for consumption key " + requireNonNull(consumptionKey));
		this.consumptionKey = consumptionKey;
	}

	public ConsumptionKey consumptionKey() {
		return consumptionKey;
	}
}
