package com.kartaguez.pocoma.supra.consume.command.failure;

public enum CommandConsumptionFailureCategory {
	COMMAND_INPUT_NOT_FOUND,
	COMMAND_CONFIGURATION,
	COMMAND_TECHNICAL_INVARIANT,
	TRANSIENT,
	COMMAND_EXECUTION_FAILURE
}
