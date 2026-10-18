package com.kartaguez.pocoma.engine.consume.command.pot.intent;

import java.util.Objects;
import java.util.UUID;

import com.kartaguez.pocoma.engine.consume.command.model.Command;

public record DeleteExpenseCommand(UUID expenseId, long observedVersion) implements Command {

	public DeleteExpenseCommand {
		Objects.requireNonNull(expenseId, "expenseId must not be null");

		if (observedVersion < 1) {
			throw new IllegalArgumentException("observedVersion must be greater than or equal to 1");
		}
	}
}
