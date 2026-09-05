package com.kartaguez.pocoma.engine.command.port.out;

import java.util.Optional;

import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.command.model.RecordedCommand;

/** Insert-only recording and authoritative reload boundary for durable Commands. */
public interface RecordedCommandPort {

	void insert(RecordedCommand command);

	Optional<RecordedCommand> findById(CommandId commandId);
}
