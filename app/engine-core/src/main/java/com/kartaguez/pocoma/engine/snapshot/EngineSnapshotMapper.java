package com.kartaguez.pocoma.engine.snapshot;

import java.util.stream.Collectors;

import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.ExpenseShares;
import com.kartaguez.pocoma.domain.pot.aggregate.PotHeader;
import com.kartaguez.pocoma.domain.pot.aggregate.PotShareholders;
import com.kartaguez.pocoma.domain.pot.value.id.ExpenseId;

public final class EngineSnapshotMapper {

	private EngineSnapshotMapper() {
	}

	public static PotHeaderSnapshot toSnapshot(PotHeader potHeader, long version) {
		return new PotHeaderSnapshot(
				potHeader.id(),
				potHeader.label(),
				potHeader.creatorId(),
				potHeader.deleted(),
				version);
	}

	public static PotShareholdersSnapshot toSnapshot(PotShareholders potShareholders, long version) {
		return new PotShareholdersSnapshot(
				potShareholders.potId(),
				potShareholders.shareholders().values().stream().collect(Collectors.toSet()),
				version);
	}

	public static ExpenseHeaderSnapshot toSnapshot(ExpenseHeader expenseHeader, long version) {
		return new ExpenseHeaderSnapshot(
				expenseHeader.id(),
				expenseHeader.potId(),
				expenseHeader.payerId(),
				expenseHeader.amount(),
				expenseHeader.label(),
				expenseHeader.deleted(),
				version);
	}

	public static ExpenseSharesSnapshot toSnapshot(ExpenseId expenseId, ExpenseShares expenseShares, long version) {
		return new ExpenseSharesSnapshot(
				expenseId,
				expenseShares.potId(),
				expenseShares.shares(),
				version);
	}
}
