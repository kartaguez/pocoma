package com.kartaguez.pocoma.domain.projection;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public record ShareholderWeight(ShareholderId shareholderId, Weight weight) {

	public ShareholderWeight {
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(weight, "weight must not be null");
	}
}
