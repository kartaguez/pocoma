package com.kartaguez.pocoma.engine.pot.command.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeletePotCommand;

public final class DeletePotCommandDecoder extends AbstractJacksonCommandPayloadDecoder<DeletePotCommand> {

	public DeletePotCommandDecoder(ObjectMapper objectMapper) {
		super(PotCommandTypes.POT_DELETE_V1, DeletePotCommand.class, objectMapper);
	}
}
