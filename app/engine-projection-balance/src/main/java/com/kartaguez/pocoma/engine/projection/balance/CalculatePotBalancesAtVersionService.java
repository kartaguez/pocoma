package com.kartaguez.pocoma.engine.projection.balance;

import static java.util.Objects.requireNonNull;

import java.util.Map;
import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pot.value.Fraction;
import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.domain.pot.value.id.ShareholderId;
import com.kartaguez.pocoma.domain.projection.balance.Balance;
import com.kartaguez.pocoma.domain.projection.balance.BalanceMapOperations;
import com.kartaguez.pocoma.domain.projection.balance.PotBalances;
import com.kartaguez.pocoma.domain.projection.balance.PotBalancesCalculator;

public final class CalculatePotBalancesAtVersionService implements CalculatePotBalancesAtVersionUseCase {
	private final HistoricalPotBalanceSourcePort sources;
	private final PotBalancesCalculator calculator;
	public CalculatePotBalancesAtVersionService(HistoricalPotBalanceSourcePort sources, PotBalancesCalculator calculator) {
		this.sources = requireNonNull(sources); this.calculator = requireNonNull(calculator);
	}
	@Override public PotBalances calculate(PotId potId, long version) {
		requireNonNull(potId);
		if (version < 1) throw new IllegalArgumentException("version must be greater than or equal to 1");
		var source = sources.loadAtVersion(potId, version);
		if (!source.header().id().equals(potId) || source.version() != version)
			throw new IllegalStateException("historical Pot source does not match the requested identity");
		Map<ShareholderId, Balance> zero = source.shareholders().shareholders().keySet().stream()
				.collect(Collectors.toMap(id -> id, id -> new Balance(id, Fraction.ZERO)));
		Map<ShareholderId, Balance> expenses = calculator.calculateExpensesBalances(potId, source.expenses());
		return new PotBalances(potId, version, BalanceMapOperations.add(zero, expenses));
	}
}
