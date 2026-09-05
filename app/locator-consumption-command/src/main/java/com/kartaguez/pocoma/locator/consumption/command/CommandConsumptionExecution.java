package com.kartaguez.pocoma.locator.consumption.command;

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput;
import com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionResult;
import com.kartaguez.pocoma.engine.command.execution.ExecuteRecordedCommandUseCase;
import com.kartaguez.pocoma.engine.command.execution.RecordedCommandExecutionResult;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionArtifact;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionInput;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecution;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecutionContext;
import com.kartaguez.pocoma.engine.port.in.consumption.result.ConsumptionExecutionResult;

/** Adapts the specialized Command result to generic consumption provenance and outcome. */
public final class CommandConsumptionExecution {

	private final ExecuteRecordedCommandUseCase executeRecordedCommand;

	public CommandConsumptionExecution(ExecuteRecordedCommandUseCase executeRecordedCommand) {
		this.executeRecordedCommand = requireNonNull(executeRecordedCommand, "executeRecordedCommand must not be null");
	}

	public ConsumptionExecution forCommand(CommandId commandId) {
		requireNonNull(commandId, "commandId must not be null");
		return context -> execute(commandId, context);
	}

	private ConsumptionExecutionResult execute(CommandId commandId, ConsumptionExecutionContext context) {
		RecordedCommandExecutionResult result = executeRecordedCommand.execute(commandId);
		List<ConsumptionInput> inputs = result.inputs().stream()
				.map(input -> consumptionInput(context, input))
				.toList();
		if (result instanceof RecordedCommandExecutionResult.Rejected rejected) {
			return new ConsumptionExecutionResult(
					new BusinessConsumptionOutcome.Rejected(rejected.reason().code()), inputs, List.of());
		}
		RecordedCommandExecutionResult.Succeeded succeeded = (RecordedCommandExecutionResult.Succeeded) result;
		List<ConsumptionResult> results = succeeded.artifacts().stream()
				.map(artifact -> consumptionResult(context, artifact))
				.toList();
		return new ConsumptionExecutionResult(new BusinessConsumptionOutcome.Success(), inputs, results);
	}

	private static ConsumptionInput consumptionInput(
			ConsumptionExecutionContext context, CommandExecutionInput input) {
		return new ConsumptionInput(context.slotId(), input.type(), input.id(), input.version());
	}

	private static ConsumptionResult consumptionResult(
			ConsumptionExecutionContext context, CommandExecutionArtifact artifact) {
		Optional<String> subjectType = artifact.subject().map(CommandExecutionInput::type);
		Optional<String> subjectId = artifact.subject().map(CommandExecutionInput::id);
		OptionalLong subjectVersion = artifact.subject().isPresent()
				? OptionalLong.of(artifact.subject().orElseThrow().version())
				: OptionalLong.empty();
		return new ConsumptionResult(context.slotId(), artifact.space(), artifact.type(), artifact.id(),
				artifact.version(), subjectType, subjectId, subjectVersion, artifact.createdAt());
	}
}
