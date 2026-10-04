package com.kartaguez.pocoma.engine.consume.command.port.out;

import java.util.Optional;

import com.kartaguez.pocoma.engine.consume.command.model.CommandId;
import com.kartaguez.pocoma.engine.consume.command.model.RecordedCommand;

/** Insert-only recording and authoritative reload boundary for durable Commands. */
public interface RecordedCommandPort {

	void insert(RecordedCommand command);

	Optional<RecordedCommand> findById(CommandId commandId);
}
