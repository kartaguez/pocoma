package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;

import java.util.Optional;
import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record PotProjectionShareholder(ShareholderId shareholderId, String name, Fraction weight,
		Optional<UserId> userId, boolean deleted) {
	public PotProjectionShareholder {
		requireNonNull(shareholderId);
		requireNonNull(name);
		requireNonNull(weight);
		requireNonNull(userId);
		if (name.isBlank()) {
			throw new IllegalArgumentException("name must not be blank");
		}
		if (weight.compareTo(Fraction.ZERO) < 0) {
			throw new IllegalArgumentException("weight must not be negative");
		}
	}
}
