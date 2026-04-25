package com.kartaguez.pocoma.supra.http.rest.spring.dto.request;

import java.util.UUID;

public record ExpenseShareRequest(UUID shareholderId, long weightNumerator, long weightDenominator) {
}
