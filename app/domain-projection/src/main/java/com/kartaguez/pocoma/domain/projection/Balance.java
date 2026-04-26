package com.kartaguez.pocoma.domain.projection;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.Fraction;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public record Balance(ShareholderId shareholderId, Fraction value) {

	public Balance {
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(value, "value must not be null");
	}
}
