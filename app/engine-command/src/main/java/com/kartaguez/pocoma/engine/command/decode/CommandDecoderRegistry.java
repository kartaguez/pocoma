package com.kartaguez.pocoma.engine.command.decode;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.engine.command.model.Command;
import com.kartaguez.pocoma.engine.command.model.CommandType;

/** Immutable registry of payload decoders, keyed by stable Command type. */
public final class CommandDecoderRegistry implements CommandDecoder {

	private final Map<CommandType, CommandPayloadDecoder<?>> decoders;

	public CommandDecoderRegistry(Collection<? extends CommandPayloadDecoder<?>> decoders) {
		requireNonNull(decoders, "decoders must not be null");
		this.decoders = decoders.stream()
				.map(decoder -> requireDecoder(decoder))
				.collect(Collectors.toUnmodifiableMap(
						CommandPayloadDecoder::commandType,
						Function.identity(),
						(left, right) -> {
							throw new IllegalArgumentException(
									"Duplicate Command decoder for " + left.commandType().value());
						}));
	}

	@Override
	public Command decode(CommandType commandType, String serializedPayload) {
		requireNonNull(commandType, "commandType must not be null");
		requireNonNull(serializedPayload, "serializedPayload must not be null");
		CommandPayloadDecoder<?> decoder = decoders.get(commandType);
		if (decoder == null) throw new UnknownCommandTypeException(commandType);
		try {
			Command command = decoder.decode(serializedPayload);
			if (command == null) {
				throw new InvalidCommandPayloadException(commandType, "decoder returned null");
			}
			if (!decoder.commandClass().isInstance(command)) {
				throw new InvalidCommandPayloadException(commandType,
						"decoder returned " + command.getClass().getName()
								+ " instead of " + decoder.commandClass().getName());
			}
			return command;
		}
		catch (InvalidCommandPayloadException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new InvalidCommandPayloadException(commandType, exception);
		}
	}

	private static CommandPayloadDecoder<?> requireDecoder(CommandPayloadDecoder<?> decoder) {
		requireNonNull(decoder, "decoder must not be null");
		requireNonNull(decoder.commandType(), "decoder.commandType must not be null");
		requireNonNull(decoder.commandClass(), "decoder.commandClass must not be null");
		return decoder;
	}
}
