package com.kartaguez.pocoma.supra.http.rest.spring.dto.response;

public record ExpenseHeaderResponse(
		String id,
		String potId,
		String payerId,
		FractionResponse amount,
		String label,
		boolean deleted,
		long version) {
}
