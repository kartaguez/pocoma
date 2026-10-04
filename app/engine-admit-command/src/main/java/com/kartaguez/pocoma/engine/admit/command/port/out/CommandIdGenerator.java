package com.kartaguez.pocoma.engine.admit.command.port.out;

import com.kartaguez.pocoma.engine.consume.command.model.CommandId;

public interface CommandIdGenerator {

	CommandId generate();
}
