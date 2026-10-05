package com.kartaguez.pocoma.engine.consume.command.pot.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.UpdatePotShareholdersDetailsCommand;

public final class UpdatePotShareholdersDetailsCommandDecoder
		extends AbstractJacksonCommandPayloadDecoder<UpdatePotShareholdersDetailsCommand> {

	public UpdatePotShareholdersDetailsCommandDecoder(ObjectMapper objectMapper) {
		super(PotCommandTypes.POT_SHAREHOLDERS_DETAILS_UPDATE_V1,
				UpdatePotShareholdersDetailsCommand.class, objectMapper);
	}
}
