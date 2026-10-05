package com.kartaguez.pocoma.engine.consume.command.port.out;
import java.util.Optional;
import com.kartaguez.pocoma.contracts.command.CommandId;
import com.kartaguez.pocoma.contracts.command.RecordedCommand;
/** Authoritative reload for execution. */
public interface RecordedCommandPort { Optional<RecordedCommand> findById(CommandId commandId); }
