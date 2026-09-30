package com.kartaguez.pocoma.supra.http.write.command;

public record SubmitCommandRequest(String commandType, Object payload) {}
