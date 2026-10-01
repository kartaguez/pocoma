package com.kartaguez.pocoma.engine.pot.command.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotDetailsCommand;

public final class UpdatePotDetailsCommandDecoder extends AbstractJacksonCommandPayloadDecoder<UpdatePotDetailsCommand> {

	public UpdatePotDetailsCommandDecoder(ObjectMapper objectMapper) {
		super(PotCommandTypes.POT_DETAILS_UPDATE_V1, UpdatePotDetailsCommand.class, objectMapper);
	}
}
