package com.kartaguez.pocoma.engine.projection.balance;

import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.List;

import com.kartaguez.pocoma.domain.pot.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.projection.balance.ProjectedExpense;

public record HistoricalPotBalanceSource(PotHeader header, PotShareholders shareholders,
		Collection<ProjectedExpense> expenses, long version) {
	public HistoricalPotBalanceSource {
		requireNonNull(header); requireNonNull(shareholders);
		expenses = List.copyOf(requireNonNull(expenses));
		if (version < 1) throw new IllegalArgumentException("version must be greater than or equal to 1");
		if (!header.id().equals(shareholders.potId()))
			throw new IllegalArgumentException("header and shareholders must reference the same Pot");
	}
}
