package com.kartaguez.pocoma.domain.pot.draft;

import java.util.Objects;

import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ExpenseShareDraft(ShareholderId shareholderId, Weight weight) {

	public ExpenseShareDraft {
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(weight, "weight must not be null");
	}
}
