package com.kartaguez.pocoma.engine.pot.command.decode;

import static java.util.Objects.requireNonNull;

import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.kartaguez.pocoma.engine.command.decode.CommandPayloadDecoder;
import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.command.model.CommandType;

abstract class AbstractJacksonCommandPayloadDecoder<C extends Command> implements CommandPayloadDecoder<C> {

	private final CommandType commandType;
	private final Class<C> commandClass;
	private final ObjectReader reader;

	AbstractJacksonCommandPayloadDecoder(CommandType commandType, Class<C> commandClass, ObjectMapper objectMapper) {
		this.commandType = requireNonNull(commandType, "commandType must not be null");
		this.commandClass = requireNonNull(commandClass, "commandClass must not be null");
		this.reader = requireNonNull(objectMapper, "objectMapper must not be null")
				.readerFor(commandClass)
				.with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	}

	@Override
	public final CommandType commandType() {
		return commandType;
	}

	@Override
	public final Class<C> commandClass() {
		return commandClass;
	}

	@Override
	public final C decode(String serializedPayload) {
		requireNonNull(serializedPayload, "serializedPayload must not be null");
		try {
			return reader.readValue(serializedPayload);
		}
		catch (IOException exception) {
			throw new IllegalArgumentException("Cannot decode " + commandType.value() + " payload", exception);
		}
	}
}
