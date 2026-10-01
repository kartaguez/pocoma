package com.kartaguez.pocoma.engine.pot.command.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseDetailsCommand;

public final class UpdateExpenseDetailsCommandDecoder
		extends AbstractJacksonCommandPayloadDecoder<UpdateExpenseDetailsCommand> {

	public UpdateExpenseDetailsCommandDecoder(ObjectMapper objectMapper) {
		super(PotCommandTypes.EXPENSE_DETAILS_UPDATE_V1, UpdateExpenseDetailsCommand.class, objectMapper);
	}
}
