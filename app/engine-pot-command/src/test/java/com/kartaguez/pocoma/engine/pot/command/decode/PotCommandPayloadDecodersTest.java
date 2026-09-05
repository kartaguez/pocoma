package com.kartaguez.pocoma.engine.pot.command.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kartaguez.pocoma.engine.command.decode.CommandDecoderRegistry;
import com.kartaguez.pocoma.engine.command.decode.InvalidCommandPayloadException;
import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.port.in.command.intent.AddPotShareholdersCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreateExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.CreatePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeleteExpenseCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.DeletePotCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdateExpenseSharesCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersDetailsCommand;
import com.kartaguez.pocoma.engine.port.in.command.intent.UpdatePotShareholdersWeightsCommand;

class PotCommandPayloadDecodersTest {

	private static final UUID POT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID EXPENSE_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
	private static final UUID USER_ID = UUID.fromString("30000000-0000-0000-0000-000000000001");
	private static final UUID SHAREHOLDER_ID = UUID.fromString("40000000-0000-0000-0000-000000000001");

	@Test
	void decodesEveryStableV1PayloadIntoTheExistingTypedCommand() {
		CommandDecoderRegistry registry = registry();
		List<Case> cases = List.of(
				new Case(PotCommandTypes.POT_CREATE_V1,
						json("{\"label\":\"Trip\",\"creatorId\":\"%s\"}", USER_ID),
						new CreatePotCommand("Trip", USER_ID)),
				new Case(PotCommandTypes.EXPENSE_CREATE_V1,
						json("{\"potId\":\"%s\",\"payerId\":\"%s\",\"amountNumerator\":42,\"amountDenominator\":1,\"label\":\"Dinner\",\"shares\":[{\"shareholderId\":\"%s\",\"weightNumerator\":1,\"weightDenominator\":2}],\"expectedVersion\":3}",
								POT_ID, SHAREHOLDER_ID, SHAREHOLDER_ID),
						new CreateExpenseCommand(POT_ID, SHAREHOLDER_ID, 42, 1, "Dinner",
								Set.of(new CreateExpenseCommand.ExpenseShareInput(SHAREHOLDER_ID, 1, 2)), 3)),
				new Case(PotCommandTypes.POT_SHAREHOLDERS_ADD_V1,
						json("{\"potId\":\"%s\",\"shareholders\":[{\"name\":\"Alice\",\"weightNumerator\":1,\"weightDenominator\":2}],\"expectedVersion\":3}", POT_ID),
						new AddPotShareholdersCommand(POT_ID,
								Set.of(new AddPotShareholdersCommand.ShareholderInput("Alice", 1, 2)), 3)),
				new Case(PotCommandTypes.POT_DELETE_V1,
						json("{\"potId\":\"%s\",\"expectedVersion\":3}", POT_ID),
						new DeletePotCommand(POT_ID, 3)),
				new Case(PotCommandTypes.EXPENSE_DELETE_V1,
						json("{\"expenseId\":\"%s\",\"expectedVersion\":3}", EXPENSE_ID),
						new DeleteExpenseCommand(EXPENSE_ID, 3)),
				new Case(PotCommandTypes.POT_DETAILS_UPDATE_V1,
						json("{\"potId\":\"%s\",\"label\":\"New trip\",\"expectedVersion\":3}", POT_ID),
						new UpdatePotDetailsCommand(POT_ID, "New trip", 3)),
				new Case(PotCommandTypes.EXPENSE_DETAILS_UPDATE_V1,
						json("{\"expenseId\":\"%s\",\"payerId\":\"%s\",\"amountNumerator\":21,\"amountDenominator\":2,\"label\":\"Lunch\",\"expectedVersion\":3}",
								EXPENSE_ID, SHAREHOLDER_ID),
						new UpdateExpenseDetailsCommand(EXPENSE_ID, SHAREHOLDER_ID, 21, 2, "Lunch", 3)),
				new Case(PotCommandTypes.EXPENSE_SHARES_UPDATE_V1,
						json("{\"expenseId\":\"%s\",\"shares\":[{\"shareholderId\":\"%s\",\"weightNumerator\":1,\"weightDenominator\":1}],\"expectedVersion\":3}",
								EXPENSE_ID, SHAREHOLDER_ID),
						new UpdateExpenseSharesCommand(EXPENSE_ID,
								Set.of(new UpdateExpenseSharesCommand.ExpenseShareInput(SHAREHOLDER_ID, 1, 1)), 3)),
				new Case(PotCommandTypes.POT_SHAREHOLDERS_DETAILS_UPDATE_V1,
						json("{\"potId\":\"%s\",\"shareholders\":[{\"shareholderId\":\"%s\",\"name\":\"Alice\",\"userId\":\"%s\"}],\"expectedVersion\":3}",
								POT_ID, SHAREHOLDER_ID, USER_ID),
						new UpdatePotShareholdersDetailsCommand(POT_ID,
								Set.of(new UpdatePotShareholdersDetailsCommand.ShareholderDetailsInput(
										SHAREHOLDER_ID, "Alice", USER_ID)), 3)),
				new Case(PotCommandTypes.POT_SHAREHOLDERS_WEIGHTS_UPDATE_V1,
						json("{\"potId\":\"%s\",\"shareholders\":[{\"shareholderId\":\"%s\",\"weightNumerator\":3,\"weightDenominator\":4}],\"expectedVersion\":3}",
								POT_ID, SHAREHOLDER_ID),
						new UpdatePotShareholdersWeightsCommand(POT_ID,
								Set.of(new UpdatePotShareholdersWeightsCommand.ShareholderWeightInput(
										SHAREHOLDER_ID, 3, 4)), 3)));

		for (Case testCase : cases) {
			Command decoded = registry.decode(testCase.type(), testCase.payload());
			assertEquals(testCase.expected(), decoded, testCase.type().value());
			assertInstanceOf(Command.class, decoded);
		}
	}

	@Test
	void rejectsUnknownPropertiesMalformedJsonAndInvalidDomainValues() {
		CommandDecoderRegistry registry = registry();

		assertThrows(InvalidCommandPayloadException.class, () -> registry.decode(
				PotCommandTypes.POT_CREATE_V1,
				json("{\"label\":\"Trip\",\"creatorId\":\"%s\",\"unknown\":true}", USER_ID)));
		assertThrows(InvalidCommandPayloadException.class,
				() -> registry.decode(PotCommandTypes.POT_CREATE_V1, "{"));
		assertThrows(InvalidCommandPayloadException.class, () -> registry.decode(
				PotCommandTypes.EXPENSE_CREATE_V1,
				json("{\"potId\":\"%s\",\"payerId\":\"%s\",\"amountNumerator\":-1,\"amountDenominator\":1,\"label\":\"Dinner\",\"shares\":[{\"shareholderId\":\"%s\",\"weightNumerator\":1,\"weightDenominator\":1}],\"expectedVersion\":3}",
						POT_ID, SHAREHOLDER_ID, SHAREHOLDER_ID)));
		assertThrows(InvalidCommandPayloadException.class, () -> registry.decode(
				PotCommandTypes.POT_CREATE_V1,
				"{\"label\":\"Trip\"}"));
		assertThrows(InvalidCommandPayloadException.class, () -> registry.decode(
				PotCommandTypes.POT_CREATE_V1,
				json("{\"label\":null,\"creatorId\":\"%s\"}", USER_ID)));
		assertThrows(InvalidCommandPayloadException.class, () -> registry.decode(
				PotCommandTypes.POT_DELETE_V1,
				json("{\"potId\":\"%s\",\"expectedVersion\":0}", POT_ID)));
		assertThrows(InvalidCommandPayloadException.class, () -> registry.decode(
				PotCommandTypes.POT_SHAREHOLDERS_WEIGHTS_UPDATE_V1,
				json("{\"potId\":\"%s\",\"shareholders\":[{\"shareholderId\":\"%s\",\"weightNumerator\":1,\"weightDenominator\":0}],\"expectedVersion\":3}",
						POT_ID, SHAREHOLDER_ID)));
	}

	@Test
	void decodedCollectionsRemainImmutable() {
		AddPotShareholdersCommand command = assertInstanceOf(AddPotShareholdersCommand.class, registry().decode(
				PotCommandTypes.POT_SHAREHOLDERS_ADD_V1,
				json("{\"potId\":\"%s\",\"shareholders\":[{\"name\":\"Alice\",\"weightNumerator\":1,\"weightDenominator\":2}],\"expectedVersion\":3}", POT_ID)));

		assertThrows(UnsupportedOperationException.class, () -> command.shareholders().clear());
	}

	@Test
	void exposesOneImmutableDecoderForEveryStableType() {
		var decoders = PotCommandPayloadDecoders.all(new ObjectMapper());
		assertEquals(10, decoders.size());
		assertEquals(10, decoders.stream().map(decoder -> decoder.commandType()).distinct().count());
		assertThrows(UnsupportedOperationException.class, () -> decoders.clear());
		new CommandDecoderRegistry(decoders);
	}

	private static CommandDecoderRegistry registry() {
		return new CommandDecoderRegistry(PotCommandPayloadDecoders.all(new ObjectMapper()));
	}

	private static String json(String template, Object... values) {
		return template.formatted(values);
	}

	private record Case(com.kartaguez.pocoma.engine.command.model.CommandType type, String payload, Command expected) {
	}
}
