package com.kartaguez.pocoma.engine.command.port.out;

import com.kartaguez.pocoma.engine.command.model.CommandOutcome;

/** Atomically persists one Command outcome and its corresponding terminal Event. */
public interface CommandOutcomePublicationPort {
	void publish(CommandOutcome outcome);
}
