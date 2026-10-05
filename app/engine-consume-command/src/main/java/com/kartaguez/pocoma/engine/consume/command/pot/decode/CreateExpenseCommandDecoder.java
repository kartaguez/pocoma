package com.kartaguez.pocoma.engine.consume.command.pot.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.CreateExpenseCommand;

public final class CreateExpenseCommandDecoder extends AbstractJacksonCommandPayloadDecoder<CreateExpenseCommand> {

	public CreateExpenseCommandDecoder(ObjectMapper objectMapper) {
		super(PotCommandTypes.EXPENSE_CREATE_V1, CreateExpenseCommand.class, objectMapper);
	}
}
