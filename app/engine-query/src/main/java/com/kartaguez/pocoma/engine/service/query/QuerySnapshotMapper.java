package com.kartaguez.pocoma.engine.service.query;

import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.pot.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.projection.Balance;
import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;
import com.kartaguez.pocoma.engine.port.in.query.result.BalanceSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.result.PotBalancesSnapshot;
import com.kartaguez.pocoma.engine.snapshot.EngineSnapshotMapper;
import com.kartaguez.pocoma.engine.snapshot.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.snapshot.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.snapshot.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.snapshot.PotShareholdersSnapshot;

final class QuerySnapshotMapper {

	private QuerySnapshotMapper() {
	}

	static PotHeaderSnapshot toSnapshot(PotHeader potHeader, long version) {
		return EngineSnapshotMapper.toSnapshot(potHeader, version);
	}

	static PotShareholdersSnapshot toSnapshot(PotShareholders potShareholders, long version) {
		return EngineSnapshotMapper.toSnapshot(potShareholders, version);
	}

	static ExpenseHeaderSnapshot toSnapshot(ExpenseHeader expenseHeader, long version) {
		return EngineSnapshotMapper.toSnapshot(expenseHeader, version);
	}

	static ExpenseSharesSnapshot toSnapshot(ExpenseId expenseId, ExpenseShares expenseShares, long version) {
		return EngineSnapshotMapper.toSnapshot(expenseId, expenseShares, version);
	}

	static PotBalancesSnapshot toSnapshot(PotBalances potBalances) {
		return new PotBalancesSnapshot(
				potBalances.potId(),
				potBalances.version(),
				potBalances.balances().values().stream()
						.map(QuerySnapshotMapper::toSnapshot)
						.collect(Collectors.toSet()));
	}

	static BalanceSnapshot toSnapshot(Balance balance) {
		return new BalanceSnapshot(balance.shareholderId(), balance.value());
	}
}
