package com.kartaguez.pocoma.orchestrator.command.admission.port.out;

import com.kartaguez.pocoma.engine.command.model.CommandId;

public interface CommandIdGenerator {

	CommandId generate();
}
