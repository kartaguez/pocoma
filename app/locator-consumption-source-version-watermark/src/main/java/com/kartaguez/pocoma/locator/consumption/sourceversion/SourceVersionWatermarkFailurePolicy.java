package com.kartaguez.pocoma.locator.consumption.sourceversion;

import com.kartaguez.pocoma.engine.port.in.consumption.failure.ConsumptionFailurePolicy;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureContext;
import com.kartaguez.pocoma.engine.port.in.consumption.failure.FailureDecision;
import com.kartaguez.pocoma.engine.service.consumption.DefaultConsumptionFailurePolicy;

public final class SourceVersionWatermarkFailurePolicy implements ConsumptionFailurePolicy {
	private final ConsumptionFailurePolicy retries = new DefaultConsumptionFailurePolicy();

	@Override
	public FailureDecision decide(FailureContext context) {
		if (context.failure().category().equals(SourceVersionWatermarkFailureClassifier.INPUT_NOT_FOUND)) {
			return new FailureDecision.Fail();
		}
		return retries.decide(context);
	}
}
