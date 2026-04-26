package com.kartaguez.pocoma.domain.draft;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.Name;
import com.kartaguez.pocoma.domain.value.UserId;
import com.kartaguez.pocoma.domain.value.Weight;

public record ShareholderDraft(Name name, Weight weight, UserId userId) {

	public ShareholderDraft {
		Objects.requireNonNull(name, "name must not be null");
		Objects.requireNonNull(weight, "weight must not be null");
	}
}
