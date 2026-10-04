package com.kartaguez.pocoma.engine.consume.command.pot.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.CreatePotCommand;

public final class CreatePotCommandDecoder extends AbstractJacksonCommandPayloadDecoder<CreatePotCommand> {

	public CreatePotCommandDecoder(ObjectMapper objectMapper) {
		super(PotCommandTypes.POT_CREATE_V1, CreatePotCommand.class, objectMapper);
	}
}
