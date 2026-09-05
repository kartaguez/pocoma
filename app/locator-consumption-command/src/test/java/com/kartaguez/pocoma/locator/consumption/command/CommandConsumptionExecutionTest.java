package com.kartaguez.pocoma.locator.consumption.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.kartaguez.pocoma.domain.consumption.claim.ClaimId;
import com.kartaguez.pocoma.domain.consumption.lifecycle.TerminalReason;
import com.kartaguez.pocoma.engine.command.execution.RecordedCommandExecutionResult;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionArtifact;
import com.kartaguez.pocoma.engine.command.model.CommandExecutionInput;
import com.kartaguez.pocoma.engine.command.model.CommandId;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.BusinessConsumptionOutcome;
import com.kartaguez.pocoma.engine.port.in.consumption.contract.ConsumptionExecutionContext;

class CommandConsumptionExecutionTest {

	private static final CommandId COMMAND_ID = new CommandId(UUID.randomUUID());
	private static final UUID SLOT_ID = UUID.randomUUID();
	private static final ConsumptionExecutionContext CONTEXT =
			new ConsumptionExecutionContext(SLOT_ID, ClaimId.generate());

	@Test
	void adaptsSuccessInputsArtifactsAndSubjectsInOrder() {
		CommandExecutionInput input = new CommandExecutionInput("POT", "pot-1", 4);
		CommandExecutionArtifact artifact = new CommandExecutionArtifact(
				"EVENT", "PotUpdated", "event-1", OptionalLong.empty(), Optional.of(input), Instant.EPOCH);
		var execution = new CommandConsumptionExecution(id ->
				new RecordedCommandExecutionResult.Succeeded(List.of(input), List.of(artifact)));

		var result = execution.forCommand(COMMAND_ID).execute(CONTEXT);

		assertInstanceOf(BusinessConsumptionOutcome.Success.class, result.outcome());
		assertEquals(List.of(new com.kartaguez.pocoma.domain.consumption.provenance.ConsumptionInput(
				SLOT_ID, "POT", "pot-1", 4)), result.inputs());
		assertEquals("event-1", result.results().getFirst().objectId());
		assertEquals(Optional.of("POT"), result.results().getFirst().subjectType());
		assertEquals(OptionalLong.of(4), result.results().getFirst().subjectVersion());
	}

	@Test
	void adaptsBusinessRejectionWithoutResults() {
		var execution = new CommandConsumptionExecution(id -> new RecordedCommandExecutionResult.Rejected(
				new TerminalReason("BUSINESS_CONFLICT"), List.of()));

		var result = execution.forCommand(COMMAND_ID).execute(CONTEXT);

		assertEquals(new BusinessConsumptionOutcome.Rejected("BUSINESS_CONFLICT"), result.outcome());
		assertEquals(List.of(), result.results());
	}

	@Test
	void propagatesTechnicalExceptionsUnchanged() {
		RuntimeException failure = new RuntimeException("boom");
		var execution = new CommandConsumptionExecution(id -> { throw failure; });

		assertSame(failure, assertThrows(RuntimeException.class,
				() -> execution.forCommand(COMMAND_ID).execute(CONTEXT)));
	}
}
