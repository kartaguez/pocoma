package com.kartaguez.pocoma.orchestrator.command.admission.model;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.engine.command.model.CommandType;

public record SubmitRecordedCommandInput(
		CommandType commandType,
		String serializedPayload,
		AuthenticatedExternalPrincipal principal) {

	public SubmitRecordedCommandInput {
		requireNonNull(commandType, "commandType must not be null");
		requireNonNull(serializedPayload, "serializedPayload must not be null");
		requireNonNull(principal, "principal must not be null");
	}
}
