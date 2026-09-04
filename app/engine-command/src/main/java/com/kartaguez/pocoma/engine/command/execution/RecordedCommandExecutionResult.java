package com.kartaguez.pocoma.engine.command.execution;

import static java.util.Objects.requireNonNull;

import java.util.List;

import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionArtifact;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionInput;

/** Command-specific execution result, independent from the consumption engine. */
public sealed interface RecordedCommandExecutionResult {

	List<CommandExecutionInput> inputs();

	record Succeeded(
			List<CommandExecutionInput> inputs,
			List<CommandExecutionArtifact> artifacts) implements RecordedCommandExecutionResult {

		public Succeeded {
			inputs = List.copyOf(requireNonNull(inputs, "inputs must not be null"));
			artifacts = List.copyOf(requireNonNull(artifacts, "artifacts must not be null"));
		}
	}

	record Rejected(
			TerminalReason reason,
			List<CommandExecutionInput> inputs) implements RecordedCommandExecutionResult {

		public Rejected {
			requireNonNull(reason, "reason must not be null");
			inputs = List.copyOf(requireNonNull(inputs, "inputs must not be null"));
		}
	}
}
