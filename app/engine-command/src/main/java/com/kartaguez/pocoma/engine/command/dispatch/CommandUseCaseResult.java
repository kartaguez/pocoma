package com.kartaguez.pocoma.engine.command.dispatch;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionInput;
import com.kartaguez.pocoma.engine.command.model.CommandProducedEvent;

/** Functional result of a typed Command use case, before durable event append. */
public sealed interface CommandUseCaseResult {

	List<CommandExecutionInput> inputs();

	record Succeeded(
			List<CommandExecutionInput> inputs,
			List<CommandProducedEvent> events) implements CommandUseCaseResult {

		public Succeeded {
			inputs = List.copyOf(requireNonNull(inputs, "inputs must not be null"));
			events = List.copyOf(requireNonNull(events, "events must not be null"));
		}
	}

	record Rejected(
			TerminalReason reason,
			List<CommandExecutionInput> inputs) implements CommandUseCaseResult {

		public Rejected {
			requireNonNull(reason, "reason must not be null");
			inputs = List.copyOf(requireNonNull(inputs, "inputs must not be null"));
		}
	}
}
