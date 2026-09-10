package com.kartaguez.pocoma.infra.persistence.jpa.adapter.projection;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.kartaguez.pocoma.domain.pot.value.id.PotId;
import com.kartaguez.pocoma.engine.read.projection.HistoricalPotReconstructionException;
import com.kartaguez.pocoma.engine.read.projection.HistoricalPotSnapshotSource;
import com.kartaguez.pocoma.domain.projection.PotVersionMetadata;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaExpenseHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaExpenseShareRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaPotHeaderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.core.JpaShareholderRepository;
import com.kartaguez.pocoma.infra.persistence.jpa.repository.JpaPotGlobalVersionRepository;

@Component
public class JpaHistoricalPotSnapshotSourceAdapter implements HistoricalPotSnapshotSource {

	private final JpaPotHeaderRepository pots;
	private final JpaShareholderRepository shareholders;
	private final JpaExpenseHeaderRepository expenses;
	private final JpaExpenseShareRepository shares;
	private final JpaPotGlobalVersionRepository versions;

	public JpaHistoricalPotSnapshotSourceAdapter(
			JpaPotHeaderRepository pots,
			JpaShareholderRepository shareholders,
			JpaExpenseHeaderRepository expenses,
			JpaExpenseShareRepository shares,
			JpaPotGlobalVersionRepository versions) {
		this.pots = pots;
		this.shareholders = shareholders;
		this.expenses = expenses;
		this.shares = shares;
		this.versions = versions;
	}

	@Override
	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public HistoricalPotSnapshot load(PotId potId, long version) {
		var createdAt = versions.findVersionCreatedAt(potId.value(), version)
				.orElseThrow(() -> new HistoricalPotReconstructionException(
						"POT_VERSION_METADATA_ABSENT",
						"No Pot version metadata at requested version"));
		var header = pots.findActiveAtVersion(potId.value(), version)
				.orElseThrow(() -> new HistoricalPotReconstructionException(
						"POT_HEADER_ABSENT",
						"No Pot header at requested version"));

		var shareholderRows = shareholders.findActiveAtVersion(potId.value(), version);
		ensureUnique(
				shareholderRows.stream().map(row -> row.shareholderId()).toList(),
				"DUPLICATE_SHAREHOLDER");

		var expenseRows = expenses.findByPotActiveAtVersion(potId.value(), version);
		ensureUnique(
				expenseRows.stream().map(row -> row.expenseId()).toList(),
				"DUPLICATE_EXPENSE");

		var historicalExpenses = expenseRows.stream().map(expense -> {
			if (!expense.potId().equals(potId.value())) {
				throw new HistoricalPotReconstructionException(
						"EXPENSE_POT_MISMATCH",
						"Expense belongs to another Pot");
			}

			var shareRows = shares.findActiveAtVersion(expense.expenseId(), version);
			ensureUnique(
					shareRows.stream().map(row -> row.shareholderId()).toList(),
					"DUPLICATE_EXPENSE_SHARE");
			if (shareRows.stream().anyMatch(share -> !share.potId().equals(potId.value()))) {
				throw new HistoricalPotReconstructionException(
						"EXPENSE_SHARE_POT_MISMATCH",
						"Expense share belongs to another Pot");
			}

			return new HistoricalExpense(
					expense.toDomain(),
					shareRows.stream().map(row -> row.toDomain()).toList());
		}).toList();

		return new HistoricalPotSnapshot(
				new PotVersionMetadata(potId, version, createdAt),
				header.toDomain(),
				shareholderRows.stream().map(row -> row.toDomain()).toList(),
				historicalExpenses);
	}

	private static void ensureUnique(List<UUID> ids, String code) {
		if (new HashSet<>(ids).size() != ids.size()) {
			throw new HistoricalPotReconstructionException(
					code,
					"Several active rows for one identity");
		}
	}
}
