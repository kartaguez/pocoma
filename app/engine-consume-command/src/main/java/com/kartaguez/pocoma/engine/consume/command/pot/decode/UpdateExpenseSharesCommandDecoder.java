package com.kartaguez.pocoma.engine.consume.command.pot.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.UpdateExpenseSharesCommand;

public final class UpdateExpenseSharesCommandDecoder
		extends AbstractJacksonCommandPayloadDecoder<UpdateExpenseSharesCommand> {

	public UpdateExpenseSharesCommandDecoder(ObjectMapper objectMapper) {
		super(PotCommandTypes.EXPENSE_SHARES_UPDATE_V1, UpdateExpenseSharesCommand.class, objectMapper);
	}
}
