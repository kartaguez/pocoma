package com.kartaguez.pocoma.engine.service.projection;

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
import com.kartaguez.pocoma.engine.port.in.projection.usecase.CalculatePotBalancesAtVersionUseCase;
import com.kartaguez.pocoma.engine.port.out.persistence.HistoricalPotBalanceSourcePort;

/** Pure exact-version Balance calculation; it never advances or persists a projection cursor. */
public final class CalculatePotBalancesAtVersionService implements CalculatePotBalancesAtVersionUseCase {
	private final HistoricalPotBalanceSourcePort sources;
	private final PotBalancesCalculator calculator;

	public CalculatePotBalancesAtVersionService(HistoricalPotBalanceSourcePort sources,
			PotBalancesCalculator calculator) {
		this.sources = requireNonNull(sources, "sources must not be null");
		this.calculator = requireNonNull(calculator, "calculator must not be null");
	}

	@Override
	public PotBalances calculate(PotId potId, long version) {
		requireNonNull(potId, "potId must not be null");
		if (version < 1) throw new IllegalArgumentException("version must be greater than or equal to 1");
		var source = sources.loadAtVersion(potId, version);
		if (!source.header().id().equals(potId) || source.version() != version) {
			throw new IllegalStateException("historical Pot source does not match the requested identity");
		}
		Map<ShareholderId, Balance> zeroBalances = source.shareholders().shareholders().keySet().stream()
				.collect(Collectors.toMap(id -> id, id -> new Balance(id, Fraction.ZERO)));
		Map<ShareholderId, Balance> expenseBalances = calculator.calculateExpensesBalances(potId, source.expenses());
		return new PotBalances(potId, version, BalanceMapOperations.add(zeroBalances, expenseBalances));
	}
}
