package com.kartaguez.pocoma.engine.consume.command.pot.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.AddPotShareholdersCommand;

public final class AddPotShareholdersCommandDecoder
		extends AbstractJacksonCommandPayloadDecoder<AddPotShareholdersCommand> {

	public AddPotShareholdersCommandDecoder(ObjectMapper objectMapper) {
		super(PotCommandTypes.POT_SHAREHOLDERS_ADD_V1, AddPotShareholdersCommand.class, objectMapper);
	}
}
