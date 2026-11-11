package com.kartaguez.pocoma.supra.web.write;

public record ApiErrorResponse(String code, String message, int status, String path) {}
