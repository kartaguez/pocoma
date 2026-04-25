package com.kartaguez.pocoma.supra.http.rest.spring.dto.response;

public record ErrorResponse(String code, String message, int status, String path) {
}
