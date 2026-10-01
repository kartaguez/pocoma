package com.kartaguez.pocoma.engine.command.decode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.command.model.CommandType;

class CommandDecoderRegistryTest {

	private static final CommandType TYPE = new CommandType("TEST_COMMAND_V1");

	@Test
	void selectsTheDecoderRegisteredForTheStableCommandType() {
		CommandDecoderRegistry registry = new CommandDecoderRegistry(List.of(decoder(payload -> new TestCommand(payload))));

		TestCommand decoded = assertInstanceOf(TestCommand.class, registry.decode(TYPE, "payload"));

		assertEquals("payload", decoded.value());
	}

	@Test
	void rejectsUnknownAndDuplicateCommandTypes() {
		assertThrows(UnknownCommandTypeException.class,
				() -> new CommandDecoderRegistry(List.of()).decode(TYPE, "payload"));
		assertThrows(IllegalArgumentException.class,
				() -> new CommandDecoderRegistry(List.of(decoder(TestCommand::new), decoder(TestCommand::new))));
	}

	@Test
	void rejectsNullConfigurationEntries() {
		assertThrows(NullPointerException.class, () -> new CommandDecoderRegistry(null));
		assertThrows(NullPointerException.class,
				() -> new CommandDecoderRegistry(Collections.singletonList(null)));
	}

	@Test
	void wrapsDecoderFailuresAsInvalidPayload() {
		IllegalArgumentException cause = new IllegalArgumentException("broken payload");
		CommandDecoderRegistry registry = new CommandDecoderRegistry(List.of(decoder(payload -> {
			throw cause;
		})));

		InvalidCommandPayloadException failure = assertThrows(
				InvalidCommandPayloadException.class, () -> registry.decode(TYPE, "broken"));

		assertSame(cause, failure.getCause());
	}

	@Test
	void rejectsNullAndUnexpectedDecoderResults() {
		CommandDecoderRegistry nullResult = new CommandDecoderRegistry(List.of(decoder(payload -> null)));
		assertThrows(InvalidCommandPayloadException.class, () -> nullResult.decode(TYPE, "payload"));

		CommandDecoderRegistry wrongType = new CommandDecoderRegistry(List.of(wrongTypeDecoder()));
		assertThrows(InvalidCommandPayloadException.class, () -> wrongType.decode(TYPE, "payload"));
	}

	private static CommandPayloadDecoder<TestCommand> decoder(Decoder decoder) {
		return new CommandPayloadDecoder<>() {
			@Override public CommandType commandType() { return TYPE; }
			@Override public Class<TestCommand> commandClass() { return TestCommand.class; }
			@Override public TestCommand decode(String serializedPayload) { return decoder.decode(serializedPayload); }
		};
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static CommandPayloadDecoder<?> wrongTypeDecoder() {
		return new CommandPayloadDecoder() {
			@Override public CommandType commandType() { return TYPE; }
			@Override public Class commandClass() { return TestCommand.class; }
			@Override public Command decode(String serializedPayload) { return new OtherCommand(); }
		};
	}

	@FunctionalInterface
	private interface Decoder {
		TestCommand decode(String payload);
	}

	private record TestCommand(String value) implements Command {}
	private record OtherCommand() implements Command {}
}
