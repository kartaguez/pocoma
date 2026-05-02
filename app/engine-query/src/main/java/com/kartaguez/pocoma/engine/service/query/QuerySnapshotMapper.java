package com.kartaguez.pocoma.engine.service.query;

import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.projection.Balance;
import com.kartaguez.pocoma.domain.projection.PotBalances;
import com.kartaguez.pocoma.domain.value.id.ExpenseId;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.result.ExpenseSharesSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.result.PotHeaderSnapshot;
import com.kartaguez.pocoma.engine.port.in.command.result.PotShareholdersSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.result.BalanceSnapshot;
import com.kartaguez.pocoma.engine.port.in.query.result.PotBalancesSnapshot;

final class QuerySnapshotMapper {

	private QuerySnapshotMapper() {
	}

	static PotHeaderSnapshot toSnapshot(PotHeader potHeader, long version) {
		return new PotHeaderSnapshot(
				potHeader.id(),
				potHeader.label(),
				potHeader.creatorId(),
				potHeader.deleted(),
				version);
	}

	static PotShareholdersSnapshot toSnapshot(PotShareholders potShareholders, long version) {
		return new PotShareholdersSnapshot(
				potShareholders.potId(),
				potShareholders.shareholders().values().stream().collect(Collectors.toSet()),
				version);
	}

	static ExpenseHeaderSnapshot toSnapshot(ExpenseHeader expenseHeader, long version) {
		return new ExpenseHeaderSnapshot(
				expenseHeader.id(),
				expenseHeader.potId(),
				expenseHeader.payerId(),
				expenseHeader.amount(),
				expenseHeader.label(),
				expenseHeader.deleted(),
				version);
	}

	static ExpenseSharesSnapshot toSnapshot(ExpenseId expenseId, ExpenseShares expenseShares, long version) {
		return new ExpenseSharesSnapshot(
				expenseId,
				expenseShares.potId(),
				expenseShares.shares(),
				version);
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
