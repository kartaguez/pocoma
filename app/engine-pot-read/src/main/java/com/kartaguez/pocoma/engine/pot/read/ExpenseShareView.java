package com.kartaguez.pocoma.engine.pot.read;

import static java.util.Objects.requireNonNull;

import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ExpenseShareView(ShareholderId shareholderId, Weight part) {
	public ExpenseShareView {
		requireNonNull(shareholderId, "shareholderId must not be null");
		requireNonNull(part, "part must not be null");
	}
}
