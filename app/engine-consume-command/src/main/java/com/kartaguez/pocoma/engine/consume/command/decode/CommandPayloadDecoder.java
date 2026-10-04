package com.kartaguez.pocoma.engine.consume.command.decode;

import com.kartaguez.pocoma.engine.consume.command.model.Command;
import com.kartaguez.pocoma.engine.consume.command.model.CommandType;

/** Decodes one stable serialized Command contract into its typed representation. */
public interface CommandPayloadDecoder<C extends Command> {

	CommandType commandType();

	Class<C> commandClass();

	C decode(String serializedPayload);
}
