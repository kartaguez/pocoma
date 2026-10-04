package com.kartaguez.pocoma.supra.consume.binding;

import java.time.Clock;

import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailure;
import com.kartaguez.pocoma.domain.consumption.lifecycle.ProcessingFailureCode;
import com.kartaguez.pocoma.engine.exception.consumption.LostClaimException;
import com.kartaguez.pocoma.domain.useridentity.currentbinding.CurrentBindingInvariantException;
import com.kartaguez.pocoma.orchestrator.consumption.locator.ConsumptionTechnicalFailureClassifier;

public final class BindingFactFailureClassifier implements ConsumptionTechnicalFailureClassifier {
	public static final String TERMINAL = "CURRENT_BINDING_INVARIANT";
	public static final String RETRYABLE = "CURRENT_BINDING_TECHNICAL";
	private final Clock clock;
	public BindingFactFailureClassifier(Clock clock) { this.clock = clock; }
	@Override public ProcessingFailure classify(RuntimeException failure) {
		if (failure instanceof LostClaimException) throw new IllegalArgumentException("lost claim is fenced", failure);
		boolean terminal = failure instanceof BindingFactNotFoundException
				|| failure instanceof CurrentBindingInvariantException || failure instanceof IllegalArgumentException;
		return new ProcessingFailure(new ProcessingFailureCode(failure.getClass().getSimpleName().toUpperCase()),
				terminal ? TERMINAL : RETRYABLE, String.valueOf(failure.getMessage()), clock.instant());
	}
}
