package com.kartaguez.pocoma.engine.pot.command.decode;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.command.decode.CommandPayloadDecoder;

/** Builds the complete immutable V1 decoder set for Pot Commands. */
public final class PotCommandPayloadDecoders {

	private PotCommandPayloadDecoders() {
	}

	public static List<CommandPayloadDecoder<?>> all(ObjectMapper objectMapper) {
		requireNonNull(objectMapper, "objectMapper must not be null");
		return List.of(
				new CreatePotCommandDecoder(objectMapper),
				new CreateExpenseCommandDecoder(objectMapper),
				new AddPotShareholdersCommandDecoder(objectMapper),
				new DeletePotCommandDecoder(objectMapper),
				new DeleteExpenseCommandDecoder(objectMapper),
				new UpdatePotDetailsCommandDecoder(objectMapper),
				new UpdateExpenseDetailsCommandDecoder(objectMapper),
				new UpdateExpenseSharesCommandDecoder(objectMapper),
				new UpdatePotShareholdersDetailsCommandDecoder(objectMapper),
				new UpdatePotShareholdersWeightsCommandDecoder(objectMapper));
	}
}
