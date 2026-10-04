package com.kartaguez.pocoma.engine.consume.command.execution;

import com.kartaguez.pocoma.engine.consume.command.model.CommandId;

/** Executes the authoritative durable Command identified by its business identifier. */
@FunctionalInterface
public interface ExecuteRecordedCommandUseCase {

	RecordedCommandExecutionResult execute(CommandId commandId);
}
