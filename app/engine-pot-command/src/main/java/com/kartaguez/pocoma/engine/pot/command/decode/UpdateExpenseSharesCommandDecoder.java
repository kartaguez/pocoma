package com.kartaguez.pocoma.engine.pot.command.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseSharesCommand;

public final class UpdateExpenseSharesCommandDecoder
		extends AbstractJacksonCommandPayloadDecoder<UpdateExpenseSharesCommand> {

	public UpdateExpenseSharesCommandDecoder(ObjectMapper objectMapper) {
		super(PotCommandTypes.EXPENSE_SHARES_UPDATE_V1, UpdateExpenseSharesCommand.class, objectMapper);
	}
}
