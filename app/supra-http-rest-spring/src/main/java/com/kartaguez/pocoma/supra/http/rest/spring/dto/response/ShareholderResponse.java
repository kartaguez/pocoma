package com.kartaguez.pocoma.supra.http.rest.spring.dto.response;

public record ShareholderResponse(
		String id,
		String potId,
		String name,
		FractionResponse weight,
		String userId,
		boolean deleted) {
}
