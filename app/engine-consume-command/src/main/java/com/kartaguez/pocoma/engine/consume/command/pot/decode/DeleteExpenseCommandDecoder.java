package com.kartaguez.pocoma.engine.consume.command.pot.decode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.consume.command.pot.intent.DeleteExpenseCommand;

public final class DeleteExpenseCommandDecoder extends AbstractJacksonCommandPayloadDecoder<DeleteExpenseCommand> {

	public DeleteExpenseCommandDecoder(ObjectMapper objectMapper) {
		super(PotCommandTypes.EXPENSE_DELETE_V1, DeleteExpenseCommand.class, objectMapper);
	}
}
