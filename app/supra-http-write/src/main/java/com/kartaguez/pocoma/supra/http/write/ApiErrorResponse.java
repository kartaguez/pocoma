package com.kartaguez.pocoma.supra.http.write;

public record ApiErrorResponse(String code, String message, int status, String path) {}
