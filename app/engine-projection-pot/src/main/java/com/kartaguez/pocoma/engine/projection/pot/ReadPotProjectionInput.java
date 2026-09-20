package com.kartaguez.pocoma.engine.projection.pot;

import static java.util.Objects.requireNonNull;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.kartaguez.pocoma.domain.pot.value.Amount;
import com.kartaguez.pocoma.domain.pot.value.Label;
import com.kartaguez.pocoma.domain.pot.value.Name;
import com.kartaguez.pocoma.domain.pot.value.UserId;
import com.kartaguez.pocoma.domain.pot.value.Weight;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;

public record ReadPotProjectionInput(
		PotId potId, long version, Label name,
		List<ShareholderInput> shareholders, List<ExpenseInput> expenses) {
	public ReadPotProjectionInput {
		requireNonNull(potId, "potId must not be null");
		requireNonNull(name, "name must not be null");
		shareholders = List.copyOf(requireNonNull(shareholders, "shareholders must not be null"));
		expenses = List.copyOf(requireNonNull(expenses, "expenses must not be null"));
		if (version < 1) throw new IllegalArgumentException("version must be positive");
	}

	public record ShareholderInput(ShareholderId id, Name name, Optional<UserId> userId, Weight part) {
		public ShareholderInput {
			requireNonNull(id); requireNonNull(name); requireNonNull(userId); requireNonNull(part);
		}
	}

	public record ExpenseInput(ExpenseId id, Label name, Amount amount, LocalDate date,
			ShareholderId payerId, List<ShareInput> shares) {
		public ExpenseInput {
			requireNonNull(id); requireNonNull(name); requireNonNull(amount); requireNonNull(date); requireNonNull(payerId);
			shares = List.copyOf(requireNonNull(shares));
		}
	}

	public record ShareInput(ShareholderId shareholderId, Weight part) {
		public ShareInput { requireNonNull(shareholderId); requireNonNull(part); }
	}
}
