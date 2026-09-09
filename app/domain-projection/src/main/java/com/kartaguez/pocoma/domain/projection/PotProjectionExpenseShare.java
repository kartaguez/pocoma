package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record PotProjectionExpenseShare(ShareholderId shareholderId, Fraction weight) {
	public PotProjectionExpenseShare {
		requireNonNull(shareholderId);
		requireNonNull(weight);
		if (weight.compareTo(Fraction.ZERO) < 0) {
			throw new IllegalArgumentException("weight must not be negative");
		}
	}
}
