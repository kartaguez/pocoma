package com.kartaguez.pocoma.domain.pot.projection;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ShareholderDetails(ShareholderId shareholderId, Name name, UserId userId) {

	public ShareholderDetails {
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(name, "name must not be null");
	}
}
