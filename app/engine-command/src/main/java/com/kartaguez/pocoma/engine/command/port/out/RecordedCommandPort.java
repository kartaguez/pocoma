package com.kartaguez.pocoma.engine.command.port.out;

import java.util.Optional;

import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;

/** Reload boundary for the authoritative durable Command envelope. */
@FunctionalInterface
public interface RecordedCommandPort {

	Optional<RecordedCommand> findById(CommandId commandId);
}
