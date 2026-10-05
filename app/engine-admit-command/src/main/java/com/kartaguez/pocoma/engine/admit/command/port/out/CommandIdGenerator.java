package com.kartaguez.pocoma.engine.admit.command.port.out;

import com.kartaguez.pocoma.contracts.command.CommandId;

public interface CommandIdGenerator {

	CommandId generate();
}
