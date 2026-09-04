package com.kartaguez.pocoma.engine.port.in.command.usecase;

import com.kartaguez.pocoma.engine.port.in.command.input.ExecuteCommandInput;

/** Routes a typed Command intent to its specialized functional use case. */
@FunctionalInterface
public interface ExecuteCommandUseCase {

	void execute(ExecuteCommandInput input);
}
