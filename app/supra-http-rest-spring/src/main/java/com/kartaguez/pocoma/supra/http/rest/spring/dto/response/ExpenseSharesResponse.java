package com.kartaguez.pocoma.supra.http.rest.spring.dto.response;

import java.util.List;

public record ExpenseSharesResponse(String expenseId, String potId, List<ExpenseShareResponse> shares, long version) {
}
