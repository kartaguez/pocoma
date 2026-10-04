package com.kartaguez.pocoma.engine.consume.command.decode;

import com.kartaguez.pocoma.engine.consume.command.model.Command;
import com.kartaguez.pocoma.engine.consume.command.model.CommandType;

/** Generic boundary for decoding an opaque durable Command payload. */
@FunctionalInterface
public interface CommandDecoder {

	Command decode(CommandType commandType, String serializedPayload);
}
