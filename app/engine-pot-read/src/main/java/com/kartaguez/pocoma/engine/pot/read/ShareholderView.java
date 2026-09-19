package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ShareholderView(ShareholderId shareholderId, Name name,
		Optional<UserId> userId, Weight part) {
	public ShareholderView {
		requireNonNull(shareholderId, "shareholderId must not be null");
		requireNonNull(name, "name must not be null");
		requireNonNull(userId, "userId must not be null");
		requireNonNull(part, "part must not be null");
	}
}
