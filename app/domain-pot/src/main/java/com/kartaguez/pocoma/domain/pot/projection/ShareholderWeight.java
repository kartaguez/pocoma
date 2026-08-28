package com.kartaguez.pocoma.domain.pot.projection;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ShareholderWeight(ShareholderId shareholderId, Weight weight) {

	public ShareholderWeight {
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(weight, "weight must not be null");
	}
}
