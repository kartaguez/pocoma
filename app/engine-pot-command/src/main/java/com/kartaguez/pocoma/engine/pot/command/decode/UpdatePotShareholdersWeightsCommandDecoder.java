package com.kartaguez.pocoma.engine.pot.command.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersWeightsCommand;

public final class UpdatePotShareholdersWeightsCommandDecoder
		extends AbstractJacksonCommandPayloadDecoder<UpdatePotShareholdersWeightsCommand> {

	public UpdatePotShareholdersWeightsCommandDecoder(ObjectMapper objectMapper) {
		super(PotCommandTypes.POT_SHAREHOLDERS_WEIGHTS_UPDATE_V1,
				UpdatePotShareholdersWeightsCommand.class, objectMapper);
	}
}
