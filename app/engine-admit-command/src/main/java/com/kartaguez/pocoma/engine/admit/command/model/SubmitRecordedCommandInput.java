package com.kartaguez.pocoma.engine.admit.command.model;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.contracts.authentication.AuthenticatedExternalPrincipal;
import com.kartaguez.pocoma.domain.useridentity.BindingId;
import com.kartaguez.pocoma.contracts.command.CommandType;

public record SubmitRecordedCommandInput(
		CommandType commandType,
		BindingId bindingId,
		String serializedPayload,
		AuthenticatedExternalPrincipal principal) {

	public SubmitRecordedCommandInput {
		requireNonNull(commandType, "commandType must not be null");
		requireNonNull(bindingId, "bindingId must not be null");
		requireNonNull(serializedPayload, "serializedPayload must not be null");
		requireNonNull(principal, "principal must not be null");
	}
}
