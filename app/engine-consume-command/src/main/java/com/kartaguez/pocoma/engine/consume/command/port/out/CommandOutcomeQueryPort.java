package com.kartaguez.pocoma.engine.consume.command.port.out;

import java.util.Optional;

import com.kartaguez.pocoma.contracts.command.CommandId;
import com.kartaguez.pocoma.engine.consume.command.model.CommandOutcome;

public interface CommandOutcomeQueryPort {
	Optional<CommandOutcome> findByCommandId(CommandId commandId);
}
