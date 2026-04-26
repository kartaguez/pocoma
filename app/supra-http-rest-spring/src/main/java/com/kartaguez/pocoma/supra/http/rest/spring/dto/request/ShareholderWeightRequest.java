package com.kartaguez.pocoma.supra.http.rest.spring.dto.request;

import java.util.UUID;

public record ShareholderWeightRequest(UUID shareholderId, long weightNumerator, long weightDenominator) {
}
