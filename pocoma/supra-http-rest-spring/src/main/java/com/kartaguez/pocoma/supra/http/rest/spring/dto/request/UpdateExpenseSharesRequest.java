package com.kartaguez.pocoma.supra.http.rest.spring.dto.request;

import java.util.List;

public record UpdateExpenseSharesRequest(List<ExpenseShareRequest> shares, long expectedVersion) {
}
