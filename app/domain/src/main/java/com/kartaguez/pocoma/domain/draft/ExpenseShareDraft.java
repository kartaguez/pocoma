package com.kartaguez.pocoma.domain.draft;

import java.util.Objects;

import com.kartaguez.pocoma.domain.value.Weight;
import com.kartaguez.pocoma.domain.value.id.ShareholderId;

public record ExpenseShareDraft(ShareholderId shareholderId, Weight weight) {

	public ExpenseShareDraft {
		Objects.requireNonNull(shareholderId, "shareholderId must not be null");
		Objects.requireNonNull(weight, "weight must not be null");
	}
}
