package com.kartaguez.pocoma.engine.read.projection;

import static java.util.Objects.requireNonNull;

import java.util.Optional;

import com.kartaguez.pocoma.domain.projection.PotProjection;
import com.kartaguez.pocoma.domain.projection.PotProjectionExpense;
import com.kartaguez.pocoma.domain.projection.PotProjectionExpenseShare;
import com.kartaguez.pocoma.domain.projection.PotProjectionShareholder;
import com.kartaguez.pocoma.domain.projection.PotProjectionStatus;
import com.kartaguez.pocoma.domain.projection.ProjectionIdentity;

public final class ReconstructPotProjectionService {
	private final HistoricalPotSnapshotSource source;

	public ReconstructPotProjectionService(HistoricalPotSnapshotSource source) {
		this.source = requireNonNull(source);
	}

	public ReconstructedPotProjection reconstruct(ProjectionIdentity identity) {
		var data = source.load(identity.generation().potId(), identity.potVersion());
		var header = data.header();
		if (!header.id().equals(identity.generation().potId())) {
			throw failure("POT_MISMATCH", "header belongs to another Pot");
		}

		var shareholders = data.shareholders().stream().map(s -> {
			if (!s.potId().equals(header.id())) {
				throw failure("SHAREHOLDER_POT_MISMATCH", "shareholder belongs to another Pot");
			}
			return new PotProjectionShareholder(
					s.id(),
					s.name().value(),
					s.weight().value(),
					Optional.ofNullable(s.userId()),
					s.deleted());
		}).toList();

		var expenses = data.expenses().stream().map(e -> {
			var h = e.header();
			if (!h.potId().equals(header.id())) {
				throw failure("EXPENSE_POT_MISMATCH", "expense belongs to another Pot");
			}
			var shares = e.shares().stream().map(s -> {
				if (!s.expenseId().equals(h.id())) {
					throw failure("EXPENSE_SHARE_MISMATCH", "share belongs to another Expense");
				}
				return new PotProjectionExpenseShare(s.shareholderId(), s.weight().value());
			}).toList();
			return new PotProjectionExpense(
					h.id(), h.payerId(), h.amount().value(), h.label().value(), h.deleted(), shares);
		}).toList();

		try {
			var projection = new PotProjection(
					identity,
					header.deleted() ? PotProjectionStatus.DELETED : PotProjectionStatus.ACTIVE,
					header.label().value(),
					header.creatorId(),
					shareholders,
					expenses);
			return new ReconstructedPotProjection(projection, data.versionMetadata());
		} catch (IllegalArgumentException exception) {
			throw failure("INCOHERENT_POT_HISTORY", exception.getMessage());
		}
	}

	private static HistoricalPotReconstructionException failure(String code, String message) {
		return new HistoricalPotReconstructionException(code, message);
	}
}
