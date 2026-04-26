package com.kartaguez.pocoma.supra.http.rest.spring.dto.request;

import java.util.List;
import java.util.UUID;

public record CreateExpenseRequest(
		UUID payerId,
		long amountNumerator,
		long amountDenominator,
		String label,
		List<ExpenseShareRequest> shares,
		long expectedVersion) {
}
