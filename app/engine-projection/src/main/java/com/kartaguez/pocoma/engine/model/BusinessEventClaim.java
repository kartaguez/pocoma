package com.kartaguez.pocoma.engine.model;

import java.util.Objects;
import java.util.UUID;

public record BusinessEventClaim(BusinessEventEnvelope event, UUID claimToken) {

	public BusinessEventClaim {
		Objects.requireNonNull(event, "event must not be null");
		Objects.requireNonNull(claimToken, "claimToken must not be null");
	}
}
