package com.kartaguez.pocoma.domain.projection;

import static java.util.Objects.requireNonNull;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import com.kartaguez.pocoma.domain.pot.value.UserId;

public record PotProjection(ProjectionIdentity identity, PotProjectionStatus status, String label,
		UserId creatorId, List<PotProjectionShareholder> shareholders, List<PotProjectionExpense> expenses) {
	public PotProjection {
		requireNonNull(identity);
		requireNonNull(status);
		requireNonNull(label);
		requireNonNull(creatorId);
		requireNonNull(shareholders);
		requireNonNull(expenses);
		if (label.isBlank()) {
			throw new IllegalArgumentException("label must not be blank");
		}
		shareholders = shareholders.stream().sorted(Comparator.comparing(s -> s.shareholderId().value())).toList();
		expenses = expenses.stream().sorted(Comparator.comparing(e -> e.expenseId().value())).toList();
		if (shareholders.stream().map(PotProjectionShareholder::shareholderId).distinct().count()
				!= shareholders.size()) {
			throw new IllegalArgumentException("duplicate shareholder");
		}
		if (expenses.stream().map(PotProjectionExpense::expenseId).distinct().count() != expenses.size()) {
			throw new IllegalArgumentException("duplicate expense");
		}
		var ids = shareholders.stream()
				.map(PotProjectionShareholder::shareholderId)
				.collect(Collectors.toSet());
		for (var expense : expenses) {
			if (!ids.contains(expense.payerId())) {
				throw new IllegalArgumentException("expense payer is not a shareholder");
			}
			if (expense.shares().stream().anyMatch(share -> !ids.contains(share.shareholderId()))) {
				throw new IllegalArgumentException("expense share references an unknown shareholder");
			}
		}
	}
}
