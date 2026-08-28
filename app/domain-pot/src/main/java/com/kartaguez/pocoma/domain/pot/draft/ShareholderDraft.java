package com.kartaguez.pocoma.domain.pot.draft;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;

public record ShareholderDraft(Name name, Weight weight, UserId userId) {

	public ShareholderDraft {
		Objects.requireNonNull(name, "name must not be null");
		Objects.requireNonNull(weight, "weight must not be null");
	}
}
