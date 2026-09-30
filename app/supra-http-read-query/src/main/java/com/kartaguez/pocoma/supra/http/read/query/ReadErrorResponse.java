package com.kartaguez.pocoma.supra.http.read.query;

public record ReadErrorResponse(String code, String message, int status, String path) {}
