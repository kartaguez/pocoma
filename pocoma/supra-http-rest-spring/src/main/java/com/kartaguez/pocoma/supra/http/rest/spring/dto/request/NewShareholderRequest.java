package com.kartaguez.pocoma.supra.http.rest.spring.dto.request;

public record NewShareholderRequest(String name, long weightNumerator, long weightDenominator) {
}
