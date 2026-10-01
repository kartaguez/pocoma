package com.kartaguez.pocoma;

public record ApiErrorResponse(String code, String message, int status, String path) {}
