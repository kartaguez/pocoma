package com.kartaguez.pocoma.engine.port.in.projection.intent;

import java.util.Objects;

import com.kartaguez.pocoma.engine.model.BusinessEventEnvelope;

public record BuildProjectionTaskCommand(BusinessEventEnvelope event) {

	public BuildProjectionTaskCommand {
		Objects.requireNonNull(event, "event must not be null");
	}
}
