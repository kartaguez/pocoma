package com.kartaguez.pocoma.supra.http.rest.spring.dto.request;

import java.util.UUID;

public record UpdateExpenseDetailsRequest(
		UUID payerId,
		long amountNumerator,
		long amountDenominator,
		String label,
		long expectedVersion) {
}
