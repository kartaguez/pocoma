package com.kartaguez.pocoma.engine.port.in.intent;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record UpdatePotShareholdersWeightsCommand(UUID potId, Set<ShareholderWeightInput> shareholders, long expectedVersion) {

	public UpdatePotShareholdersWeightsCommand {
		Objects.requireNonNull(potId, "potId must not be null");
		shareholders = Set.copyOf(Objects.requireNonNull(shareholders, "shareholders must not be null"));

		if (shareholders.isEmpty()) {
			throw new IllegalArgumentException("shareholders must not be empty");
		}
		if (expectedVersion < 1) {
			throw new IllegalArgumentException("expectedVersion must be greater than or equal to 1");
		}
	}

	public record ShareholderWeightInput(UUID shareholderId, long weightNumerator, long weightDenominator) {

		public ShareholderWeightInput {
			Objects.requireNonNull(shareholderId, "shareholderId must not be null");
			validateStrictlyPositiveWeight(weightNumerator, weightDenominator);
		}
	}

	private static void validateStrictlyPositiveWeight(long numerator, long denominator) {
		if (denominator == 0) {
			throw new IllegalArgumentException("weightDenominator must not be zero");
		}
		if (numerator == 0 || (numerator > 0) != (denominator > 0)) {
			throw new IllegalArgumentException("weight must be strictly positive");
		}
	}
}
