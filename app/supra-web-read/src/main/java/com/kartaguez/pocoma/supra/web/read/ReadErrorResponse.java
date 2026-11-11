package com.kartaguez.pocoma.supra.web.read;

public record ReadErrorResponse(String code, String message, int status, String path) {}
