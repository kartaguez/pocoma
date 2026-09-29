package com.kartaguez.pocoma.engine.command.port.out;

import java.util.Optional;

import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.CommandOutcome;

public interface CommandOutcomeQueryPort {
	Optional<CommandOutcome> findByCommandId(CommandId commandId);
}
